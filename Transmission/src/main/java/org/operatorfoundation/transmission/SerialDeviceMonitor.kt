package org.operatorfoundation.transmission

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Application-scoped monitor for USB serial device availability.
 *
 * Wraps [SerialConnectionFactory] and self-registers a USB broadcast receiver
 * using application context, so attach/detach events are handled for the full
 * app lifetime without requiring any Activity involvement.
 *
 * Instantiate once in Application.onCreate() and hold as a property.
 * Observe [connectionState] to react to connection changes.
 *
 * Usage:
 * ```
 * // In Application.onCreate():
 * serialDeviceMonitor = SerialDeviceMonitor(applicationContext)
 *
 * // Anywhere that needs connection state:
 * serialDeviceMonitor.connectionState.collect { state -> ... }
 * ```
 */
class SerialDeviceMonitor(private val context: Context)
{
    private val factory = SerialConnectionFactory(context)

    /** Current serial connection state. Observe this to react to connect/disconnect. */
    val connectionState: StateFlow<SerialConnectionFactory.ConnectionState> = factory.connectionState

    /** True when a serial connection is established. Convenience derived from [connectionState]. */
    val isConnected: Boolean
        get() = connectionState.value is SerialConnectionFactory.ConnectionState.Connected

    // ==================== USB Broadcast Receiver ====================

    private val usbReceiver = object : BroadcastReceiver()
    {
        override fun onReceive(context: Context, intent: Intent)
        {
            when (intent.action)
            {
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                {
                    Timber.d("SerialDeviceMonitor: USB device attached")
                    onDeviceAttached()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                {
                    Timber.d("SerialDeviceMonitor: USB device detached")
                    factory.onDeviceDetached()
                }
            }
        }
    }

    init
    {
        // Register receiver for the app's lifetime using application context.
        // No need to unregister — this lives as long as the app does.
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Timber.d("SerialDeviceMonitor: USB receiver registered")

        // Check for already-connected devices on startup
        // (device may have been attached before the app launched)
        onDeviceAttached()
    }

    // ==================== Private ====================

    /**
     * Called on USB attach or startup. Finds available serial devices
     * and initiates connection to the first one found, if any.
     *
     * No-ops if a connection is already established or in progress.
     */
    private fun onDeviceAttached()
    {
        // Don't attempt if already connected or connecting
        val current = connectionState.value
        if (current is SerialConnectionFactory.ConnectionState.Connected ||
            current is SerialConnectionFactory.ConnectionState.Connecting ||
            current is SerialConnectionFactory.ConnectionState.RequestingPermission)
        {
            Timber.d("SerialDeviceMonitor: connection already active, ignoring attach")
            return
        }

        val devices = factory.findAvailableDevices()
        if (devices.isEmpty())
        {
            Timber.d("SerialDeviceMonitor: no serial devices found")
            return
        }

        Timber.d("SerialDeviceMonitor: found ${devices.size} device(s), connecting to first")
        factory.createConnection(devices.first().device)
    }
}