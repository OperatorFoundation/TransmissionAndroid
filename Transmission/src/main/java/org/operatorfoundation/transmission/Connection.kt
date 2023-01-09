package org.operatorfoundation.transmission

interface Connection
{
    fun read(size: Int): ByteArray?
    fun unsafeRead(size: Int): ByteArray?
    fun readMaxSize(maxSize: Int): ByteArray?
    fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray?

    fun write(string: String): Boolean
    fun write(data: ByteArray): Boolean
    fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean

    fun close()
}

enum class ConnectionType
{
    TCP,
    UDP
}