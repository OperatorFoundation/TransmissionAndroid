package org.operatorfoundation.transmission

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages USB device permissions for serial communication.
 * Handles permission requests, caching, and provides real time permission state updates.
 */
class USBPermissionManager(private val activityContext: Context)
{
    companion object
    {
        private const val ACTION_USB_PERMISSION = "org.operatorfoundation.transmission.USB_PERMISSION"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_PERMISSION_GRANTED = "permission"

        private val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private val usbManager = activityContext.getSystemService(Context.USB_SERVICE) as UsbManager

    // Cache permission states to avoid repeated system calls
    private val permissionCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Result of USB permission request.
     */
    sealed class PermissionResult
    {
        object Granted: PermissionResult()
        object Denied: PermissionResult()
        data class Error(val message: String) : PermissionResult()
    }

    init {
        Timber.d("=== USBPermissionManager Context Info ===")
        Timber.d("Context type: ${activityContext.javaClass.simpleName}")
        Timber.d("Context: $activityContext")
        if (activityContext is android.app.Activity) {
            Timber.d("Is Activity: true")
        }
        else if (activityContext is android.app.Application)
        {
            Timber.d("Is Application: true")
        }
        else
        {
            Timber.d("Context type: Other (${activityContext.javaClass.name})")
        }
    }

    /**
     * Checks if permission is already granted for the specified device.
     * Updates internal cache with the result.
     */
    fun hasPermission(device: UsbDevice): Boolean
    {
        val deviceKey = getDeviceKey(device)
        val hasPermission = usbManager.hasPermission(device)
        permissionCache[deviceKey] = hasPermission
        return hasPermission
    }

    /**
     * Requests permission for the specified USB device.
     * Returns a flow the emits the permission result.
     *
     * @param device The USB device to request permission for.
     * @return Flow<PermissionResult> The permission request outcome.
     */
    fun requestPermissionFor(device: UsbDevice): Flow<PermissionResult> = callbackFlow {
        val deviceKey = getDeviceKey(device)

        // Check if permission already exists.
        if (hasPermission(device))
        {
            trySend(PermissionResult.Granted)
            close()
            return@callbackFlow
        }

        // Create a broadcast receiver to handle the permission response.
        val permissionReceiver = object : BroadcastReceiver()
        {
            override fun onReceive(context: Context, intent: Intent)
            {
                Timber.d("=== BROADCAST RECEIVED ===")
                Timber.d("Action: ${intent.action}")
                Timber.d("Expected: $ACTION_USB_PERMISSION")
                Timber.d("Context: ${context.javaClass.simpleName}")
                Timber.d("Thread: ${Thread.currentThread().name}")

                if (ACTION_USB_PERMISSION == intent.action)
                {
                    Timber.d("USB permission broadcast received!")

                    // Check what Android actually sends
                    val androidDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    }
                    else
                    {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    Timber.d("Android provided device: ${androidDevice?.deviceName}")
                    Timber.d("Android device key: ${androidDevice?.let { getDeviceKey(it) }}")
                    Timber.d("Expected device key: $deviceKey")
                    Timber.d("Permission granted flag: ${intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)}")

                    // Debug: Log all extras in the intent
                    val extras = intent.extras
                    if (extras != null)
                    {
                        for (key in extras.keySet())
                        {
                            Timber.d("Intent extra: $key = ${extras.get(key)}")
                        }
                    }
                    else
                    {
                        Timber.d("No extras in intent")
                    }

                    val receivedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    }
                    else
                    {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    // Verify the response is for our device.
                    if (receivedDevice != null && getDeviceKey(receivedDevice) == deviceKey)
                    {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        permissionCache[deviceKey] = granted

                        val result = if (granted)
                        {
                            PermissionResult.Granted
                        }
                        else
                        {
                            PermissionResult.Denied
                        }

                        trySend(result)
                        close()
                    }
                    else
                    {
                        if (receivedDevice != null) {
                            Timber.d(
                                "Received permission for an unexpected device: ${
                                    getDeviceKey(
                                        receivedDevice
                                    )
                                }"
                            )
                        }
                        else
                        {
                            Timber.d("Received permission but the device is null.")
                        }

                        trySend(PermissionResult.Error("Device mismatch in permission response"))
                        close()
                    }
                }
                else
                {
                    Timber.d("Ignoring broadcast with different action")
                }
            }
        }

        // Register receiver and request permission
        try
        {
            kotlinx.coroutines.MainScope().launch {
                val intentFilter = IntentFilter(ACTION_USB_PERMISSION)

                Timber.d("=== REGISTERING RECEIVER ===")
                Timber.d("Context: ${activityContext.javaClass.simpleName}")
                Timber.d("Filter: ${intentFilter.actionsIterator().asSequence().toList()}")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                {
                    activityContext.registerReceiver(
                        permissionReceiver,
                        intentFilter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                }
                else
                {
                    activityContext.registerReceiver(permissionReceiver, intentFilter)
                }

                Timber.d("Receiver registered successfully")
                Timber.d("IntentFilter actions: ${intentFilter.actionsIterator().asSequence().toList()}")
                Timber.d("Current thread: ${Thread.currentThread().name}")
                Timber.d("Context: ${activityContext.javaClass.simpleName}")

                // Create intent with the device as an extra so it comes back in the broadcast
                val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    activityContext,
                    device.deviceId,
                    permissionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                usbManager.requestPermission(device, pendingIntent)

                Timber.d("Permission request sent for device: ${device.deviceName}")
                Timber.d("Using action: $ACTION_USB_PERMISSION")
                Timber.d("Device key: $deviceKey")
            }

        }
        catch (error: Exception)
        {
            Timber.e(error, "Failed to register receiver or request permission")
            trySend(PermissionResult.Error("Failed to request permission: ${error.message}"))
            close()
        }

        // Cleanup when flow is cancelled.
        awaitClose {
            try
            {
                activityContext.unregisterReceiver(permissionReceiver)
            }
            catch (e: IllegalArgumentException)
            {
                // Receiver was already unregistered, ignore
            }
        }
    }

    /**
     * Suspending function that requests permission and returns the result.
     */
    suspend fun requestPermissionSuspend(device: UsbDevice): PermissionResult
    {
        return suspendCancellableCoroutine { continuation ->
            val permissionFlow = requestPermissionFor(device)

            // Collect the first result and complete.
            val job = kotlinx.coroutines.GlobalScope.launch {
                permissionFlow.collect { result ->
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    /**
     * Clears the permission cache for a specific device.
     * Call this when the device has been disconnected or where permission state may have changed.
     */
    fun clearPermissionCache(device: UsbDevice)
    {
        val deviceKey = getDeviceKey(device)
        permissionCache.remove(deviceKey)
    }

    /**
     * Clears all cached permission states.
     * Useful when you want to force fresh permission checks.
     */
    fun clearAllPermissionCache()
    {
        permissionCache.clear()
    }

    /**
     * Creates a unique identifier for a USB device.
     * Uses vendor ID, productID, and device name for uniqueness.
     */
    private fun getDeviceKey(device: UsbDevice): String
    {
        return "${device.vendorId}:${device.productId}:${device.deviceName}"
    }


}