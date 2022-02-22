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

class TransmissionConnection(var connection: Socket, val logger: Logger?)
{
    private val TAG = "TransmissionConnection" // Use this when Logging e.g. Log.d(TAG, "init TransmissionConnection called")
    val id: String
    var buffer = ByteArray(1)
    var inputStream: InputStream
    var outputStream: OutputStream

    init
    {
        logger?.log(Level.FINE, "init TransmissionConnection called")
        id = randomUUID().toString()
        inputStream = connection.getInputStream()
        outputStream = connection.getOutputStream()
    }

    constructor(host:String, port: Int, type: ConnectionType = ConnectionType.tcp, logger: Logger?) : this(Socket(), logger)
    {
        when (type)
        {
            ConnectionType.tcp ->
            {
                try
                {
                    val socketAddress = InetSocketAddress(host, port)
                    connection.connect(socketAddress)
                }
                catch (error: Exception)
                {
                    logger?.log(Level.SEVERE, "The socket failed to connect with the provided host and port. ")
                    return
                }
            }

            // FIXME: UDP
            ConnectionType.udp -> logger?.log(Level.SEVERE, "UDP connections are not currently supported.")
        }
    }

    // Reads exactly size bytes
    @Synchronized fun read(size: Int): ByteArray?
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
            val maybeData = netwokRead(size)

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
    @Synchronized fun readMaxSize(maxSize: Int): ByteArray?
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
                val bytesReadCount = inputStream.read(maybeData)

                if (bytesReadCount <= 0)
                {
                    //Log.d(TAG, "tried to read data from the network and got nothing.")
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
            //Log.e(TAG, "Connection inputStream encountered an error while trying to read: " + readError.toString())
            return null
        }
    }

    @Synchronized fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?
    {
        val maybeLength: Int?

        when(prefixSizeInBits)
        {
            8 ->
            {
                val maybeLengthData = netwokRead(prefixSizeInBits/8)

                if (maybeLengthData == null)
                {
                    //Log.d(TAG, "Failed to read uint8 length prefix.")
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).get().toInt()
            }
            16 ->
            {
                val maybeLengthData = netwokRead(prefixSizeInBits/8)

                if (maybeLengthData == null)
                {
                    //Log.d(TAG, "Failed to read uint16 length prefix.")
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).getShort().toInt()
            }
            32 ->
            {
                val maybeLengthData = netwokRead(prefixSizeInBits/8)

                if (maybeLengthData == null)
                {
                    //Log.d(TAG, "Failed to read uint32 length prefix.")
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).getInt()
            }
            64 ->
            {
                val maybeLengthData = netwokRead(prefixSizeInBits/8)

                if (maybeLengthData == null)
                {
                    //Log.d(TAG, "Failed to read uint64 length prefix.")
                    return null
                }

                maybeLength = ByteBuffer.wrap(maybeLengthData).getLong().toInt()
            }
            else -> return null
        }

        val maybeReadData = netwokRead(maybeLength)

        return maybeReadData
    }

    private fun netwokRead(size: Int): ByteArray?
    {
        while (buffer.size < size)
        {
            try
            { inputStream.read(buffer, buffer.size, size) }
            catch (readError: Exception)
            {
                //Log.e(TAG, "Connection inputSream encountered an error while trying to read a specific size: " + readError.toString())
                return null
            }
        }

        val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
        val remainingBytes = buffer.drop(size).toByteArray()

        buffer = remainingBytes
        return readBytes
    }

    @Synchronized fun write(string: String): Boolean
    {
        val data = string.toByteArray()
        return networkWrite(data)
    }

    @Synchronized fun write(data: ByteArray): Boolean
    {
        return networkWrite(data)
    }

    @Synchronized fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean
    {
        val messageSize = data.size
        val messageSizeBytes: ByteBuffer

        when(prefixSizeInBits)
        {
            8 ->
            {
                messageSizeBytes = ByteBuffer.allocate(1)
                messageSizeBytes.put(messageSize.toByte())
            }
            16 ->
            {
                messageSizeBytes = ByteBuffer.allocate(2)
                messageSizeBytes.putShort(messageSize.toShort())
            }
            32 ->
            {
                messageSizeBytes = ByteBuffer.allocate(4)
                messageSizeBytes.putInt(messageSize)
            }
            64 ->
            {
                messageSizeBytes = ByteBuffer.allocate(8)
                messageSizeBytes.putLong(messageSize.toLong())
            }
            else -> return false
        }

        val atomicData = messageSizeBytes.array() + data
        val success = networkWrite(atomicData)

        return success
    }

    private fun networkWrite(data: ByteArray): Boolean
    {
        try
        {
            outputStream.write(data)
            return true
        }
        catch (writeError: Exception)
        {
            //Log.e(TAG, "Error while attempting to write data to the network: " + writeError.toString())
            return false
        }
    }
}

enum class ConnectionType
{
    tcp,
    udp
}