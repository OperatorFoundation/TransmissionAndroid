package org.operatorfoundation.transmissionandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import org.operatorfoundation.transmission.SerialReader
import timber.log.Timber
import java.sql.Driver
import java.text.SimpleDateFormat
import java.util.*

class SerialDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionFactory = SerialConnectionFactory(application)
    private var currentConnection: SerialConnection? = null
    private var serialReader: SerialReader? = null

    // Public State Flows
    val connectionState = connectionFactory.connectionState

    private val _readingMode = MutableStateFlow<ReadingMode>(ReadingMode.Stopped)
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private val _logMessages = MutableStateFlow<List<LogMessage>>(emptyList())
    val logMessages: StateFlow<List<LogMessage>> = _logMessages.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<UsbSerialDriver>>(emptyList())
    val availableDevices: StateFlow<List<UsbSerialDriver>> = _availableDevices.asStateFlow()

    sealed class ReadingMode {
        object Stopped : ReadingMode()
        object Streaming : ReadingMode()
        object CommandSequence : ReadingMode()
    }

    data class LogMessage(
        val timestamp: String,
        val message: String,
        val isError: Boolean = false
    )

    init {
        // Observe connection state changes
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is SerialConnectionFactory.ConnectionState.Connected -> {
                        if (currentConnection == null) {
                            currentConnection = state.connection
                            serialReader = SerialReader(state.connection)
                            addLogMessage("Successfully connected!")
                        }
                    }

                    is SerialConnectionFactory.ConnectionState.Disconnected -> {
                        currentConnection = null
                        serialReader = null
                    }

                    is SerialConnectionFactory.ConnectionState.Error -> {
                        addLogMessage("Connection error: ${state.message}", isError = true)
                    }

                    else -> { /* Other states handled by UI */
                    }
                }
            }
        }
    }

    /**
     * Scans for available USB serial devices.
     */
    fun scanForDevices() {
        viewModelScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) {
                    connectionFactory.findAvailableDevices()
                }

                _availableDevices.value = devices
                addLogMessage("Found ${devices.size} USB serial device(s)")
            } catch (error: Exception) {
                addLogMessage("Error scanning for devices: ${error.message}", isError = true)
            }
        }
    }

    /**
     * Connects to the specified USB serial driver.
     */
    fun connectToDevice(driver: UsbSerialDriver) {
        addLogMessage("Connecting to ${driver.device.deviceName}...")
        connectionFactory.createConnection(driver.device)
    }

    /**
     * Disconnects from the current device.
     */
    fun disconnect() {
        currentConnection?.close()
        currentConnection = null
        serialReader = null
        connectionFactory.disconnect()
        addLogMessage("Disconnected from device.")
    }

    /**
     * Starts continuous streaming mode using SerialReader.
     */
    fun startStreamMode() {
        val reader = serialReader

        if (reader == null) {
            addLogMessage("No active connection for stream mode", isError = true)
            return
        }

        stopReading()
        _readingMode.value = ReadingMode.Streaming
        addLogMessage("=== Starting Stream Mode ===")

        reader.startLineReading(
            scope = viewModelScope,
            timeoutMs = 500,
            maxLength = 1024,
            onLine = { line ->
                addLogMessage("← Stream: $line")
            },
            onError = { error ->
                if (_readingMode.value == ReadingMode.Streaming) {
                    addLogMessage("Stream read error: ${error.message}", isError = true)
                }
            }
        )
    }

    /**
     * Stops any active reading mode.
     */
    fun stopReading() {
        serialReader?.stopReading(viewModelScope)
        _readingMode.value = ReadingMode.Stopped
        addLogMessage("Reading stopped.")
    }

    /**
     * Runs a command sequence test.
     */
    fun runCommandSequenceTest() {
        val connection = currentConnection
        if (connection == null) {
            addLogMessage("No active connection for command sequence", isError = true)
            return
        }

        stopReading()
        _readingMode.value = ReadingMode.CommandSequence

        viewModelScope.launch {
            try {
                addLogMessage("=== Starting Command Sequence Test ===")

                val commandSequence = listOf(
                    "2", "#", "2", "8", "53200", "q",
                    "3", "QA0DEF", "7", "BK8600", "6", "11",
                    "2", "4", "14097157", "q"
                )

                var sequenceSuccess = true

                for ((index, command) in commandSequence.withIndex()) {
                    if (!isActive) {
                        addLogMessage("Command sequence cancelled by user")
                        break
                    }

                    try {
                        addLogMessage("Sequence [${index + 1}/${commandSequence.size}]: Sending '$command'")

                        val sendSuccess = withContext(Dispatchers.IO) {
                            connection.write(command + "\r\n")
                        }

                        if (!sendSuccess) {
                            addLogMessage("Failed to send command: $command", isError = true)
                            sequenceSuccess = false
                            break
                        }

                        addLogMessage("→ Sent: $command")

                        val response = waitForAnyResponse(connection, 500)
                        if (response != null) {
                            addLogMessage("← Response: $response")
                        }

                        delay(50)
                    } catch (e: Exception) {
                        addLogMessage("Error at step ${index + 1}: ${e.message}", isError = true)
                        sequenceSuccess = false
                        break
                    }
                }

                if (sequenceSuccess) {
                    addLogMessage("Sequence complete, sending final '1' (transmit) command...")

                    val finalSendSuccess = withContext(Dispatchers.IO) {
                        connection.write("1")
                    }

                    if (finalSendSuccess) {
                        addLogMessage("→ Transmit command sent: 1")

                        val finalResponse = waitForAnyResponse(connection, 4 * 60 * 1000)
                        if (finalResponse != null) {
                            addLogMessage("← Final response: $finalResponse")
                        }

                        addLogMessage("=== Command Sequence Test COMPLETED ===")
                    }

                    withContext(Dispatchers.IO) {
                        connection.write("q")
                    }
                } else {
                    addLogMessage("=== Command Sequence Test FAILED ===", isError = true)
                }
            } catch (e: Exception) {
                addLogMessage("Fatal error: ${e.message}", isError = true)
            } finally {
                _readingMode.value = ReadingMode.Stopped
            }
        }
    }

    /**
     * Waits for any response (not necessarily line-terminated).
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
                        val remainingTime =
                            (timeoutMs - (System.currentTimeMillis() - startTime)).toInt()
                        val readTimeout = minOf(remainingTime, 100)
                        val data = connection.readAvailable(timeoutMs = readTimeout)

                        if (data != null && data.isNotEmpty())
                        {
                            lastDataTime = System.currentTimeMillis()

                            for (byte in data)
                            {
                                val char = byte.toInt().toChar()
                                if (char.isISOControl().not() && char != '\u0000')
                                {
                                    buffer.append(char)
                                }
                            }
                        }
                        else
                        {
                            if (buffer.isNotEmpty() &&
                                System.currentTimeMillis() - lastDataTime > 200
                            ) {
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

                if (buffer.isNotEmpty()) { buffer.toString().trim() }
                else { null }
            }
        }
    }

    /**
     * Adds a timestamped log message.
     */
    fun addLogMessage(message: String, isError: Boolean = false)
    {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = LogMessage(timestamp, message, isError)
        _logMessages.value = _logMessages.value + logMessage
    }

    /**
     * Clears all log messages.
     */
    fun clearLogs() { _logMessages.value = emptyList() }

    override fun onCleared()
    {
        super.onCleared()
        stopReading()
        currentConnection?.close()
    }
}