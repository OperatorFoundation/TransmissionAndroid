# TransmissionAndroid
An Android library for reliable serial communication with microcontrollers over USB. Provides both simple byte-level communication and length-prefixed protocols for robust data exchange.

# TransmissionAndroid

A Kotlin library for USB serial communication with microcontrollers on Android devices.

## Features

- **USB Serial Communication**: Direct communication with Arduino, ESP32, FeatherM4, and other microcontrollers
- **Modern Android APIs**: Uses contemporary permission handling and Kotlin coroutines
- **Automatic Permission Management**: Streamlined USB permission requests
- **Multiple Connection Types**: Supports both USB serial and network (TCP/UDP) connections
- **Reactive State Management**: StateFlow-based connection state updates

## Requirements

- Android API 21+ (Android 5.0)
- USB OTG support on Android device
- Kotlin/Java project

## Dependencies

Add to your `build.gradle` (app level):

```gradle
dependencies {
    implementation 'com.github.mik3y:usb-serial-for-android:3.4.6'
    implementation 'com.jakewharton.timber:timber:5.0.1' // Optional: for logging
}
```

## Basic Usage

### 1. Create Connection Factory

```kotlin
val connectionFactory = SerialConnectionFactory(this) // Activity context
```

### 2. Find and Connect to Device

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var connectionFactory: SerialConnectionFactory
    private var currentConnection: SerialConnection? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        connectionFactory = SerialConnectionFactory(this)
        
        // Find available devices
        val devices = connectionFactory.findAvailableDevices()
        
        if (devices.isNotEmpty()) {
            connectToDevice(devices.first())
        }
    }
    
    private fun connectToDevice(driver: UsbSerialDriver) {
        lifecycleScope.launch {
            connectionFactory.createConnection(driver.device).collect { state ->
                when (state) {
                    is SerialConnectionFactory.ConnectionState.Connected -> {
                        currentConnection = state.connection
                        startCommunication()
                    }
                    is SerialConnectionFactory.ConnectionState.Error -> {
                        Log.e("Serial", "Connection failed: ${state.message}")
                    }
                }
            }
        }
    }
}
```

### 3. Send and Receive Data

```kotlin
// Send data
private fun sendCommand(command: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        currentConnection?.write(command)
    }
}

// Read data
private fun startCommunication() {
    lifecycleScope.launch(Dispatchers.IO) {
        val connection = currentConnection ?: return@launch
        
        while (currentConnection != null) {
            val data = connection.readAvailable()
            if (data != null && data.isNotEmpty()) {
                val message = String(data)
                Log.d("Serial", "Received: $message")
            }
            delay(10)
        }
    }
}
```

### 4. Disconnect

```kotlin
override fun onDestroy() {
    super.onDestroy()
    currentConnection?.close()
    connectionFactory.disconnect()
}
```

## Connection States

The library provides reactive connection state updates:

- `Disconnected` - No active connection
- `RequestingPermission` - Requesting USB permission from user
- `Connecting` - Establishing connection
- `Connected` - Ready for communication
- `Error` - Connection failed with error message

## Supported Devices

### Tested
- Adafruit Feather M4
- 
### Theoretical
- Arduino boards (Uno, Nano, Mega, etc.)
- ESP32 and ESP8266 development boards
- Any device using common USB-to-serial chips (FTDI, CH340, CP210x, etc.)

## Demo Application

See the included demo app for a complete example showing:
- Device discovery and connection
- Permission handling
- Real-time data exchange
- Connection state management

## License

MIT License

## Contributing

1. Fork the repository
2. Create a feature branch
3. Submit a pull request