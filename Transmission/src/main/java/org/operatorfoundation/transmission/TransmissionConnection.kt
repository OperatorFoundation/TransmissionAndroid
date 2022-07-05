package org.operatorfoundation.transmission

import java.lang.Exception
import java.net.*
import java.nio.ByteBuffer
import java.util.UUID.randomUUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

class TransmissionConnection(var logger: Logger?) : Connection
{
    private val readLock = Object()
    private val writeLock = Object()

    val id: String
    var connectionType: ConnectionType = ConnectionType.TCP
    private var buffer = ByteArray(0)

    var udpConnection: DatagramSocket? = null
    var tcpConnection: Socket? = null

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
                }
                catch (error: Exception)
                {
                    println("The socket failed to create a tcp connection with the provided host and port: $error")
                    logger?.log(Level.SEVERE, "The socket failed to create a tcp connection with the provided host and port: $error ")
                    throw error
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
                    println("The socket failed to create a udp connection with the provided host and port: $error")
                    logger?.log(Level.SEVERE, "The socket failed to create a udp connection with the provided host and port: $error ")
                    throw error
                }
            }
        }
    }

    // Use this when you have already created a socket and connect() has been called
    constructor(tcpConnection: Socket, logger: Logger?) : this(logger)
    {
        this.tcpConnection = tcpConnection
    }

    constructor(udpConnection: DatagramSocket, logger: Logger?) : this(logger)
    {
        this.connectionType = ConnectionType.UDP
        this.udpConnection = udpConnection
    }


    // Reads exactly size bytes
    override fun read(size: Int): ByteArray?
    {
        synchronized(readLock) {
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

                    return if (size <= buffer.size)
                    {
                        val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
                        val remainingBytes = buffer.drop(size).toByteArray()

                        buffer = remainingBytes
                        readBytes
                    }
                    else
                    {
                        logger?.log(Level.WARNING, "Requested a read for more data than what was available in the buffer.")
                        null
                    }
                }
                else
                {
                    logger?.log(Level.WARNING, "Failed to read data from the network.")
                    return null
                }
            }
        }
    }

    // Reads up to maxSize bytes
    override fun readMaxSize(maxSize: Int): ByteArray?
    {
        synchronized(readLock) {
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
                            if (tcpConnection == null)
                            {
                                logger?.log(Level.SEVERE, "Tried to read on a null tcp connection.")
                                return null
                            }

                            bytesReadCount = tcpConnection!!.inputStream!!.read(maybeData)
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
    }

    override fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?
    {
        synchronized(readLock) {
            println("TransmissionAndroid.readWithLengthPrefix(prefixSizeInBits: $prefixSizeInBits) called")
            val maybeLength: Int?
            val prefixSizeInBytes = prefixSizeInBits / 8

            when (prefixSizeInBits)
            {
                8 ->
                {
                    val maybeLengthData = networkRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        println("TransmissionAndroid.readWithLengthPrefix: Failed to read the 8 bit length prefix from the network.")
                        logger?.log( Level.WARNING, "TransmissionAndroid.readWithLengthPrefix: Failed to read the 8 bit length prefix from the network." )
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).get().toInt()
                }
                16 ->
                {
                    val maybeLengthData = networkRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        println("TransmissionAndroid.readWithLengthPrefix: Failed to read the 16 bit length prefix from the network.")
                        logger?.log(Level.WARNING, "TransmissionAndroid.readWithLengthPrefix: Failed to read the 16 bit length prefix from the network.")
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).short.toInt()
                }
                32 ->
                {
                    val maybeLengthData = networkRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        println("TransmissionAndroid.readWithLengthPrefix: Failed to read the 32 bit length prefix from the network.")
                        logger?.log(Level.WARNING, "TransmissionAndroid.readWithLengthPrefix: Failed to read the 32 bit length prefix from the network.")
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).int
                }
                64 ->
                {
                    val maybeLengthData = networkRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        println("TransmissionAndroid.readWithLengthPrefix: Failed to read the 64 bit length prefix from the network.")
                        logger?.log(Level.WARNING, "TransmissionAndroid.readWithLengthPrefix: Failed to read the 64 bit length prefix from the network.")
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).long.toInt()
                }
                else ->
                {
                    println("TransmissionAndroid.readWithLengthPrefix: Unable to complete a read request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                    logger?.log(Level.SEVERE, "TransmissionAndroid.readWithLengthPrefix: Unable to complete a read request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                    return null
                }
            }

            return networkRead(maybeLength)
        }
    }

    private fun networkRead(size: Int): ByteArray?
    {
        println("TransmissionAndroid.networkRead(size: $size) called")
        var networkBuffer = ByteArray(2048)
        var networkBufferSize = 0
        val bytesToTake = min(size, buffer.size)
        var result = buffer.dropLast(buffer.size - bytesToTake).toByteArray()
        buffer = buffer.drop(bytesToTake).toByteArray()

        val numberToRead = size - result.size

        while (networkBufferSize < numberToRead)
        {
            try
            {
                when (connectionType)
                {
                    ConnectionType.TCP ->
                    {
                        if (tcpConnection == null)
                        {
                            logger?.log(Level.FINE, "TransmissionAndroid.networkRead: networkRead(size: ) called on null tcp connection.")
                            println("TransmissionAndroid.networkRead: networkRead(size: ) called on null tcp connection.")
                            return null
                        }

                        println("TransmissionAndroid.networkRead: TCP - calling inputStream.read requested: $size, inBuffer: $networkBufferSize")
                        networkBufferSize += tcpConnection!!.inputStream.read(networkBuffer, networkBufferSize, size)
                        println("TransmissionAndroid.networkRead: TCP - returned from inputStream.read, inBuffer: $networkBufferSize")
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

        println("buffer.size - ${buffer.size}, networkBufferSize - $networkBufferSize, networkBuffer.size - ${networkBuffer.size}")

        val numberToAdd = min(numberToRead, networkBufferSize)
        val numberLeftOver = networkBufferSize - numberToAdd
        val bytesToAdd = networkBuffer.dropLast(networkBuffer.size - numberToAdd).toByteArray()
        result += bytesToAdd

        if (networkBufferSize - numberToAdd > 0)
        {
            buffer += networkBuffer.drop(numberToAdd).dropLast(networkBuffer.size - numberLeftOver).toByteArray()
        }

        return result
    }

    override fun write(string: String): Boolean
    {
        synchronized(writeLock)
        {
            val data = string.toByteArray()
            return networkWrite(data)
        }
    }

    override fun write(data: ByteArray): Boolean
    {
        synchronized(writeLock) {
            return networkWrite(data)
        }
    }

    override fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean
    {
        synchronized(writeLock) {
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
    }

    private fun networkWrite(data: ByteArray): Boolean
    {
        println("TransmissionConnection.networkWrite() called with ${data.size} bytes.")
        try
        {
            when (connectionType)
            {
                ConnectionType.TCP ->
                {
                    println("TransmissionConnection.networkWrite: this is a tcpConnection")

                    if (tcpConnection == null)
                    {
                        println("TransmissionConnection.networkWrite: error - tcpConnection is null")
                        logger?.log(Level.FINE, "Called networkWrite() on a null tcpConnection")
                        return false
                    }

                    tcpConnection!!.outputStream.write(data)

                    println("TransmissionAndroid.TransmissionConnection: Wrote to output stream: ")
                    println(data.toHexString())
                    tcpConnection!!.outputStream.flush()
                    return true
                }
                ConnectionType.UDP ->
                {
                    println("TransmissionConnection.networkWrite: this is a udpConnection")

                    if (udpConnection == null)
                    {
                        println("TransmissionConnection.networkWrite: error - null udpConnection")
                        logger?.log(Level.FINE, "Tried to call networkWrite() on a null udpConnection.")
                        return false
                    }

                    val datagramPacket = DatagramPacket(data, data.size)

                    println("TransmissionConnection.networkWrite: calling udpConnection!!.send() with ${datagramPacket.length} bytes.")
                    udpConnection!!.send(datagramPacket)
                    println("TransmissionConnection.networkWrite: returned from udpConnection!!.send().")
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

    fun ByteArray.toHexString() : String {
        return this.joinToString("") { it.toString(16) }
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