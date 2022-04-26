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
        // FIXME: Valid Server IP
        val transmissionConnection = TransmissionConnection("164.92.71.230", 1234, ConnectionType.TCP, null)
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

        println("Read ${maybeBytes?.size} bytes")
        assertNotNull(maybeBytes)
    }

    fun hexStringToByteArray(hexString: String): ByteArray
    {
        check(hexString.length % 2 == 0) { "Must be divisible by 2" }

        return hexString.chunked(2) // create character pairs
            .map { it.toInt(16).toByte() } // Convert pairs to integer
            .toByteArray() // Convert ints to a ByteArray
    }
}