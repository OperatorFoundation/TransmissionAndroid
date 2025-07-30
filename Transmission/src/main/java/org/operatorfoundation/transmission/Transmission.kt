package org.operatorfoundation.transmission

import java.nio.ByteBuffer
import java.util.logging.Level
import java.util.logging.Logger

class Transmission
{
    companion object {
        fun readWithLengthPrefix(connection: Connection, prefixSizeInBits: Int, logger: Logger?): ByteArray? {
            val maybeLength: Int?
            val prefixSizeInBytes = prefixSizeInBits / 8

            when (prefixSizeInBits)
            {
                8 ->
                {
                    val maybeLengthData = connection.unsafeRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        logger?.log( Level.WARNING, "Transmission: Failed to read the 8 bit length prefix from the network." )
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).get().toInt()
                }
                16 ->
                {
                    val maybeLengthData = connection.unsafeRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        logger?.log(Level.WARNING, "Transmission: Failed to read the 16 bit length prefix from the network.")
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).short.toInt()
                }
                32 ->
                {
                    val maybeLengthData = connection.unsafeRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        logger?.log(Level.WARNING, "Transmission: Failed to read the 32 bit length prefix from the network.")
                        return null
                    }

                    if (maybeLengthData[0].toInt() != 0)
                    {
                        println("🐘Read a very large 32 bit length data: ${maybeLengthData.toHexString()}")
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).int
                }
                64 ->
                {
                    val maybeLengthData = connection.unsafeRead(prefixSizeInBytes)

                    if (maybeLengthData == null)
                    {
                        logger?.log(Level.WARNING, "Transmission: Failed to read the 64 bit length prefix from the network.")
                        return null
                    }

                    maybeLength = ByteBuffer.wrap(maybeLengthData).long.toInt()
                }
                else ->
                {
                    logger?.log(Level.SEVERE, "Transmission: Unable to complete a read request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                    return null
                }
            }

            return connection.unsafeRead(maybeLength)
        }

        fun writeWithLengthPrefix(connection: Connection, data: ByteArray, prefixSizeInBits: Int, logger: Logger?): Boolean
        {
                val messageSize = data.size
                val messageSizeBytes: ByteBuffer

                when (prefixSizeInBits) {
                    8 -> {
                        messageSizeBytes = ByteBuffer.allocate(1)
                        messageSizeBytes.put(messageSize.toByte())
                    }
                    16 -> {
                        messageSizeBytes = ByteBuffer.allocate(2)
                        messageSizeBytes.putShort(messageSize.toShort())
                    }
                    32 -> {
                        messageSizeBytes = ByteBuffer.allocate(4)
                        messageSizeBytes.putInt(messageSize)
                    }
                    64 -> {
                        messageSizeBytes = ByteBuffer.allocate(8)
                        messageSizeBytes.putLong(messageSize.toLong())
                    }
                    else ->
                    {
                        logger?.log(Level.SEVERE, "Transmission: Unable to complete a write request, the size in bits of the requested length prefix is invalid. Requested size in bits: $prefixSizeInBits")
                        return false
                    }
                }

                val atomicData = messageSizeBytes.array() + data

                return connection.write(atomicData)
        }

        fun ByteArray.toHexString() : String {
            return this.joinToString("") { it.toString(16) }
        }
    }
}