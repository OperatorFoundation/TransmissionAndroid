package org.operatorfoundation.transmission

import java.lang.Exception
import java.util.UUID.randomUUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min

abstract class BaseConnection(var logger: Logger?) : Connection
{
    private val readLock = Object()
    private val writeLock = Object()

    val id: String = randomUUID().toString()
    var connectionType: ConnectionType = ConnectionType.TCP
    private var buffer = ByteArray(0)

    var connectionClosed = true

    // Reads exactly size bytes
    override fun read(size: Int): ByteArray?
    {
        when(connectionType)
        {
            ConnectionType.UDP ->
            {
                val maybeData = readMaxSize(size)

                if (maybeData != null && maybeData.size != size)
                {
                    logger?.log(Level.WARNING, "TransmissionConnection: Received a read size (${maybeData.size}) different from the requested a read size ($size).")
                    return null
                }

                return maybeData
            }
            ConnectionType.TCP ->
            {
                synchronized(readLock) {
                    if (size < 1)
                    {
                        logger?.log(Level.WARNING, "BaseConnection: Requested a read size less than 1.")
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
                        while (size > buffer.size)
                        {
                            val maybeData = networkRead(size)

                            if (maybeData != null)
                            {
                                buffer += maybeData
                            }
                            else
                            {
                                logger?.log(Level.WARNING, "BaseConnection: Failed to read data from the network.")
                                close()
                                return null
                            }
                        }

                        val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
                        val remainingBytes = buffer.drop(size).toByteArray()

                        buffer = remainingBytes
                        return readBytes
                    }
                }
            }
        }
    }

    // Reads exactly size bytes
    override fun unsafeRead(size: Int): ByteArray?
    {
        if (size < 1)
        {
            logger?.log(Level.WARNING, "BaseConnection: Requested a read size less than 1.")
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
                        "BaseConnection: Requested a read for more data than what was available in the buffer."
                    )
                    null
                }
            } else {
                logger?.log(Level.WARNING, "BaseConnection: Failed to read data from the network.")
                return null
            }
        }
    }

    // Reads up to maxSize bytes
    override fun readMaxSize(maxSize: Int): ByteArray?
    {
        synchronized(readLock)
        {
            try
            {
                if (maxSize < 1)
                {
                    logger?.log(Level.WARNING, "BaseConnection: Requested a max read size less than 1.")
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
                            // FIXME: UDP Network Read
//                            val datagramPacket = DatagramPacket(maybeData, maxSize)
//                            udpConnection!!.receive(datagramPacket)
//                            bytesReadCount = datagramPacket.length
//
//                            if (bytesReadCount > maxSize)
//                            {
//                                logger?.log(Level.SEVERE, "TransmissionConnection: Tried to read more bytes than max requested size of $maxSize.")
//                                return null
//                            }
//
//                            // Do not continue on to buffer management with UDP connections
//                            return datagramPacket.data.sliceArray(0 until bytesReadCount)
                            logger?.log(Level.SEVERE, "UDP Read not implemented for TransmissionAndroid")
                            return null
                        }
                        ConnectionType.TCP ->
                        {
                            val bytesRead = networkRead(maxSize)

                            if (bytesRead == null)
                            {
                                bytesReadCount = 0
                            }
                            else
                            {
                                maybeData = bytesRead
                                bytesReadCount = bytesRead.size
                            }
                        }
                    }

                    if (bytesReadCount <= 0)
                    {
                        logger?.log(Level.WARNING, "TransmissionConnection: tried to read data from the network and got nothing.")
                        close()
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
                logger?.log(Level.SEVERE, "BaseConnection: Connection inputStream encountered an error while trying to read: $readError")
                close()
                return null
            }
        }
    }

    override fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?
    {
        when(connectionType) {
            ConnectionType.UDP ->
            {
                logger?.log(Level.SEVERE, "BaseConnection: This function is not supported for UDP connections")
                return null
            }
            ConnectionType.TCP ->
            {
                synchronized(readLock) {
                    return Transmission.readWithLengthPrefix(this, prefixSizeInBits, this.logger)
                }
            }
        }
    }

    abstract fun networkRead(size: Int): ByteArray?

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

    abstract fun networkWrite(data: ByteArray): Boolean

    abstract fun networkClose()

    override fun close()
    {
        connectionClosed = true
        networkClose()
    }

    fun ByteArray.toHexString() : String {
        return this.joinToString("") { it.toString(16) }
    }
}