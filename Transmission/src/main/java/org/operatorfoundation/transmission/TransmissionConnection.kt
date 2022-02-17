package org.operatorfoundation.transmission

class TransmissionConnection
{
    // Reads exactly size bytes
    fun read(size: Int): ByteArray?
    {
        return null
    }

    // reads up to maxSize bytes
    fun readMaxSize(maxSize: Int): ByteArray?
    {
        return null
    }

    fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?
    {
        return null
    }

    fun write(string: String): Boolean
    {
        return false
    }

    fun write(data: ByteArray): Boolean
    {
        return false
    }

    fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean
    {
        return false
    }
}