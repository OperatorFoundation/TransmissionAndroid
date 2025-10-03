package org.operatorfoundation.transmission

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Helper class for common serial reading patterns.
 * Manages coroutine lifecycle and provides cancellation-safe operations.
 *
 * This class wraps a SerialConnection and provides higher-level reading patterns.
 *
 */
class SerialReader(private val connection: SerialConnection)
{
    private var readJob: Job? = null

    /**
     * Starts continuous line based reading with a callback.
     * Automatically handles cancellation and cleanup.
     *
     * @param scope CoroutineScope to use for launching the reading coroutine.
     * @param timeoutMs Timeout in milliseconds for each readLine attempt.
     * @param onLine Callback invoked on the Main dispatcher when a line is received.
     * @param onError Callback invoked on the Main dispatcher when an error occurs.
     * @return Job that can be used to monitor the read operation.
     */
    fun startLineReading(
        scope: CoroutineScope,
        timeoutMs: Long = 500,
        maxLength: Int = 1024,
        onLine: (String) -> Unit,
        onError: (Exception) -> Unit = {}
    ): Job
    {
        // Stop any existing reading operations
        stopReading(scope)

        readJob = scope.launch(Dispatchers.IO) {
            try
            {
                while (isActive)
                {
                    try
                    {
                        val line = connection.readLine(timeoutMs = timeoutMs, maxLength = maxLength)

                        if (line != null)
                        {
                            withContext(Dispatchers.Main){
                                onLine(line)
                            }
                        }
                    }
                    catch (error: Exception)
                    {
                        if (isActive)
                        {
                            withContext(Dispatchers.Main) {
                                onError(error)
                            }
                        }
                    }
                }
            }
            finally
            {
                // Cleanup when coroutine is cancelled
                connection.clearLineBuffer()
            }
        }

        return readJob!!
    }

    /**
     * Starts continuous raw byte reading with a callback.
     * Useful for binary protocols or non-line-based communication.
     *
     * @param scope CoroutineScope to launch the reading coroutine in
     * @param maxSize Maximum bytes to read per attempt
     * @param timeoutMs Timeout for each read attempt
     * @param onData Callback invoked on the Main dispatcher when data is received
     * @param onError Callback invoked on the Main dispatcher when an error occurs
     * @return Job that can be used to monitor the reading operation
     */
    fun startRawReading(
        scope: CoroutineScope,
        timeoutMs: Int = 100,
        maxSize: Int = 4096,
        onData: (ByteArray) -> Unit,
        onError: (Exception) -> Unit = {}
    ): Job
    {
        // Stop any existing reading operation
        stopReading(scope)

        readJob = scope.launch(Dispatchers.IO) {
            try
            {
                while (isActive)
                {
                    try
                    {
                        val data = connection.readAvailable(maxSize = maxSize, timeoutMs = timeoutMs)

                        if (data != null && data.isNotEmpty())
                        {
                            withContext(Dispatchers.Main)
                            {
                                onData(data)
                            }
                        }
                    }
                    catch (e: Exception) {
                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                onError(e)
                            }
                        }
                    }
                }
            }
            finally
            {
                // Cleanup when coroutine is cancelled
                connection.clearLineBuffer()
            }
        }

        return readJob!!
    }

    /**
     * Stops reading and clears buffers.
     * Safe to call from any thread and safe to call multiple times.
     * This is a blocking call that waits for the reading job to complete.
     *
     * @param scope CoroutineScope to launch the cancellation in (for suspend functions)
     */
    fun stopReading(scope: CoroutineScope)
    {
        scope.launch {
            try
            {
                readJob?.cancelAndJoin()
            }
            catch (error: Exception)
            {
                // Ignore cancellation exceptions
            }
            finally
            {
                readJob = null
                withContext(Dispatchers.IO)
                {
                    connection.clearLineBuffer()
                }
            }
        }
    }

    /**
     * Checks if reading is currently active.
     */
    fun isReading(): Boolean { return readJob?.isActive == true }

}