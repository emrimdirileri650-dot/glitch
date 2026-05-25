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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var pendingDeferredBitmap: CompletableDeferred<Bitmap?>? = null

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
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        // Initialize MediaProjection immediately to satisfy Android 14+ token validity rules
        synchronized(this) {
            if (mediaProjection == null) {
                val resultCode = MediaProjectionHolder.resultCode
                val data = MediaProjectionHolder.data
                if (data != null) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = try {
                        projectionManager.getMediaProjection(resultCode, data)?.apply {
                            registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }
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

    private fun cleanupCaptureSession() {
        synchronized(this) {
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            virtualDisplay = null

            try {
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            imageReader = null

            try {
                backgroundThread?.quit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            backgroundThread = null
            backgroundHandler = null
            pendingDeferredBitmap = null
        }
    }

    /**
     * Grabs a high-fidelity image frame from the MediaProjection.
     */
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        // Clean up any existing capture session resources first
        cleanupCaptureSession()

        // Safely access the pre-initialized MediaProjection instance
        val mp = synchronized(this@ScreenCaptureService) {
            mediaProjection
        } ?: return@withContext null

        val deferred = CompletableDeferred<Bitmap?>()
        pendingDeferredBitmap = deferred

        // Configure a new ImageReader strictly for this capture occurrence
        val ir = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
        imageReader = ir

        val thread = HandlerThread("ScreenCaptureThread")
        thread.start()
        backgroundThread = thread
        val handler = Handler(thread.looper)
        backgroundHandler = handler

        val listener = ImageReader.OnImageAvailableListener { reader ->
            try {
                val image = reader.acquireLatestImage() ?: reader.acquireNextImage()
                if (image != null) {
                    try {
                        val deferredVal = pendingDeferredBitmap
                        if (deferredVal != null && !deferredVal.isCompleted) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val tempBitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            tempBitmap.copyPixelsFromBuffer(buffer)

                            val croppedBitmap = if (rowPadding != 0) {
                                val cb = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                                if (cb != tempBitmap) {
                                    tempBitmap.recycle()
                                }
                                cb
                            } else {
                                tempBitmap
                            }
                            pendingDeferredBitmap = null
                            deferredVal.complete(croppedBitmap)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        deferred.complete(null)
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        ir.setOnImageAvailableListener(listener, handler)

        // Creating the VirtualDisplay forces the system to compost and render the initial frame instantly
        val vd = try {
            mp.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupCaptureSession()
            return@withContext null
        }
        virtualDisplay = vd

        val resultBitmap = try {
            withTimeout(2500) {
                deferred.await()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            cleanupCaptureSession()
        }

        resultBitmap
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
        cleanupCaptureSession()
        synchronized(this) {
            try {
                mediaProjection?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mediaProjection = null
        }
        overlayManager.destroy()
    }
}
