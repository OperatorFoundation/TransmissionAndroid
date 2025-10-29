package org.operatorfoundation.transmission

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import timber.log.Timber
import java.io.IOException


class SerialConnection(private val port: UsbSerialPort, private val connection: UsbDeviceConnection): Connection
{
    companion object
    {
        const val timeout = 0

        fun new(context: Context, permissionIntent: PendingIntent): SerialConnection
        {
            // Find all available drivers from attached devices.
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val availableDrivers: List<UsbSerialDriver> =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isEmpty()) {
                throw Exception("no serial ports")
            }

            // Open a connection to the first available driver.
            val driver: UsbSerialDriver = availableDrivers[0]
            usbManager.requestPermission(driver.device, permissionIntent)
            val connection = usbManager.openDevice(driver.device) ?: throw Exception("could not open serial port")

            // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            val port0 = driver.ports[0] ?: throw Exception("serial port was null") // Most devices have just one port (port 0)
            println("Connecting to device on port: ${port0.portNumber}")
            println("Device: ${port0.device.deviceName}")
            println("Driver: $driver")
            return SerialConnection(port0, connection)
        }

        fun new(context: Context, vendorID: Int, productID: Int, driverClass: Class<UsbSerialDriver>, permissionIntent: PendingIntent): SerialConnection
        {
            // Find all available drivers from attached devices.
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val customProbeTable = ProbeTable()
            customProbeTable.addProduct(vendorID, productID, driverClass)
//            customProbeTable.addProduct(0x1b4f, 0x0024, CdcAcmSerialDriver::class.java)
            val customUSBSerialProber = UsbSerialProber(customProbeTable)

            val availableDrivers: List<UsbSerialDriver> =
                customUSBSerialProber.findAllDrivers(usbManager)
            if (availableDrivers.isEmpty()) {
                throw Exception("no serial ports")
            }

            // Open a connection to the first available driver.
            val driver: UsbSerialDriver = availableDrivers[0]
            usbManager.requestPermission(driver.device, permissionIntent)
            val connection = usbManager.openDevice(driver.device) ?: throw Exception("could not open serial port")

            // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            val port0 = driver.ports[0] ?: throw Exception("serial port was null") // Most devices have just one port (port 0)
            return SerialConnection(port0, connection)
        }

        fun new(context: Context, manager: UsbManager? = null, driver: UsbSerialDriver, permissionIntent: PendingIntent): SerialConnection
        {
            val usbManager: UsbManager = manager ?: context.getSystemService(Context.USB_SERVICE) as UsbManager
            usbManager.requestPermission(driver.device, permissionIntent)

            val connection = usbManager.openDevice(driver.device) ?: throw Exception("could not open serial port")

            // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            val port0 = driver.ports[0] ?: throw Exception("serial port was null") // Most devices have just one port (port 0)

            return SerialConnection(port0, connection)
        }
    }

    // Buffer for accumulating partial lines
    private val lineBuffer = StringBuilder()

    init
    {
        this.port.open(this.connection)
        this.port.setParameters(
            115200,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        // Set DTR and RTS control lines - many Arduino sketches need this
        try
        {
            this.port.dtr = true
            this.port.rts = true
            Timber.d("DTR and RTS set to true")
        }
        catch (e: Exception)
        {
            Timber.w("Failed to set DTR/RTS: ${e.message}")
        }
    }

    /**
     * Reads data until a line terminator is found.
     * Supports \n, \r, or \r\n line endings.
     * Returns content without line ending characters.
     *
     * @param timeoutMs Maximum time to wait for a complete line.
     * @param maxLength Maximum line length (prevents unbounded buffer growth).
     * @return Complete line without line ending characters, partial line on timeout, or null on error.
     */
    @Synchronized
    fun readLine(timeoutMs: Long = 1000, maxLength: Int = 1024): String?
    {
        val startTime = System.currentTimeMillis()

        Timber.d("readLine() called with timeout=$timeoutMs")

        try
        {
            while (System.currentTimeMillis() - startTime < timeoutMs)
            {
                // Check if we have a complete line in the buffer
                val lineEnd = findLineEnding(lineBuffer)

                if (lineEnd >= 0)
                {
                    val line = lineBuffer.substring(0, lineEnd)
                    Timber.d("readLine() found complete line: '$line'")

                    // Remove this line and its terminator from the buffer
                    var removeUntil = lineEnd + 1

                    // Handle \r\n cases (skip both)
                    if (lineEnd < lineBuffer.length - 1 &&
                        lineBuffer[lineEnd] == '\r' &&
                        lineBuffer[lineEnd + 1] == '\n')
                    {
                        removeUntil = lineEnd + 2
                    }

                    lineBuffer.delete(0, removeUntil)

                    return line
                }

                // Check buffer size limit
                if (lineBuffer.length >= maxLength)
                {
                    Timber.w("readLine: Buffer exceeded max length ($maxLength), returning partial data.")
                    val partial = lineBuffer.toString()
                    lineBuffer.clear()
                    return partial
                }

                // Try to read more data
                val remainingTime = timeoutMs - (System.currentTimeMillis() - startTime)
                if (remainingTime <= 0) break

                val readTimeout = minOf(remainingTime.toInt(), 100)
                val data = readAvailable(maxLength - lineBuffer.length, readTimeout)

                if (data != null && data.isNotEmpty())
                {
                    val decoded = data.decodeToString()
                    Timber.d("readLine() got data: '$decoded' (${data.size} bytes)")
                    lineBuffer.append(decoded)
                }
            }

            // Timeout occurred
            if (lineBuffer.isNotEmpty())
            {
                val partial = lineBuffer.toString()
                lineBuffer.clear()
                return partial
            }

            return null
        }
        catch (error: Exception)
        {
            Timber.w("readLine error: ${error.message}")
            return null
        }
    }

    @Synchronized
    override fun read(size: Int): ByteArray {
        return this.unsafeRead(size)
    }

    override fun unsafeRead(size: Int): ByteArray {
        val bytes = ByteArray(size)
        val result = this.port.read(bytes, timeout)

        if (result != size) {
            throw Exception("SerialConnection: read failed: $size bytes requested, only $result bytes read")
        }

        return bytes
    }

    @Synchronized
    override fun readMaxSize(maxSize: Int): ByteArray {
        var bytes = ByteArray(maxSize)
        val result = this.port.read(bytes, timeout)

        if (result != maxSize) {
            bytes = bytes.slice(0 until result).toByteArray()
        }

        return bytes
    }

    @Synchronized
    override fun readWithLengthPrefix(prefixSizeInBits: Int): ByteArray? {
        return Transmission.readWithLengthPrefix(this, prefixSizeInBits, null)
    }

    override fun write(string: String): Boolean {
        val bytes = string.toByteArray()
        return this.write(bytes)
    }

    @Synchronized
    override fun write(data: ByteArray): Boolean {
        this.port.write(data, timeout)

        return true
    }

    @Synchronized
    override fun writeWithLengthPrefix(data: ByteArray, prefixSizeInBits: Int): Boolean {
        return Transmission.writeWithLengthPrefix(this, data, prefixSizeInBits,null)
    }


    @Synchronized
    override fun close()
    {
        try
        {
            if (!this.port.isOpen)
            {
                return // Already closed, nothing to do
            }
            this.port.close()
        }
        catch (e: IOException)
        {
            // Log but don't crash - connection might already be closed
            Timber.w("Port was already closed: ${e.message}")
        }
    }

    /**
     * Reads available data without blocking, up to maxSize bytes
     * Returns null if no data available, empty array if connection closed
     *
     * @param maxSize Maximum number of bytes to read
     * @param timeoutMs Timeout in milliseconds for the read operation
     */
    fun readAvailable(maxSize: Int = 4096, timeoutMs: Int = 100): ByteArray?
    {
        return try
        {
            val buffer = ByteArray(maxSize)
            val bytesRead = this.port.read(buffer, timeoutMs)

            when
            {
                bytesRead > 0 ->
                {
                    Timber.v("readAvailable called with maxSize=$maxSize, timeoutMs=$timeoutMs")
                    val readResult = buffer.sliceArray(0 until bytesRead)

                    Timber.d("Read ${bytesRead} bytes: ${readResult.decodeToString()}")
                    
                    readResult
                }

                bytesRead == 0 -> null // No data available

                else -> // Connection might be closed
                {
                    Timber.d("Received an unexpected response; The connection may be closed.")
                    ByteArray(0)
                }
            }
        }
        catch (e: Exception)
        {
            // Log but don't crash
            Timber.w( "Read error: ${e.message}")
            null
        }
    }

    /**
     * Non-blocking read that returns immediately with whatever data is available
     */
    fun readNonBlocking(maxSize: Int = 64): ByteArray?
    {
        return readAvailable(maxSize, 0) // 0ms timeout = immediate return
    }

    /**
     * Finds the index of the first line-ending character (\r or \n) in the string builder.
     * Returns -1 if no line ending is found.
     */
    private fun findLineEnding(buffer: StringBuilder): Int
    {
        for (i in 0 until buffer.length)
        {
            if (buffer[i] == '\r' || buffer[i] == '\n') return i
        }

        return  -1
    }

    /**
     * Clears the internal line buffer in a thread-safe way.
     * Safe to call even if a read operation is in progress.
     */
    fun clearLineBuffer()
    {
        lineBuffer.clear()
    }
}