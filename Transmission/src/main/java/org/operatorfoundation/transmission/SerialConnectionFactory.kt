package org.operatorfoundation.transmission

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Factory for creating SerialConnection instances with proper permission handling.
 * Manages the connection lifecycle and provides state updates.
 */
class SerialConnectionFactory(context: Context)
{
    companion object
    {
        private const val DEFAULT_BAUD_RATE = 115200
        private const val DEFAULT_DATA_BITS = 8
        private const val DEFAULT_STOP_BITS = 0
        private const val DEFAULT_PARITY = 0
    }

    /**
     * Represents the current state of a serial connection.
     */
    sealed class ConnectionState
    {
        object Disconnected : ConnectionState()
        object RequestingPermission : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val connection: SerialConnection) : ConnectionState()
        data class Error(val message: String, val cause: Throwable? = null) : ConnectionState()
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionManager = USBPermissionManager(context)
    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Creates a SerialConnection for the specified USB device.
     * Handles permission requests automatically and updates connection state.
     *
     * This method initiates the connection process and returns immediately.
     * The caller should observe the connectionState flow to track progress.
     *
     * @param device The USB device to connect to
     * @param baudRate Serial communication baud rate (default: 115200)
     * @param dataBits Number of data bits (default: 8)
     * @param stopBits Number of stop bits (default: 1)
     * @param parity Parity setting (default: none)
     */
    fun createConnection(
        device: UsbDevice,
        baudRate: Int = DEFAULT_BAUD_RATE,
        dataBits: Int = DEFAULT_DATA_BITS,
        stopBits: Int = DEFAULT_STOP_BITS,
        parity: Int = DEFAULT_PARITY
    )
    {
        // Check current state
        val currentState = _connectionState.value
        if (currentState !is ConnectionState.Disconnected && currentState !is ConnectionState.Error)
        {
            Timber.w("Connection already in progress (state: $currentState), ignoring new request")
            return
        }

        connectionScope.launch {
            try
            {
                Timber.d("🔵 Requesting USB permission for ${device.deviceName}")
                _connectionState.value = ConnectionState.RequestingPermission

                // Switch to IO for permission request
                val permissionResult = withContext(Dispatchers.IO) {
                    permissionManager.requestPermissionFor(device).first()
                }

                Timber.d("🔵 Permission result: $permissionResult")

                when (permissionResult)
                {
                    is USBPermissionManager.PermissionResult.Granted -> {
                        Timber.d("🟢 Permission GRANTED, connecting...")
                        _connectionState.value = ConnectionState.Connecting

                        val connection = withContext(Dispatchers.IO) {
                            createSerialConnection(device, baudRate, dataBits, stopBits, parity)
                        }

                        Timber.d("🟢 Serial connection established")
                        _connectionState.value = ConnectionState.Connected(connection)
                    }

                    is USBPermissionManager.PermissionResult.Denied -> {
                        Timber.w("🔴 USB permission DENIED")
                        _connectionState.value = ConnectionState.Error("USB permission denied by user")
                    }

                    is USBPermissionManager.PermissionResult.Error -> {
                        Timber.e("🔴 Permission error: ${permissionResult.message}")
                        _connectionState.value = ConnectionState.Error(
                            "Permission request failed: ${permissionResult.message}"
                        )
                    }
                }
            }
            catch (e: Exception)
            {
                Timber.e(e, "🔴 Connection failed")
                _connectionState.value = ConnectionState.Error(
                    "Failed to create connection: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * Creates a SerialConnection for a device with a specific driver class.
     * Useful when the default USB serial prober doesn't detect your device.
     *
     * @param vendorID USB vendor ID
     * @param productID USB product ID
     * @param driverClass Specific USB serial driver class to use
     */
    fun createConnectionWithDriver(
        vendorID: Int,
        productID: Int,
        driverClass: Class<out UsbSerialDriver>,
    ): Flow<ConnectionState>
    {
        kotlinx.coroutines.GlobalScope.launch {
            try
            {
                _connectionState.value = ConnectionState.RequestingPermission

                // Find device with custom probe table
                val customProbeTable = ProbeTable()
                customProbeTable.addProduct(vendorID, productID, driverClass)
                val customProber = UsbSerialProber(customProbeTable)

                val availableDrivers = customProber.findAllDrivers(usbManager)
                if (availableDrivers.isEmpty())
                {
                    _connectionState.value = ConnectionState.Error(
                        "No USB serial device found with vendor ID: $vendorID, product ID: $productID"
                    )
                    return@launch
                }

                val driver = availableDrivers.first()
                Timber.d("🔌 Creating a connection with ${driver::class}")
                val device = driver.device

                val permissionResult = permissionManager.requestPermissionFor(device).first()
                when (permissionResult)
                {
                    is USBPermissionManager.PermissionResult.Granted -> {
                        _connectionState.value = ConnectionState.Connecting

                        val usbConnection = usbManager.openDevice(device)
                            ?: throw Exception("Failed to open USB device")

                        val port = driver.ports.firstOrNull()
                            ?: throw Exception("No serial ports available on device")

                        val connection = SerialConnection(port, usbConnection)
                        _connectionState.value = ConnectionState.Connected(connection)
                    }

                    is USBPermissionManager.PermissionResult.Denied -> {
                        _connectionState.value = ConnectionState.Error("USB permission denied by user")
                    }

                    is USBPermissionManager.PermissionResult.Error -> {
                        _connectionState.value = ConnectionState.Error(
                            "Permission request failed: ${permissionResult.message}"
                        )
                    }
                }
            }
            catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(
                    "Failed to create connection with driver: ${e.message}",
                    e
                )
            }
        }

        return connectionState
    }

    /**
     * Finds all available USB serial devices currently connected.
     *
     * @return List of available USB serial drivers
     */
    fun findAvailableDevices(): List<UsbSerialDriver>
    {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    /**
     * Disconnects the current connection and resets state.
     */
    fun disconnect()
    {
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Connected)
        {
            currentState.connection.close()
        }

        connectionScope.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
    * Creates the actual SerialConnection instance from a permitted USB device.
    *
    * @param device USB device with granted permission
    * @param baudRate Serial baud rate
    * @param dataBits Data bits setting
    * @param stopBits Stop bits setting
    * @param parity Parity setting
    * @return Configured SerialConnection
    */
    private fun createSerialConnection(
        device: UsbDevice,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: Int
    ): SerialConnection
    {
        // Find driver for device
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.find { it.device == device }
            ?: throw Exception("No USB serial driver found for device: ${device.deviceName}")

        Timber.d("🔌 Connecting to driver ${driver::class}")

        // Open device connection
        val usbConnection = usbManager.openDevice(device)
            ?: throw Exception("Failed to open USB device: ${device.deviceName}")

        // Get the first available port
        val port = driver.ports.firstOrNull()
            ?: throw Exception("No serial ports available on device: ${device.deviceName}")

        Timber.d("🔌 Connecting to port ${port::class}")

        // Create and configure the Serial connection
        val connection = SerialConnection(port, usbConnection)

        // Set custom parameters
        if (baudRate != DEFAULT_BAUD_RATE || dataBits != DEFAULT_DATA_BITS ||
            stopBits != DEFAULT_STOP_BITS || parity != DEFAULT_PARITY)
        {
            port.setParameters(baudRate, dataBits, stopBits, parity)
        }

        return connection
    }


}