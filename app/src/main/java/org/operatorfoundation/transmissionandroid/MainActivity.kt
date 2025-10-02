package org.operatorfoundation.transmissionandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.text.SimpleDateFormat
import java.util.*
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive


/**
 * Demo activity showcasing serial communication with microcontrollers.
 * Demonstrates length-prefixed protocol communication with FeatherM4 and ESP32 devices.
 */
class MainActivity : ComponentActivity()
{
    private var usbStateReceiver: BroadcastReceiver? = null

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

    // Reading mode state
    private val _readingMode = MutableStateFlow<ReadingMode>(ReadingMode.Stopped)
    private val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private var readingJob: Job? = null

    sealed class ReadingMode
    {
        object Stopped : ReadingMode()
        object Streaming : ReadingMode()
        object CommandSequence : ReadingMode()
    }


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

        // Initialize and register USB state receiver for auto-detection
        initializeUsbStateReceiver()
        val usbIntentFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbStateReceiver, usbIntentFilter)

        Timber.i("USB auto-detection enabled")

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
                    }
                    else
                    {
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
            if (connectionState is SerialConnectionFactory.ConnectionState.Connected)
            {
                val currentMode by readingMode.collectAsState()

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Communication Mode",
                            style = MaterialTheme.typography.titleMedium
                        )

                        // Mode Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Stream Mode Button
                            Button(
                                onClick = { startStreamMode() },
                                modifier = Modifier.weight(1f),
                                enabled = currentMode != ReadingMode.Streaming,
                                colors = if (currentMode == ReadingMode.Streaming)
                                {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                                else { ButtonDefaults.buttonColors() }
                            ) {
                                Text("Stream Mode")
                            }

                            // Command Mode Button
                            Button(
                                onClick = { runCommandSequenceTest() },
                                modifier = Modifier.weight(1f),
                                enabled = currentMode != ReadingMode.CommandSequence,
                                colors = if (currentMode == ReadingMode.CommandSequence)
                                {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                                else { ButtonDefaults.buttonColors() }
                            ) {
                                Text("Command Mode")
                            }
                        }

                        // Stop button
                        if (currentMode != ReadingMode.Stopped)
                        {
                            Button(
                                onClick = { stopReading() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Stop") }
                        }

                        // Status indicator
                        Text(
                            text = when (currentMode) {
                                ReadingMode.Stopped -> "No active mode"
                                ReadingMode.Streaming -> "Streaming: Displaying all incoming data"
                                ReadingMode.CommandSequence -> "Running command sequence..."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
        stopReading()
        currentConnection?.close()
        currentConnection = null
        connectionFactory.disconnect()
        Timber.i("Disconnected from device")
    }

    /**
     * Starts continuous streaming mode - reads and displays all incoming data.
     */
    private fun startStreamMode()
    {
        // Cancel any existing read job
        stopReading()

        _readingMode.value = ReadingMode.Streaming

        readingJob = lifecycleScope.launch {
            try
            {
                val connection = currentConnection

                if (connection == null)
                {
                    Timber.w("No active connection for stream mode")
                    _readingMode.value = ReadingMode.Stopped
                    return@launch
                }

                Timber.i("=== Starting Stream Mode ===")

                withContext(Dispatchers.IO) {
                    while (isActive && currentConnection != null)
                    {
                        try
                        {
                            val line = connection.readLine(timeoutMs = 500, maxLength = 1024)

                            if (line != null)
                            {
                                withContext(Dispatchers.Main){
                                    Timber.i("← Stream: $line")
                                }
                            }
                        }
                        catch (error: Exception)
                        {
                            if (isActive)
                            {
                                Timber.w("Stream read error: ${error.message}")
                                delay(100)
                            }
                        }
                    }
                }

                Timber.i("=== Stream Mode Stopped ===")
            }
            catch (e: Exception)
            {
                Timber.e(e, "Fatal error in stream mode")
            }
            finally
            {
                _readingMode.value = ReadingMode.Stopped
            }
        }
    }

    /**
     * Stops any active reading mode.
     */
    private fun stopReading()
    {
        readingJob?.cancel()
        readingJob = null
        currentConnection?.clearLineBuffer()
        _readingMode.value = ReadingMode.Stopped
        Timber.i("Reading stopped")
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

        // Unregister USB state receiver
        usbStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Timber.d("USB state receiver unregistered")
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered or never registered
                Timber.w("USB state receiver was already unregistered")
            }
        }
    }

    // MARK: Demo functions

    /**
     * Test function that sends a predetermined sequence of commands to the microcontroller.
     * Each command waits for a response before proceeding to the next one.
     */
    private fun runCommandSequenceTest()
    {
        // Cancel any streaming mode
        stopReading()
        _readingMode.value = ReadingMode.CommandSequence

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
                    "#",
                    "2",
                    "8",
                    "53200",
                    "q",
                    "3",
                    "QA0DEF",
                    "7",
                    "BK8600",
                    "6",
                    "11",
                    "2",
                    "4",
                    "14097157",
                    "q"
                )

                var sequenceSuccess = true

                // Send each command and wait for response
                for ((index, command) in commandSequence.withIndex())
                {
                    if (!isActive)
                    {
                        Timber.i("Command sequence cancelled by user")
                        break
                    }

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
                        val response = waitForAnyResponse(connection, timeoutMs = 500)

                        if (response != null)
                        {
                            Timber.d("← Response: $response")
                        }
                        else
                        {
//                            Timber.w("No response received for command: $command")
                            // Continue anyway - some commands might not respond
                        }

                        // Brief pause between commands
                        delay(50) // 500ms delay between commands

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
            finally
            {
                _readingMode.value = ReadingMode.Stopped
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
            connection.readLine(timeoutMs = timeoutMs)
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
                        // Calculate remaining time and use it for readAvailable timeout
                        val remainingTime = (timeoutMs - (System.currentTimeMillis() - startTime)).toInt()
                        val readTimeout = minOf(remainingTime, 100) // Use smaller chunks of 100ms max
                        val data = connection.readAvailable(timeoutMs = readTimeout)

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
                    catch (e: Exception)
                    {
                        delay(500)
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

    /**
     * Initializes a BroadcastReceiver to listen for USB device attachment/detachment events.
     * Automatically refreshes the device list when USB devices are connected or disconnected.
     */
    private fun initializeUsbStateReceiver() {
        usbStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Timber.d("USB device attached - refreshing device list")
                        // Small delay to allow the system to fully recognize the device
                        lifecycleScope.launch {
                            delay(500)
                            scanForDevices()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Timber.d("USB device detached - refreshing device list")
                        // If we're connected to this device, update our state
                        val detachedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        // Check if the detached device is our current connection
                        if (currentConnection != null && detachedDevice != null) {
                            // You might want to check if this is the connected device
                            // For now, we'll just refresh the list
                            disconnectDevice()
                        }
                        scanForDevices()
                    }
                }
            }
        }
    }

}