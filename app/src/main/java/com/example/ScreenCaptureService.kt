package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

// --- Intent Data Bridge Holder ---

object ServiceStatus {
    val isRunning = MutableStateFlow(false)
}

object MediaProjectionHolder {
    var resultCode: Int = 0
    var data: Intent? = null
}

// --- Foreground Service ---

class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var overlayManager: OverlayManager
    private var isProcessingTranslation = false

    companion object {
        private const val CHANNEL_ID = "screen_capture_channel_id"
        private const val NOTIFICATION_ID = 20389
        
        /**
         * Starts the service helper.
         */
        fun startService(context: Context, resultCode: Int, data: Intent) {
            MediaProjectionHolder.resultCode = resultCode
            MediaProjectionHolder.data = data
            val intent = Intent(context, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the service helper.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        overlayManager.setOnTranslateListener {
            triggerTranslation()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceStatus.isRunning.value = true
        createNotificationChannel()
        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback starting without projection type if permission isn't fully bound yet
            startForeground(NOTIFICATION_ID, notification)
        }

        // Deploy the on-screen overlays
        overlayManager.showBubble()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Gathers a single frame screenshot and submits it to Gemini for translation.
     */
    private fun triggerTranslation() {
        if (isProcessingTranslation) return
        isProcessingTranslation = true

        serviceScope.launch {
            OverlayState.isLoading.value = true
            OverlayState.isResultVisible.value = false

            val screenshot = captureScreen()
            if (screenshot == null) {
                OverlayState.translationResult.value = "Error: Screen capture was unsuccessful.\n\nPlease verify that you granted Media Capture permission and try relaunching the Screen Translator."
                OverlayState.isLoading.value = false
                overlayManager.showResultWindow()
                isProcessingTranslation = false
                return@launch
            }

            // Execute translation using Gemini Multimodal input
            val result = GeminiTranslator.translateScreenImage(
                bitmap = screenshot,
                targetLanguage = OverlayState.targetLanguage.value
            )

            OverlayState.translationResult.value = result
            OverlayState.isLoading.value = false
            
            // Present the result dialog overlay
            overlayManager.showResultWindow()
            isProcessingTranslation = false
        }
    }

    /**
     * Grabs a high-fidelity image frame from the MediaProjection.
     */
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        val resultCode = MediaProjectionHolder.resultCode
        val data = MediaProjectionHolder.data ?: return@withContext null

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            projectionManager.getMediaProjection(resultCode, data) ?: return@withContext null
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }

        // Screen parameters setup
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // Acquire 1 frame container
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        val virtualDisplay = try {
            mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
        } catch (e: Exception) {
            mediaProjection.stop()
            imageReader.close()
            return@withContext null
        }

        // Delay briefly to allow frame content pipeline to settle
        delay(200)

        var bitmap: Bitmap? = null
        try {
            val image = imageReader.acquireLatestImage() ?: imageReader.acquireNextImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                // Form full dimension bitmap capturing padding configuration
                val tempBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop out any column row padding to match actual screen resolution
                bitmap = if (rowPadding != 0) {
                    Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                } else {
                    tempBitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                virtualDisplay?.release()
                imageReader.close()
                mediaProjection.stop()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains the floating translation bubble active on screen."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator")
            .setContentText("The floating translation bubble is active. Tap to translate.")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceStatus.isRunning.value = false
        serviceScope.cancel()
        overlayManager.destroy()
    }
}
