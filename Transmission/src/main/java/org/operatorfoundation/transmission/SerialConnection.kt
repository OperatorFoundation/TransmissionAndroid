package org.operatorfoundation.transmission

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber


class SerialConnection(private val port: UsbSerialPort, private val connection: UsbDeviceConnection): Connection {
    companion object {
        const val timeout = 0

        fun new(context: Context, permissionIntent: PendingIntent): SerialConnection {
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

        fun new(context: Context, vendorID: Int, productID: Int, driverClass: Class<UsbSerialDriver>, permissionIntent: PendingIntent): SerialConnection {
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

    init {
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
        this.port.close()
    }
}