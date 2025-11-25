package org.operatorfoundation.transmission

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bidirectional in-memory pipe connecting two endpoints.
 * Data written to one end can be read from the other end.
 */
class Pipe {
    private val bufferAtoB = RingBuffer(4096)
    private val bufferBtoA = RingBuffer(4096)

    val endA = PipeEnd(bufferBtoA, bufferAtoB)
    val endB = PipeEnd(bufferAtoB, bufferBtoA)
}

/**
 * One end of a bidirectional pipe.
 */
class PipeEnd internal constructor(
    private val readBuffer: RingBuffer,
    private val writeBuffer: RingBuffer
) : Connection {

    fun tryReadOne(): Int {
        val byte = readBuffer.get()
        return byte?.toInt()?.and(0xFF) ?: -1
    }

    fun readOne(): Byte {
        return readBuffer.get() ?: (-1).toByte()
    }

    override fun read(size: Int): ByteArray? {
        val result = mutableListOf<Byte>()

        for (i in 0 until size) {
            val byte = readBuffer.get() ?: break
            result.add(byte)
        }

        return if (result.isEmpty()) null else result.toByteArray()
    }

    override fun unsafeRead(size: Int): ByteArray? {
        return read(size)
    }

    override fun readMaxSize(maxSize: Int): ByteArray? {
        return read(maxSize)
    }

    override fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray? {
        throw UnsupportedOperationException("Not implemented for Pipe")
    }

    override fun write(string: String): Boolean {
        return write(string.toByteArray())
    }

    override fun write(data: ByteArray): Boolean {
        for (byte in data) {
            if (!writeBuffer.put(byte)) {
                return false
            }
        }
        return true
    }

    override fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean {
        throw UnsupportedOperationException("Not implemented for Pipe")
    }

    override fun close() {
        // No-op for in-memory pipe
    }

    fun availableForReading(): Boolean {
        return readBuffer.count() > 0
    }

    fun available(): Int {
        return readBuffer.count()
    }

    fun writeSpace(): Int {
        return readBuffer.free()
    }
}

/**
 * Thread-safe ring buffer for byte storage.
 */
internal class RingBuffer(private val capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var head = 0  // Write position
    private var tail = 0  // Read position
    private var size = 0  // Current number of elements
    private val lock = ReentrantLock()

    fun put(value: Byte): Boolean = lock.withLock {
        if (size >= capacity) {
            return false  // Buffer full
        }

        buffer[head] = value
        head = (head + 1) % capacity
        size++
        return true
    }

    fun get(): Byte? = lock.withLock {
        if (size == 0) {
            return null  // Buffer empty
        }

        val value = buffer[tail]
        tail = (tail + 1) % capacity
        size--
        return value
    }

    fun count(): Int = lock.withLock {
        return size
    }

    fun free(): Int = lock.withLock {
        return capacity - size
    }
}