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
import java.security.Permission
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages USB device permissions for serial communication.
 * Handles permission requests, caching, and provides real time permission state updates.
 */
class USBPermissionManager(private val context: Context)
{
    companion object
    {
        private const val ACTION_USB_PERMISSION = "org.operatorfoundation.transmission.USB_PERMISSION"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_PERMISSION_GRANTED = "permission"

        private val PENDING_INTENT_FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        else
        {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

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
        val permissionReceiver = object : BroadcastReceiver() {

            override fun onReceive(context: Context, intent: Intent)
            {
                if (ACTION_USB_PERMISSION == intent.action)
                {
                    val receivedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    {
                        intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
                    }
                    else
                    {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DEVICE)
                    }

                    // Verify the response is for our device.
                    if (receivedDevice != null && getDeviceKey(receivedDevice) == deviceKey)
                    {
                        val granted = intent.getBooleanExtra(EXTRA_PERMISSION_GRANTED, false)
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
                }
            }
        }

        // Register receiver and request permission
        try {
            val intentFilter = IntentFilter(ACTION_USB_PERMISSION)
            context.registerReceiver(permissionReceiver, intentFilter)

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                deviceKey.hashCode(), // Use device key hash as a unique request code
                Intent(ACTION_USB_PERMISSION),
                PENDING_INTENT_FLAGS
            )

            usbManager.requestPermission(device, permissionIntent)
        }
        catch (error: Exception)
        {
            trySend(PermissionResult.Error("Failed to request permission: ${error.message}"))
            close()
        }

        // Cleanup when flow is cancelled.
        awaitClose {
            try
            {
                context.unregisterReceiver(permissionReceiver)
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