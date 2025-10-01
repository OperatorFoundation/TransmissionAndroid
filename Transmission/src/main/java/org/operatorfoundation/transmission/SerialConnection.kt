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

    init
    {
        this.port.open(this.connection)
        this.port.setParameters(
            115200,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )
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
     */
    fun readAvailable(maxSize: Int = 4096): ByteArray?
    {
        return try
        {
            val buffer = ByteArray(maxSize)
            val bytesRead = this.port.read(buffer, 100) // 100ms timeout

            when
            {
                bytesRead > 0 ->
                {
                    Timber.v("readAvailable called with maxSize=$maxSize")
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
     * Reads data with a specific timeout in milliseconds
     * Returns null if timeout or error occurs
     */
    fun readWithTimeout(size: Int, timeoutMs: Int): ByteArray?
    {
        return try
        {
            val buffer = ByteArray(size)
            val bytesRead = this.port.read(buffer, timeoutMs)

            if (bytesRead == size)
            {
                buffer
            }
            else if (bytesRead > 0)
            {
                buffer.sliceArray(0 until bytesRead)
            }
            else
            {
                null
            }
        }
        catch (e: Exception)
        {
            Timber.w("Read with timeout error: ${e.message}")
            null
        }
    }

    /**
     * Non-blocking read that returns immediately with whatever data is available
     */
    fun readNonBlocking(maxSize: Int = 64): ByteArray?
    {
        return readWithTimeout(maxSize, 0) // 0ms timeout = immediate return
    }
}