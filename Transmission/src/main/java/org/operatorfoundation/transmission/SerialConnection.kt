package org.operatorfoundation.transmission

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort

class SerialConnection(private val port: UsbSerialPort, private val connection: UsbDeviceConnection): Connection {
    companion object {
        const val timeout = 100

        fun new(context: Context): SerialConnection {
            // Find all available drivers from attached devices.
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager?
            val availableDrivers: List<UsbSerialDriver> =
                UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            if (availableDrivers.isEmpty()) {
                throw Exception("no serial ports")
            }

            // Open a connection to the first available driver.
            val driver: UsbSerialDriver = availableDrivers[0]
            val connection = manager!!.openDevice(driver.device) ?: throw Exception("could not open serial port")

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
    override fun read(size: Int): ByteArray? {
        return this.unsafeRead(size)
    }

    override fun unsafeRead(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        val result = this.port.read(bytes, timeout)

        if (result != size) {
            throw Exception("read failed")
        }

        return bytes
    }

    @Synchronized
    override fun readMaxSize(maxSize: Int): ByteArray? {
        var bytes = ByteArray(maxSize)
        val result = this.port.read(bytes, timeout)

        if (result != maxSize) {
            bytes = bytes.slice(0 until maxSize).toByteArray()
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
    override fun close() {
        return
    }
}