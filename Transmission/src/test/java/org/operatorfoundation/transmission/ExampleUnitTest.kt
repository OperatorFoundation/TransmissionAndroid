package org.operatorfoundation.transmission

import kotlinx.coroutines.*
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest
{

    @Test
    fun testWriteWithLengthPrefix()
    {
        val transmissionConnection = TransmissionConnection("", 1234, ConnectionType.TCP, null)
        val newPacketString = "0b"
        val pingPacket = hexStringToByteArray(newPacketString)

        val messageSent = transmissionConnection.writeWithLengthPrefix(pingPacket, 16)

        println("TransmissionTest: sent a message to the transmission connection. Success - $messageSent")

        if (messageSent == false)
        {
            println("TransmissionTest: failed to write a message.")
            return
        }

        assertTrue(messageSent)

        val maybeBytes = transmissionConnection.readWithLengthPrefix(16)
        assertNotNull(maybeBytes)
    }

    var writeCoroutineScope = CoroutineScope(Job() + Dispatchers.Default)

    @Test
    fun testWriteWithLengthPrefixCoroutine()
    {
        val transmissionConnection = TransmissionConnection("", 1234, ConnectionType.TCP, null)
        val newPacketString = "450000258ad100004011ef41c0a801e79fcb9e5adf5104d200115d4268656c6c6f6f6f6f0a"
        val pingPacket = hexStringToByteArray(newPacketString)

        println("TransmissionTest: Calling writeMessage")
        runBlocking { writeMessage(transmissionConnection, pingPacket) }
    }

    suspend fun writeMessage(transmissionConnection: TransmissionConnection, data: ByteArray)
    {
        println("TransmissionTest: preparing to send a message to the transmission connection.")

        val messageSent = withContext(writeCoroutineScope.coroutineContext) {
            transmissionConnection.writeWithLengthPrefix(
                data,
                16
            )
        }

        println("TransmissionTest: sent a message to the transmission connection. Success - $messageSent")

        if (messageSent == false)
        {
            println("TransmissionTest: failed to write a message.")
        }

        assertTrue(messageSent)
    }

    fun hexStringToByteArray(hexString: String): ByteArray
    {
        check(hexString.length % 2 == 0) { "Must be divisible by 2" }

        return hexString.chunked(2) // create character pairs
            .map { it.toInt(16).toByte() } // Convert pairs to integer
            .toByteArray() // Convert ints to a ByteArray
    }
}