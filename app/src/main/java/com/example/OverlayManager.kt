package com.example

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow

// --- Reactive State Holder ---

object OverlayState {
    val isBubbleVisible = MutableStateFlow(false)
    val isResultVisible = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val translationResult = MutableStateFlow("")
    val targetLanguage = MutableStateFlow("English")
}

// --- Service Lifecycle & SavedState Provider for Compose View Tree ---

class ServiceViewLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val myViewModelStore = ViewModelStore()

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    fun onCreate() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        myViewModelStore.clear()
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = myViewModelStore
}

// --- Overlay Manager ---

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: ComposeView? = null
    private var resultView: ComposeView? = null

    private var bubbleLifecycleOwner: ServiceViewLifecycleOwner? = null
    private var resultLifecycleOwner: ServiceViewLifecycleOwner? = null

    private var onTranslateTriggered: (() -> Unit)? = null

    /**
     * Set callback to invoke when the user clicks the floating translation bubble.
     */
    fun setOnTranslateListener(listener: () -> Unit) {
        this.onTranslateTriggered = listener
    }

    /**
     * Displays the Draggable Floating Bubble overlay on screen.
     */
    fun showBubble() {
        if (bubbleView != null) return

        bubbleLifecycleOwner = ServiceViewLifecycleOwner().apply {
            onCreate()
            onStart()
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80 // Initial coordinate X
            y = 600 // Initial coordinate Y
        }

        bubbleView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(bubbleLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(bubbleLifecycleOwner)
            setViewTreeViewModelStoreOwner(bubbleLifecycleOwner)
            setContent {
                MyApplicationTheme {
                    val isLoading by OverlayState.isLoading.collectAsState()
                    val targetLang by OverlayState.targetLanguage.collectAsState()

                    BubbleUI(
                        isLoading = isLoading,
                        targetLanguage = targetLang,
                        onDrag = { dx, dy ->
                            updateBubblePosition(dx, dy)
                        },
                        onTap = {
                            if (!isLoading) {
                                onTranslateTriggered?.invoke()
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(bubbleView, params)
            OverlayState.isBubbleVisible.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Hides the Floating Bubble.
     */
    fun hideBubble() {
        bubbleView?.let {
            try {
                it.disposeComposition()
                windowManager.removeView(it)
                bubbleLifecycleOwner?.onDestroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubbleView = null
        bubbleLifecycleOwner = null
        OverlayState.isBubbleVisible.value = false
    }

    /**
     * Displays the Draggable Translation Result Window.
     */
    fun showResultWindow() {
        if (resultView != null) return

        resultLifecycleOwner = ServiceViewLifecycleOwner().apply {
            onCreate()
            onStart()
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density
        val widthPx = (345 * density).toInt()     // standard adaptive width
        val heightPx = (480 * density).toInt()    // standard adaptive height

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        resultView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(resultLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(resultLifecycleOwner)
            setViewTreeViewModelStoreOwner(resultLifecycleOwner)
            setContent {
                MyApplicationTheme {
                    val resultText by OverlayState.translationResult.collectAsState()
                    val targetLang by OverlayState.targetLanguage.collectAsState()

                    ResultWindowUI(
                        translatedText = resultText,
                        targetLanguage = targetLang,
                        onDrag = { dx, dy ->
                            updateResultWindowPosition(dx, dy)
                        },
                        onClose = {
                            hideResultWindow()
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(resultView, params)
            OverlayState.isResultVisible.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Hides the Translation Result Window.
     */
    fun hideResultWindow() {
        resultView?.let {
            try {
                it.disposeComposition()
                windowManager.removeView(it)
                resultLifecycleOwner?.onDestroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        resultView = null
        resultLifecycleOwner = null
        OverlayState.isResultVisible.value = false
    }

    /**
     * Updates the position of the bubble following drag gestures.
     */
    private fun updateBubblePosition(dx: Int, dy: Int) {
        val view = bubbleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x += dx
        params.y += dy
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Updates the position of the result dialog following drag gestures.
     */
    private fun updateResultWindowPosition(dx: Int, dy: Int) {
        val view = resultView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x += dx
        params.y += dy
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Cleans up all overlays.
     */
    fun destroy() {
        hideBubble()
        hideResultWindow()
    }
}

// --- Compose UI Components ---

@Composable
fun BubbleUI(
    isLoading: Boolean,
    targetLanguage: String,
    onDrag: (Int, Int) -> Unit,
    onTap: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val pulseScale by animateFloatAsState(
        targetValue = if (isLoading) 1.15f else 1.0f,
        label = "PulseScale"
    )

    Box(
        modifier = Modifier
            .scale(pulseScale)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                )
            }
            .clickable {
                if (!isDragging) {
                    onTap()
                }
            }
            .shadow(12.dp, CircleShape)
            .size(64.dp)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = "Translate Screen Button",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = targetLanguage.take(3).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultWindowUI(
    translatedText: String,
    targetLanguage: String,
    onDrag: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Drag handle header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                        }
                    }
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag Dialog Handle",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 4.dp)
                )
                
                Text(
                    text = "Aura Translate [$targetLanguage]",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Translation Dialog"
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

            // Scrollable Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = translatedText.ifEmpty { "Initiating translation... Tap the bubble icon to scan the screen." },
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

            // Bottom Buttons Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCopied) {
                    Text(
                        text = "Copied!",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Screen Translation", translatedText)
                        clipboard.setPrimaryClip(clip)
                        isCopied = true
                    },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    enabled = translatedText.isNotEmpty() && !translatedText.startsWith("Error:")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy text button",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", fontSize = 14.sp)
                }
            }
        }
    }
}
