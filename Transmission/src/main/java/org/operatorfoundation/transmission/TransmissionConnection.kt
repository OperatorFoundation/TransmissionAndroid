package org.operatorfoundation.transmission

import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.net.*
import java.nio.ByteBuffer
import java.util.UUID.randomUUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

class TransmissionConnection(var logger: Logger?) : Connection
{
    val id: String
    var connectionType: ConnectionType = ConnectionType.TCP
    private var buffer = ByteArray(0)

    var udpConnection: DatagramSocket? = null
    var tcpConnection: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    init
    {
        logger?.log(Level.FINE, "init TransmissionConnection called")
        id = randomUUID().toString()
    }

    constructor(host:String, port: Int, type: ConnectionType = ConnectionType.TCP, logger: Logger?) : this(logger)
    {
        when (type)
        {
            ConnectionType.TCP ->
            {
                try
                {
                    val socketAddress = InetSocketAddress(host, port)
                    this.tcpConnection = Socket()
                    this.tcpConnection!!.connect(socketAddress)
                    this.inputStream = tcpConnection!!.getInputStream()
                    this.outputStream = tcpConnection!!.getOutputStream()
                }
                catch (error: Exception)
                {
                    logger?.log(Level.SEVERE, "The socket failed to create a tcp connection with the provided host and port. ")
                    return
                }
            }

            ConnectionType.UDP ->
            {
                try
                {
                    val socketAddress = InetSocketAddress(host, port)
                    this.connectionType = ConnectionType.UDP
                    this.udpConnection = DatagramSocket()
                    this.udpConnection!!.connect(socketAddress)
                }
                catch (error: Exception)
                {
                    logger?.log(Level.SEVERE, "The socket failed to create a udp connection with the provided host and port. ")
                    return
                }
            }
        }
    }

    // Use this when you have already created a socket and connect() has been called
    constructor(tcpConnection: Socket, logger: Logger?) : this(logger)
    {
        this.tcpConnection = tcpConnection
        inputStream = tcpConnection.getInputStream()
        outputStream = tcpConnection.getOutputStream()
    }

    constructor(udpConnection: DatagramSocket, logger: Logger?) : this(logger)
    {
        this.connectionType = ConnectionType.UDP
        this.udpConnection = udpConnection
    }


    // Reads exactly size bytes
    @Synchronized
    override fun read(size: Int): ByteArray?
    {
        if (size < 1)
        {
            logger?.log(Level.WARNING, "Requested a read size less than 1.")
            return null
        }
        else if (size <= buffer.size)
        {
            val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
            val remainingBytes = buffer.drop(size).toByteArray()

            buffer = remainingBytes
            return readBytes
        }
        else
        {
            val maybeData = networkRead(size)

            if (maybeData != null)
            {
                buffer += maybeData

                if (size <= buffer.size)
                {
                    val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
                    val remainingBytes = buffer.drop(size).toByteArray()

                    buffer = remainingBytes
                    return readBytes
                }
                else
                {
                    logger?.log(Level.WARNING, "Requested a read for more data than what was available in the buffer.")
                    return null
                }
            }
            else
            {
                logger?.log(Level.WARNING, "Failed to read data from the network.")
                return null
            }
        }
    }

    // Reads up to maxSize bytes
    @Synchronized
    override fun readMaxSize(maxSize: Int): ByteArray?
    {
        try
        {
            if (maxSize < 1)
            {
                logger?.log(Level.WARNING, "Requested a max read size less than 1.")
                return null
            }

            var size = maxSize

            if (maxSize > buffer.size)
            {
                size = buffer.size
            }

            if (size > 0)
            {
                val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
                val remainingBytes = buffer.drop(size).toByteArray()

                buffer = remainingBytes
                return readBytes
            }
            else
            {
                // The buffer was empty go get more data
                var maybeData = ByteArray(maxSize)
                val bytesReadCount: Int

                when(connectionType)
                {
                    ConnectionType.UDP ->
                    {
                        if (udpConnection == null)
                        {
                            logger?.log(Level.SEVERE, "Tried to receive data but our udpConnection does not exist.")
                            return null
                        }

                        val datagramPacket = DatagramPacket(maybeData, maxSize)
                        udpConnection!!.receive(datagramPacket)
                        bytesReadCount = datagramPacket.length
                    }
                    ConnectionType.TCP ->
                    {
                        if (inputStream == null)
                        {
                            logger?.log(Level.SEVERE, "Tried to read on a tcp connection that has no input stream.")
                            return null
                        }

                        bytesReadCount = inputStream!!.read(maybeData)
                    }
                }

                if (bytesReadCount <= 0)
                {
                    logger?.log(Level.WARNING, "tried to read data from the network and got nothing.")
                    return null
                }
                else if (bytesReadCount < maxSize) // We initialized maybeData to be max size, trim off the excess 0's if we read fewer bytes than that
                {
                    maybeData = maybeData.dropLast(maybeData.size - bytesReadCount).toByteArray()
                }

                // We got some data, add it to the buffer
                buffer += maybeData

                val targetSize = min(maxSize, buffer.size)
                val result = buffer.dropLast(buffer.size - targetSize).toByteArray()
                buffer = buffer.drop(targetSize).toByteArray()

                return result
            }
        }
        catch (readError: Exception)
        {
            logger?.log(Level.SEVERE, "TransmissionAndroid.readMaxSize: Connection inputStream encountered an error while trying to read: $readError")
            return null
        }
    }

    @Synchronized
    override fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?
    {
        println("TransmissionAndroid.readWithLengthPrefix() called")
        val maybeLength: Int?

        when (prefixSizeInBits) {
            8 -> {
                val maybeLengthData = networkRead(prefixSizeInBits / 8)

                if (maybeLengthData == null) {
                    logger?.log(
                        Level.WARNING,
                        "Failed to read the 8 bit length prefix from the network."
                    )
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).get().toInt()
            }
            16 -> {
                val maybeLengthData = networkRead(prefixSizeInBits / 8)

                if (maybeLengthData == null) {
                    println("Failed to read the 16 bit length prefix from the network.")
                    logger?.log(
                        Level.WARNING,
                        "Failed to read the 16 bit length prefix from the network."
                    )
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).short.toInt()
            }
            32 -> {
                val maybeLengthData = networkRead(prefixSizeInBits / 8)

                if (maybeLengthData == null) {
                    logger?.log(
                        Level.WARNING,
                        "Failed to read the 32 bit length prefix from the network."
                    )
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).int
            }
            64 -> {
                val maybeLengthData = networkRead(prefixSizeInBits / 8)

                if (maybeLengthData == null) {
                    logger?.log(
                        Level.WARNING,
                        "Failed to read the 64 bit length prefix from the network."
                    )
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).long.toInt()
            }
            else ->
            {
                logger?.log(Level.SEVERE, "Unable to complete a read request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                return null
            }
        }

        return networkRead(maybeLength)
    }

    private fun networkRead(size: Int): ByteArray?
    {
        println("TransmissionAndroid.networkRead(size: $size) called")
        var networkBuffer = ByteArray(2048)
        var networkBufferSize = 0

        while (networkBufferSize < size)
        {
            try
            {
                when (connectionType)
                {
                    ConnectionType.TCP ->
                    {
                        if (inputStream == null)
                        {
                            logger?.log(Level.FINE, "TransmissionAndroid.networkRead: tcp connection that has no input stream.")
                            println("TransmissionAndroid.networkRead: tcp connection that has no input stream.")
                            return null
                        }

                        println("TransmissionAndroid.networkRead: TCP - calling inputStream.read requested: $size, inBuffer: $networkBufferSize")
                        networkBufferSize += inputStream!!.read(networkBuffer, networkBufferSize, size)
                    }
                    ConnectionType.UDP ->
                    {
                        if (udpConnection == null)
                        {
                            logger?.log(Level.FINE, "TransmissionAndroid.networkRead: null udpConnection.")
                            println("TransmissionAndroid.networkRead: null udpConnection.")
                            return null
                        }

                        // FIXME: Keep track of buffer size correctly
                        val datagramPacket = DatagramPacket(buffer, buffer.size, size)

                        // FIXME: Keep track of buffer size correctly
                        udpConnection!!.receive(datagramPacket)
                    }
                }
            }
            catch (readError: Exception)
            {
                logger?.log(Level.SEVERE, "TransmissionAndroid.networkRead: Connection inputStream encountered an error while trying to read a specific size: $readError")
                println("TransmissionAndroid.networkRead: Connection inputStream encountered an error while trying to read a specific size: $readError")
                return null
            }
        }

        val readBytes = networkBuffer.dropLast(buffer.size - networkBufferSize).toByteArray()

        println("TransmissionAndroid.networkRead: returning ${readBytes.size} bytes.")
        return readBytes
    }

    @Synchronized
    override fun write(string: String): Boolean
    {
        val data = string.toByteArray()
        return networkWrite(data)
    }

    @Synchronized
    override fun write(data: ByteArray): Boolean
    {
        return networkWrite(data)
    }

    @Synchronized
    override fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean
    {
        println("TransmissionConnection.writeWithLengthPrefix() called")
        val messageSize = data.size
        val messageSizeBytes: ByteBuffer

        when (prefixSizeInBits) {
            8 -> {
                println("TransmissionConnection.writeWithLengthPrefix: prefixSizeInBits - 8")
                messageSizeBytes = ByteBuffer.allocate(1)
                messageSizeBytes.put(messageSize.toByte())
            }
            16 -> {
                println("TransmissionConnection.writeWithLengthPrefix: prefixSizeInBits - 16")
                messageSizeBytes = ByteBuffer.allocate(2)
                messageSizeBytes.putShort(messageSize.toShort())
            }
            32 -> {
                println("TransmissionConnection.writeWithLengthPrefix: prefixSizeInBits - 32")
                messageSizeBytes = ByteBuffer.allocate(4)
                messageSizeBytes.putInt(messageSize)
            }
            64 -> {
                println("TransmissionConnection.writeWithLengthPrefix: prefixSizeInBits - 64")
                messageSizeBytes = ByteBuffer.allocate(8)
                messageSizeBytes.putLong(messageSize.toLong())
            }
            else ->
            {
                print("TransmissionConnection.writeWithLengthPrefix: Unable to complete a write request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                logger?.log(Level.SEVERE, "TransmissionConnection.writeWithLengthPrefix: Unable to complete a write request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                return false
            }
        }

        val atomicData = messageSizeBytes.array() + data

        return networkWrite(atomicData)
    }

    private fun networkWrite(data: ByteArray): Boolean
    {
        println("TransmissionConnection.networkWrite() called")
        try
        {
            when (connectionType)
            {
                ConnectionType.TCP ->
                {
                    println("TransmissionConnection.networkWrite: tcpConnection")

                    if (outputStream == null)
                    {
                        println("TransmissionConnection.networkWrite: error - tcpConnection has a null outputStream")
                        logger?.log(Level.FINE, "Called networkWrite() when out tcpConnection has a null outputStream")
                        return false
                    }

                    outputStream!!.write(data)
                    return true
                }
                ConnectionType.UDP ->
                {
                    println("TransmissionConnection.networkWrite: udpConnection")

                    if (udpConnection == null)
                    {
                        println("TransmissionConnection.networkWrite: error - null udpConnection")
                        logger?.log(Level.FINE, "Tried to call networkWrite() on a null udpConnection.")
                        return false
                    }

                    val datagramPacket = DatagramPacket(data, data.size)
                    udpConnection!!.send(datagramPacket)
                    return true
                }
            }
        }
        catch (writeError: Exception)
        {
            println("TransmissionConnection.networkWrite: Error while attempting to write data to the network: $writeError")
            logger?.log(Level.SEVERE, "Error while attempting to write data to the network: $writeError")
            return false
        }
    }

    fun close()
    {
        if (udpConnection != null)
        {
            udpConnection!!.close()
        }
        else if (tcpConnection != null)
        {
            tcpConnection!!.close()
        }
    }
}