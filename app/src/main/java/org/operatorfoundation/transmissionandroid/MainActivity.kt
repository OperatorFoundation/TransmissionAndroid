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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import timber.log.Timber
import java.io.Serial
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
    private fun SerialDemoUI()
    {
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

        // Handle connection state changes
        LaunchedEffect(connectionState)
        {
            when (connectionState)
            {
                is SerialConnectionFactory.ConnectionState.Connected ->
                {
                    if (currentConnection == null)
                    {
                        currentConnection = (connectionState as SerialConnectionFactory.ConnectionState.Connected).connection
                        Timber.i("Successfully connected!")
                        startReadingData()
                    }
                }

                is SerialConnectionFactory.ConnectionState.Error ->
                {
                    Timber.e("Connection failed: ${(connectionState as SerialConnectionFactory.ConnectionState.Error).message}")
                    Toast.makeText(context,
                        "Connection failed: ${(connectionState as SerialConnectionFactory.ConnectionState.Error).message}",
                        Toast.LENGTH_LONG).show()
                }

                is SerialConnectionFactory.ConnectionState.RequestingPermission ->
                {
                    Timber.d("Requesting USB permission...")
                }

                is SerialConnectionFactory.ConnectionState.Connecting ->
                {
                    Timber.d("Establishing connection...")
                }

                is SerialConnectionFactory.ConnectionState.Disconnected ->
                {
                    currentConnection = null
                    Timber.d("Connection state: Disconnected")
                }
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

                        // Demo command sequence
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { runCommandSequenceTest() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("Run Command Sequence Test")
                            }
                        }

//                        Row(
//                            modifier = Modifier.fillMaxWidth(),
//                            horizontalArrangement = Arrangement.spacedBy(8.dp)
//                        )
//                        {
//                            Button(
//                                onClick = { sendCommand(CMD_LED_ON) },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("LED ON")
//                            }
//
//                            Button(
//                                onClick = { sendCommand(CMD_LED_OFF) },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("LED OFF")
//                            }
//                        }

//                        Row(
//                            modifier = Modifier.fillMaxWidth(),
//                            horizontalArrangement = Arrangement.spacedBy(8.dp)
//                        )
//                        {
//                            Button(
//                                onClick = { sendCommand(CMD_STATUS) },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("Get Status")
//                            }
//
//                            Button(
//                                onClick = { sendCommand(CMD_PING) },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                Text("Ping")
//                            }
//                        }
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
        Timber.d("connectToDevice() called for ${driver.device.deviceName}")
        Timber.i("Connecting to ${driver.device.deviceName}...")

        connectionFactory.createConnection(driver.device)
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
            try {
                val connection = currentConnection ?: return@launch
                Timber.d("Starting non-blocking read loop...")

                withContext(Dispatchers.IO) {
                    val buffer = StringBuilder()
                    var consecutiveEmptyReads = 0

                    while (currentConnection != null)
                    {
                        try {
                            // Use the new non-blocking read
                            val data = connection.readAvailable(64)

                            when {
                                data != null && data.isNotEmpty() -> {
                                    consecutiveEmptyReads = 0

                                    // Process each byte
                                    for (byte in data) {
                                        val char = byte.toInt().toChar()

                                        when {
                                            char == '\n' || char == '\r' -> {
                                                if (buffer.isNotEmpty()) {
                                                    val message = buffer.toString().trim()
                                                    withContext(Dispatchers.Main) {
                                                        Timber.d("← Received: $message")
                                                    }
                                                    buffer.clear()
                                                }
                                            }
                                            char.isISOControl().not() && char != '\u0000' -> {
                                                buffer.append(char)
                                            }
                                        }
                                    }
                                }

                                data != null && data.isEmpty() -> {
                                    // Connection might be closed
                                    Timber.w("Connection appears closed")
                                    break
                                }

                                data == null -> {
                                    // No data available
                                    consecutiveEmptyReads++
                                    kotlinx.coroutines.delay(if (consecutiveEmptyReads > 100) 50 else 10)
                                }
                            }

                        } catch (e: Exception) {
                            Timber.e(e, "Read loop error")
                            kotlinx.coroutines.delay(100)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Fatal error in read loop")
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

    // MARK: Demo functions

    /**
     * Test function that sends a predetermined sequence of commands to the microcontroller.
     * Each command waits for a response before proceeding to the next one.
     */
    private fun runCommandSequenceTest()
    {
        lifecycleScope.launch {
            try
            {
                val connection = currentConnection
                if (connection == null)
                {
                    Timber.w("No active connection for command sequence test")
                    return@launch
                }

                Timber.i("=== Starting Command Sequence Test ===")

                // Predefined command sequence
                val commandSequence = listOf(
                    "2",
                    "1",
                    "11NIFK",
                    "3",
                    "aa00aa",
                    "2",
                    "0",
                    "4",
                    "14095600",
                    "5",
                    "q",
                    "q",
                    "3"
                )

                var sequenceSuccess = true

                // Send each command and wait for response
                for ((index, command) in commandSequence.withIndex())
                {
                    try
                    {
                        Timber.i("Sequence [${index + 1}/${commandSequence.size}]: Sending '$command'")

                        // Send the command
                        val sendSuccess = withContext(Dispatchers.IO)
                        {
                            connection.write(command + "\r\n")
                        }

                        if (!sendSuccess)
                        {
                            Timber.e("Failed to send command: $command")
                            sequenceSuccess = false
                            break
                        }

                        Timber.d("→ Sent: $command")

                        // Wait for response with timeout
                        val response = waitForAnyResponse(connection, timeoutMs = 2000)

                        if (response != null)
                        {
                            Timber.d("← Response: $response")
                        }
                        else
                        {
                            Timber.w("No response received for command: $command")
                            // Continue anyway - some commands might not respond
                        }

                        // Brief pause between commands
                        delay(500) // 500ms delay between commands

                    }
                    catch (e: Exception)
                    {
                        Timber.e(e, "Error during command sequence at step ${index + 1}: $command")
                        sequenceSuccess = false
                        break
                    }
                }

                // Send final 'q' command after all responses received
                if (sequenceSuccess)
                {
                    Timber.i("Sequence complete, sending final '1' (transmit) command...")

                    try
                    {
                        val finalSendSuccess = withContext(Dispatchers.IO) {
                            connection.write("1")
                        }

                        if (finalSendSuccess)
                        {
                            Timber.d("→ Transmit command sent: 1")

                            val finalResponse = waitForAnyResponse(connection, timeoutMs = 4 * 60 * 1000) // 4 minutes in milliseconds

                            if (finalResponse != null)
                            {
                                Timber.d("← Final response: $finalResponse")
                            }

                            Timber.i("=== Command Sequence Test COMPLETED ===")
                        }
                        else
                        {
                            Timber.e("Failed to send final 'q' command")
                        }

                        val quitSendSuccess = withContext(Dispatchers.IO) {
                            connection.write("q")
                        }

                    }
                    catch (e: Exception)
                    {
                        Timber.e(e, "Error sending final command")
                    }
                }
                else
                {
                    Timber.e("=== Command Sequence Test FAILED ===")
                }

            }
            catch (e: Exception)
            {
                Timber.e(e, "Fatal error during command sequence test")
            }
        }
    }

    /**
     * Waits for a response from the serial connection with a specified timeout.
     *
     * @param connection The serial connection to read from
     * @param timeoutMs Timeout in milliseconds
     * @return The received response string, or null if timeout/error
     */
    private suspend fun waitForResponse(connection: SerialConnection, timeoutMs: Long): String?
    {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {

                val buffer = StringBuilder()
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeoutMs)
                {
                    try
                    {
                        val data = connection.readAvailable()

                        if (data != null && data.isNotEmpty())
                        {
                            for (byte in data)
                            {
                                val char = byte.toInt().toChar()

                                when
                                {
                                    char == '\n' || char == '\r' ->
                                    {
                                        if (buffer.isNotEmpty())
                                        {
                                            return@withTimeoutOrNull buffer.toString().trim()
                                        }
                                    }

                                    char.isISOControl().not() && char != '\u0000' ->
                                    {
                                        buffer.append(char)
                                    }
                                }
                            }

                            // If we got some data but no line ending yet, continue reading
                            if (buffer.isNotEmpty())
                            {
                                delay(10) // Small delay before checking for more data
                            }
                        }
                        else
                        {
                            delay(50) // No data available, wait a bit
                        }
                    }
                    catch (e: Exception)
                    {
                        Timber.w(e.message)
                        delay(100)
                    }
                }

                // Timeout - return partial data if any
                if (buffer.isNotEmpty())
                {
                    buffer.toString().trim()
                }
                else
                {
                    null
                }
            }
        }
    }

    /**
     * Waits for any response (not necessarily line-terminated)
     */
    private suspend fun waitForAnyResponse(connection: SerialConnection, timeoutMs: Long): String?
    {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val buffer = StringBuilder()
                val startTime = System.currentTimeMillis()
                var lastDataTime = startTime

                while (System.currentTimeMillis() - startTime < timeoutMs)
                {
                    try
                    {
                        val data = connection.readAvailable()

                        if (data != null && data.isNotEmpty()) {
                            lastDataTime = System.currentTimeMillis()

                            for (byte in data) {
                                val char = byte.toInt().toChar()
                                if (char.isISOControl().not() && char != '\u0000') {
                                    buffer.append(char)
                                }
                            }
                        }
                        else
                        {
                            // If we have data and haven't received more for 200ms, consider it complete
                            if (buffer.isNotEmpty() &&
                                System.currentTimeMillis() - lastDataTime > 200)
                            {
                                return@withTimeoutOrNull buffer.toString().trim()
                            }

                            delay(50)
                        }

                    }
                    catch (e: Exception) {
                        Timber.w(e.message)
                        delay(1000)
                    }
                }

                // Return whatever we got
                if (buffer.isNotEmpty())
                {
                    buffer.toString().trim()
                }
                else
                {
                    null
                }
            }
        }
    }

}