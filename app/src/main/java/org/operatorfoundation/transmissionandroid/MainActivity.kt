package org.operatorfoundation.transmissionandroid

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import timber.log.Timber
import java.sql.Driver
import java.text.SimpleDateFormat
import java.util.*


/**
 * Demo activity showcasing serial communication with microcontrollers.
 * Demonstrates length-prefixed protocol communication with FeatherM4 and ESP32 devices.
 */
class MainActivity : ComponentActivity()
{
    companion object
    {
        // Length prefix sizes supported by the transmission protocol
        private const val PREFIX_SIZE_8_BITS = 8
        private const val PREFIX_SIZE_16_BITS = 16
        private const val PREFIX_SIZE_32_BITS = 32

        // Demo message commands for microcontroller communication
        private const val CMD_LED_ON = "LED_ON"
        private const val CMD_LED_OFF = "LED_OFF"
        private const val CMD_STATUS = "STATUS"
        private const val CMD_PING = "PING"
    }

    /**
     * Custom Timber Tree that logs to both system log and UI display.
     */
    private inner class UILoggingTree : Timber.DebugTree()
    {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?)
        {
            // Always log to system (Logcat)
            super.log(priority, tag, message, t)

            // Also add to UI display for user visibility
            val isError = priority >= android.util.Log.WARN

            val displayMessage = if (t != null)
            {
                "$message: ${t.message}"
            }
            else
            {
                message
            }

            addLogMessage(displayMessage, isError)
        }
    }


    private lateinit var connectionFactory: SerialConnectionFactory
    private var currentConnection: SerialConnection? = null

    // UI State management
    private val _logMessages = MutableStateFlow<List<LogMessage>>(emptyList())
    private val logMessages: StateFlow<List<LogMessage>> = _logMessages.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<UsbSerialDriver>>(emptyList())
    private val availableDevices: StateFlow<List<UsbSerialDriver>> = _availableDevices.asStateFlow()

    data class LogMessage(
        val timestamp: String,
        val message: String,
        val isError: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        // Initialize Timber with custom tree for dual logging
        if (Timber.treeCount == 0)
        {
            Timber.plant(UILoggingTree())
        }

        connectionFactory = SerialConnectionFactory(this)

        setContent {
            SerialDemoUI()
        }

        // Scan for available devices on startup
        scanForDevices()

        Timber.i("Serial Demo initialized")
        Timber.i("Scanning for USB serial devices...")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SerialDemoUI() {
        val context = LocalContext.current
        val connectionState by connectionFactory.connectionState.collectAsState()
        val devices by availableDevices.collectAsState()
        val logs by logMessages.collectAsState()
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new log messages arrive
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        is SerialConnectionFactory.ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                        is SerialConnectionFactory.ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = getConnectionStatusText(connectionState),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Device Selection
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Available Devices (${devices.size})",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Button(
                            onClick = { scanForDevices() }
                        ) {
                            Text("Refresh")
                        }
                    }

                    if (devices.isEmpty()) {
                        Text(
                            text = "No USB serial devices found. Connect your FeatherM4 or ESP32 via USB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        devices.forEach { driver ->
                            DeviceCard(
                                driver = driver,
                                isConnected = connectionState is SerialConnectionFactory.ConnectionState.Connected,
                                onConnect = { connectToDevice(driver) },
                                onDisconnect = { disconnectDevice() }
                            )
                        }
                    }
                }
            }

            // Communication Controls
            if (connectionState is SerialConnectionFactory.ConnectionState.Connected) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Microcontroller Commands",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { sendCommand(CMD_LED_ON) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("LED ON")
                            }

                            Button(
                                onClick = { sendCommand(CMD_LED_OFF) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("LED OFF")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { sendCommand(CMD_STATUS) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Get Status")
                            }

                            Button(
                                onClick = { sendCommand(CMD_PING) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ping")
                            }
                        }
                    }
                }
            }

            // Log Display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Communication Log",
                        style = MaterialTheme.typography.titleMedium
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = "[${log.timestamp}] ${log.message}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = if (log.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            )
                        }
                    }

                    Button(
                        onClick = { clearLogs() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Clear Log")
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(
        driver: UsbSerialDriver,
        isConnected: Boolean,
        onConnect: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = driver.device.deviceName ?: "Unknown Device",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "VID: ${String.format("0x%04X", driver.device.vendorId)} " +
                                "PID: ${String.format("0x%04X", driver.device.productId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(onClick = onConnect) {
                        Text("Connect")
                    }
                }
            }
        }
    }

    /**
     * Scans for available USB serial devices and updates the UI state.
     */
    private fun scanForDevices()
    {
        lifecycleScope.launch {
            try
            {
                val devices = withContext(Dispatchers.IO) {
                    connectionFactory.findAvailableDevices()
                }
                _availableDevices.value = devices
                Timber.i("Found ${devices.size} USB serial device(s)")
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error scanning for devices")
            }
        }
    }

    /**
     * Initiates connection to the specified USB serial driver.
     */
    private fun connectToDevice(driver: UsbSerialDriver)
    {
        lifecycleScope.launch {
            Timber.i("Connecting to ${driver.device.deviceName}...")

            connectionFactory.createConnection(driver.device).collect { state ->
                when (state) {
                    is SerialConnectionFactory.ConnectionState.Connected -> {
                        currentConnection = state.connection
                        Timber.i("Successfully connected to ${driver.device.deviceName}")
                        startReadingData()
                    }

                    is SerialConnectionFactory.ConnectionState.Error -> {
                        Timber.e("Connection failed: ${state.message}")
                        Toast.makeText(this@MainActivity,
                            "Connection failed: ${state.message}",
                            Toast.LENGTH_LONG).show()
                    }

                    is SerialConnectionFactory.ConnectionState.RequestingPermission -> {
                        Timber.d("Requesting USB permission...")
                    }

                    is SerialConnectionFactory.ConnectionState.Connecting -> {
                        Timber.d("Establishing connection...")
                    }

                    else -> { Timber.d("Received unknown state ($state) while trying to connect to ${driver.device.deviceName}") }
                }
            }
        }
    }

    /**
     * Disconnects from the current device.
     */
    private fun disconnectDevice()
    {
        currentConnection?.close()
        currentConnection = null
        connectionFactory.disconnect()
        Timber.i("Disconnected from device")
    }

    /**
     * Sends a command to the connected microcontroller using length-prefixed protocol.
     */
    private fun sendCommand(command: String)
    {
        lifecycleScope.launch {
            try
            {
                val connection = currentConnection
                if (connection == null)
                {
                    Timber.w("No active connection")
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val success = connection.write(command)

                    withContext(Dispatchers.Main) {
                        if (success)
                        {
                            Timber.d("→ Sent: $command")
                        }
                        else
                        {
                            Timber.e("Failed to send command: $command")
                        }
                    }
                }
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error sending command '$command'")
            }
        }
    }

    /**
     * Starts a coroutine to continuously read data from the serial connection.
     */
    private fun startReadingData()
    {
        lifecycleScope.launch {
            try
            {
                val connection = currentConnection ?: return@launch

                withContext(Dispatchers.IO) {
                    while (currentConnection != null)
                    {
                        try
                        {
                            // Read response using length-prefixed protocol
                            val responseBytes = connection.readWithLengthPrefix(PREFIX_SIZE_16_BITS)

                            if (responseBytes != null)
                            {
                                val response = String(responseBytes)

                                withContext(Dispatchers.Main)
                                {
                                    Timber.d("← Received: $response")
                                }
                            }
                        }
                        catch (e: Exception)
                        {
                            withContext(Dispatchers.Main)
                            {
                                Timber.e(e, "Read error")
                            }

                            // Break the loop on read errors
                            break
                        }
                    }
                }
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error in read loop")
            }
        }
    }

    /**
     * Adds a timestamped message to the log.
     */
    private fun addLogMessage(message: String, isError: Boolean = false)
    {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = LogMessage(timestamp, message, isError)

        _logMessages.value = _logMessages.value + logMessage
    }

    /**
     * Clears all log messages.
     */
    private fun clearLogs()
    {
        _logMessages.value = emptyList()
    }

    /**
     * Converts connection state to human-readable text.
     */
    private fun getConnectionStatusText(state: SerialConnectionFactory.ConnectionState): String
    {
        return when (state)
        {
            is SerialConnectionFactory.ConnectionState.Disconnected -> "Disconnected"
            is SerialConnectionFactory.ConnectionState.RequestingPermission -> "Requesting USB permission..."
            is SerialConnectionFactory.ConnectionState.Connecting -> "Connecting..."
            is SerialConnectionFactory.ConnectionState.Connected -> "Connected"
            is SerialConnectionFactory.ConnectionState.Error -> "Error: ${state.message}"
        }
    }

    override fun onDestroy()
    {
        super.onDestroy()
        disconnectDevice()
    }

}