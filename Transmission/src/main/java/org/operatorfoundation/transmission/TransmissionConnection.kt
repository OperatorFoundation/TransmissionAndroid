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

    var connectionClosed = true

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
                    this.connectionClosed = false
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
                    this.connectionClosed = false
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
        this.connectionClosed = false
    }

    constructor(udpConnection: DatagramSocket, logger: Logger?) : this(logger)
    {
        this.connectionType = ConnectionType.UDP
        this.udpConnection = udpConnection
        this.connectionClosed = false
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

    // Reads exactly size bytes
    override fun unsafeRead(size: Int): ByteArray?
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
        else {
            val maybeData = networkRead(size)

            if (maybeData != null) {
                buffer += maybeData

                return if (size <= buffer.size) {
                    val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
                    val remainingBytes = buffer.drop(size).toByteArray()

                    buffer = remainingBytes
                    readBytes
                } else {
                    logger?.log(
                        Level.WARNING,
                        "Requested a read for more data than what was available in the buffer."
                    )
                    null
                }
            } else {
                logger?.log(Level.WARNING, "Failed to read data from the network.")
                return null
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
            return Transmission.readWithLengthPrefix(this, prefixSizeInBits, this.logger)
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
                            close()
                            return null
                        }

                        val readResult = tcpConnection!!.inputStream.read(networkBuffer, networkBufferSize, size)

                        if (readResult > 0)
                        {
                            networkBufferSize += readResult
                            println("TransmissionAndroid.networkRead: TCP - returned from inputStream.read, inBuffer: $networkBufferSize")
                        }
                        else
                        {
                            close()
                            return null
                        }
                    }
                    ConnectionType.UDP ->
                    {
                        if (udpConnection == null)
                        {
                            logger?.log(Level.FINE, "TransmissionAndroid.networkRead: null udpConnection.")
                            close()
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
                close()
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
        return Transmission.writeWithLengthPrefix(this, data, prefixSizeInBits, this.logger)
    }

    override fun close()
    {
        if (udpConnection != null)
        {
            udpConnection!!.close()
        }
        else if (tcpConnection != null)
        {
            tcpConnection!!.close()
        }

        connectionClosed = true
    }

    private fun networkWrite(data: ByteArray): Boolean
    {
        try
        {
            when (connectionType)
            {
                ConnectionType.TCP ->
                {
                    if (tcpConnection == null)
                    {
                        logger?.log(Level.FINE, "Called networkWrite() on a null tcpConnection")
                        close()
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
                    if (udpConnection == null)
                    {
                        logger?.log(Level.FINE, "Tried to call networkWrite() on a null udpConnection.")
                        close()
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
            logger?.log(Level.SEVERE, "Error while attempting to write data to the network: $writeError")
            close()
            return false
        }
    }

    fun ByteArray.toHexString() : String {
        return this.joinToString("") { it.toString(16) }
    }
}