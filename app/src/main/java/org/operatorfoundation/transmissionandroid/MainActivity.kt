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
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive


/**
 * Demo activity showcasing serial communication with microcontrollers.
 * Demonstrates streaming and command-response patterns with Arduino devices.
 */
class MainActivity : ComponentActivity()
{
    private var usbStateReceiver: BroadcastReceiver? = null
    private lateinit var viewModel: SerialDemoViewModel

    /**
     * Custom Timber Tree that logs to both system log and UI display.
     */
    private inner class UILoggingTree : Timber.DebugTree()
    {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?)
        {
            // Always log to system (Logcat)
            super.log(priority, tag, message, t)

            // Only log to UI if ViewModel is initialized
            if (::viewModel.isInitialized)
            {
                // Also add to UI display for user visibility
                val isError = priority >= android.util.Log.WARN
                val displayMessage = if (t != null) "$message: ${t.message}" else message

                viewModel.addLogMessage(displayMessage, isError)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[SerialDemoViewModel::class.java]

        // Initialize Timber with custom tree for dual logging
        if (Timber.treeCount == 0) Timber.plant(UILoggingTree())

        // Initialize and register USB state receiver for auto-detection
        initializeUsbStateReceiver()
        val usbIntentFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbStateReceiver, usbIntentFilter)

        Timber.i("USB auto-detection enabled")

        setContent {
            SerialDemoUI()
        }

        // Scan for available devices on startup
        viewModel.scanForDevices()

        Timber.i("Serial Demo initialized")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SerialDemoUI()
    {
        val context = LocalContext.current
        val connectionState by viewModel.connectionState.collectAsState()
        val devices by viewModel.availableDevices.collectAsState()
        val logs by viewModel.logMessages.collectAsState()
        val currentMode by viewModel.readingMode.collectAsState()
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
                is SerialConnectionFactory.ConnectionState.Error -> {
                    val message = (connectionState as SerialConnectionFactory.ConnectionState.Error).message
                    Toast.makeText(context, "Connection failed: $message", Toast.LENGTH_LONG).show()
                }
                else -> { /* Handled by ViewModel */ }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Connection Status Card
            ConnectionStatusCard(connectionState)

            // Device Selection
            DeviceSelectionCard(
                devices = devices,
                isConnected = connectionState is SerialConnectionFactory.ConnectionState.Connected,
                onRefresh = { viewModel.scanForDevices() },
                onConnect = { viewModel.connectToDevice(it) },
                onDisconnect = { viewModel.disconnect() }
            )

            // Communication Controls
            if (connectionState is SerialConnectionFactory.ConnectionState.Connected) {
                CommunicationControlsCard(
                    currentMode = currentMode,
                    onStreamMode = { viewModel.startStreamMode() },
                    onCommandMode = { viewModel.runCommandSequenceTest() },
                    onStop = { viewModel.stopReading() }
                )
            }

            // Log Display
            LogDisplayCard(
                logs = logs,
                listState = listState,
                onClearLogs = { viewModel.clearLogs() },
                modifier = Modifier.weight(1f)  // Pass weight as modifier parameter
            )
        }
    }

    @Composable
    private fun ConnectionStatusCard(connectionState: SerialConnectionFactory.ConnectionState)
    {
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
                Text(text = "Connection Status", style = MaterialTheme.typography.titleMedium)
                Text(text = getConnectionStatusText(connectionState), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun DeviceSelectionCard(
        devices: List<UsbSerialDriver>,
        isConnected: Boolean,
        onRefresh: () -> Unit,
        onConnect: (UsbSerialDriver) -> Unit,
        onDisconnect: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Available Devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRefresh) { Text("Refresh") }
                }

                if (devices.isEmpty()) {
                    Text(
                        "No USB serial devices found. Connect your device via USB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    devices.forEach { driver ->
                        DeviceCard(driver, isConnected, onConnect, onDisconnect)
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

    @Composable
    private fun CommunicationControlsCard(
        currentMode: SerialDemoViewModel.ReadingMode,
        onStreamMode: () -> Unit,
        onCommandMode: () -> Unit,
        onStop: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Communication Mode", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStreamMode,
                        modifier = Modifier.weight(1f),
                        enabled = currentMode != SerialDemoViewModel.ReadingMode.Streaming,
                        colors = if (currentMode == SerialDemoViewModel.ReadingMode.Streaming) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        } else ButtonDefaults.buttonColors()
                    ) { Text("Stream Mode") }

                    Button(
                        onClick = onCommandMode,
                        modifier = Modifier.weight(1f),
                        enabled = currentMode != SerialDemoViewModel.ReadingMode.CommandSequence,
                        colors = if (currentMode == SerialDemoViewModel.ReadingMode.CommandSequence) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        } else ButtonDefaults.buttonColors()
                    ) { Text("Command Mode") }
                }

                if (currentMode != SerialDemoViewModel.ReadingMode.Stopped) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop") }
                }

                Text(
                    text = when (currentMode) {
                        SerialDemoViewModel.ReadingMode.Stopped -> "No active mode"
                        SerialDemoViewModel.ReadingMode.Streaming -> "Streaming: Displaying all incoming data"
                        SerialDemoViewModel.ReadingMode.CommandSequence -> "Running command sequence..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun LogDisplayCard(
        logs: List<SerialDemoViewModel.LogMessage>,
        listState: androidx.compose.foundation.lazy.LazyListState,
        onClearLogs: () -> Unit,
        modifier: Modifier = Modifier  // Add modifier parameter
    ) {
        Card(
            modifier = modifier.fillMaxWidth()  // Remove weight, apply passed modifier
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Communication Log", style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),  // This weight stays - it's inside Column scope
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
                    onClick = onClearLogs,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Clear Log") }
            }
        }
    }

    @Composable
    private fun DeviceCard(
        driver: UsbSerialDriver,
        isConnected: Boolean,
        onConnect: (UsbSerialDriver) -> Unit,
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
                    Text(driver.device.deviceName ?: "Unknown Device", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "VID: ${String.format("0x%04X", driver.device.vendorId)} " +
                                "PID: ${String.format("0x%04X", driver.device.productId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Disconnect") }
                } else {
                    Button(onClick = { onConnect(driver) }) { Text("Connect") }
                }
            }
        }
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

    private fun initializeUsbStateReceiver()
    {
        usbStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?)
            {
                when (intent?.action)
                {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        lifecycleScope.launch {
                            delay(500)
                            viewModel.scanForDevices()
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

                        if (detachedDevice != null)
                        {
                            viewModel.disconnect()
                        }

                        viewModel.scanForDevices()
                    }
                }
            }
        }
    }

    override fun onDestroy()
    {
        super.onDestroy()

        usbStateReceiver?.let {
            try
            {
                unregisterReceiver(it)
            }
            catch (e: IllegalArgumentException)
            {
                // Already unregistered
            }
        }
    }
}