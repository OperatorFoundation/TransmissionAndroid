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
     * Returns a flow that emits the permission result once and closes.
     *
     * @param device The USB device to request permission for.
     * @return Flow<PermissionResult> emitting a single result.
     */
    fun requestPermissionFor(device: UsbDevice): Flow<PermissionResult> = callbackFlow {
        val deviceKey = getDeviceKey(device)

        // Short-circuit if permission is already granted.
        if (hasPermission(device))
        {
            trySend(PermissionResult.Granted)
            close()
            return@callbackFlow
        }

        val permissionReceiver = object : BroadcastReceiver()
        {
            override fun onReceive(context: Context, intent: Intent)
            {
                if (intent.action != ACTION_USB_PERMISSION) return

                val receivedDevice =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

                // Ignore broadcasts for other devices.
                if (receivedDevice == null || getDeviceKey(receivedDevice) != deviceKey)
                {
                    Timber.w("Permission broadcast for unexpected device: ${receivedDevice?.deviceName}")
                    return
                }

                // EXTRA_PERMISSION_GRANTED is unreliable on some Android versions; query directly.
                val granted = usbManager.hasPermission(receivedDevice)
                Timber.d("Permission result for ${device.deviceName}: granted=$granted")
                permissionCache[deviceKey] = granted

                trySend(if (granted) PermissionResult.Granted else PermissionResult.Denied)
                close()
            }
        }

        // Register before calling requestPermission() so we don't miss it
        val intentFilter = IntentFilter(ACTION_USB_PERMISSION)
        activityContext.registerReceiver(permissionReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        Timber.d("Permission receiver registered for ${device.deviceName}")

        val pendingIntent = PendingIntent.getBroadcast(
            activityContext,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).apply {
                `package` = activityContext.packageName  // make the intent explicit
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        usbManager.requestPermission(device, pendingIntent)
        Timber.d("Permission request sent for ${device.deviceName}")

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