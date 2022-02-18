package org.operatorfoundation.transmission

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.net.*
import java.util.UUID.randomUUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock

class TransmissionConnection(var connection: Socket)
{
    private val TAG = "TransmissionConnection" // Use this when Logging e.g. Log.d(TAG, "init TransmissionConnection called")
    private val reentrantReadWriteLock = ReentrantReadWriteLock()
    private val readLock = reentrantReadWriteLock.readLock()
    private val writeLock = reentrantReadWriteLock.writeLock()
    //val states: BlockingQueue<Boolean> = BlockingQueue()
    val id: String
    var buffer = ByteArray(1)
    var inputStream: InputStream
    var outputStream: OutputStream


    init
    {
        Log.d(TAG, "init TransmissionConnection called")
        id = randomUUID().toString() // FIXME: Original Swift implementation uses the socket's file descriptor. Kotlin Sockets do not have this
        inputStream = connection.getInputStream()
        outputStream = connection.getOutputStream()
    }

    constructor(host:String, port: Int, type: ConnectionType = ConnectionType.tcp) : this(Socket())
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
                catch (error: Exception) {
                    Log.e(TAG, "The socket failed to connect with the provided host and port. ")
                    return
                }
            }

            // FIXME: UDP
            ConnectionType.udp -> Log.e(TAG, "UDP connections are not currently supported.")
        }
    }

    // Reads exactly size bytes
    fun read(size: Int): ByteArray?
    {
        if (size < 1)
        {
            Log.e(TAG, "Requested a read size less than 1.")
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
                    Log.e(TAG, "Requested a read for more data than what was available in the buffer.")
                    return null
                }
            }
            else
            {
                Log.e(TAG,"Failed to read data from the netowrk.")
                return null
            }
        }
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

    fun netwokRead(size: Int): ByteArray?
    {
        while (buffer.size < size)
        {
            readLock.lock()

            try
            { inputStream.read(buffer, buffer.size, size) }
            catch (readError: Exception)
            {
                Log.e(TAG, "Connection inputSream encountered an error while trying to read: " + readError.toString())
                return null
            }
            finally { readLock.unlock() }
        }

        readLock.lock()
        try
        {
            val readBytes = buffer.dropLast(buffer.size - size).toByteArray()
            val remainingBytes = buffer.drop(size).toByteArray()

            buffer = remainingBytes
            return readBytes
        }
        finally { readLock.unlock() }
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

enum class ConnectionType
{
    tcp,
    udp
}