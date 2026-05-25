@file:OptIn(ExperimentalMaterial3Api::class)
package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Launcher for acquiring screen media projection consent
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data
            if (intentData != null) {
                // Permissions verified, launch the overlay projection service
                ScreenCaptureService.startService(this, result.resultCode, intentData)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    "Screen Translator",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        requestProjection = {
                            val intent = mediaProjectionManager.createScreenCaptureIntent()
                            screenCaptureLauncher.launch(intent)
                        },
                        stopProjection = {
                            ScreenCaptureService.stopService(this)
                        }
                    )
                }
            }
        }
    }
}

// --- Context Helper to safely resolve the Activity ---

fun Context.findActivity(): ComponentActivity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// --- Main Composable Dashboard UI ---

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    requestProjection: () -> Unit,
    stopProjection: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    // Observe service and permission state configurations
    val isServiceRunning by ServiceStatus.isRunning.collectAsState()
    val selectedTargetLang by OverlayState.targetLanguage.collectAsState()

    var overlayGranted by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    var accessibilityActive by remember {
        mutableStateOf(PermissionHelper.isAccessibilityServiceEnabled(context, ScreenTranslatorService::class.java))
    }

    // Key API warning state checks
    val isApiKeyConfigured = remember {
        BuildConfig.GEMINI_API_KEY.isNotEmpty() && 
        BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY" && 
        BuildConfig.GEMINI_API_KEY != "GEMINI_API_KEY"
    }

    // Refresh permission statuses on resume (when user returns from system settings)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayGranted = PermissionHelper.hasOverlayPermission(context)
                accessibilityActive = PermissionHelper.isAccessibilityServiceEnabled(context, ScreenTranslatorService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Regular manual poll configuration during recompositions for immediate feedback
    LaunchedEffect(key1 = isServiceRunning) {
        overlayGranted = PermissionHelper.hasOverlayPermission(context)
        accessibilityActive = PermissionHelper.isAccessibilityServiceEnabled(context, ScreenTranslatorService::class.java)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Service Control Active Card
        ServiceControlCard(
            isRunning = isServiceRunning,
            onToggle = { active ->
                if (active) {
                    if (!overlayGranted) {
                        val activity = context.findActivity()
                        if (activity != null) {
                            PermissionHelper.requestOverlayPermission(activity)
                        }
                    } else {
                        requestProjection()
                    }
                } else {
                    stopProjection()
                }
            }
        )

        // 2. Language Picker
        LanguageSelectorBlock(
            selectedLang = selectedTargetLang,
            onSelect = { OverlayState.targetLanguage.value = it }
        )

        // 3. Permission Management Block
        PermissionsBlock(
            overlayGranted = overlayGranted,
            accessibilityActive = accessibilityActive,
            onRequestOverlay = { 
                val activity = context.findActivity()
                if (activity != null) {
                    PermissionHelper.requestOverlayPermission(activity)
                }
            },
            onRequestAccessibility = { PermissionHelper.openAccessibilitySettings(context) }
        )

        // 4. API Key Security Warning Callout
        ApiKeyWarningBlock(isConfigured = isApiKeyConfigured)

        // 5. Instruction Manual Card
        HowToUseCard()
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// --- Compose Sub-Components ---

@Composable
fun ServiceControlCard(
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val cardBg by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(500),
        label = "CardBgColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.PlayArrow else Icons.Filled.Stop,
                        contentDescription = "Service Status Icon",
                        tint = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Translation Overlay",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isRunning) "Active - Floating bubble is on screen" else "Service offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = isRunning,
                onCheckedChange = { onToggle(it) }
            )
        }
    }
}

@Composable
fun LanguageSelectorBlock(
    selectedLang: String,
    onSelect: (String) -> Unit
) {
    val languages = listOf("English", "Spanish", "French", "German", "Japanese", "Chinese", "Korean", "Turkish", "Arabic", "Portuguese", "Italian")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Target Language",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                text = "Translate screen captures and OCR readings into:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp)
            ) {
                items(languages) { lang ->
                    val isSelected = (lang == selectedLang)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(lang) },
                        label = { Text(lang) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionsBlock(
    overlayGranted: Boolean,
    accessibilityActive: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Permissions Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Permission Status Item: Display Over Other Apps
            PermissionItemCard(
                title = "Display Over Other Apps",
                description = "Required to exhibit the floating translation bubble and result windows.",
                isGranted = overlayGranted,
                onConfigure = onRequestOverlay
            )

            // Permission Status Item: Accessibility Service (Optional but helpful context)
            PermissionItemCard(
                title = "Accessibility Screen Reader",
                description = "Enables text structural analysis. Useful as a support overlay background service.",
                isGranted = accessibilityActive,
                onConfigure = onRequestAccessibility,
                isOptional = true
            )
        }
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onConfigure: () -> Unit,
    isOptional: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = "Permission state",
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isOptional) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("Optional", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (!isGranted) {
            Button(
                onClick = onConfigure,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Permission Granted",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ApiKeyWarningBlock(isConfigured: Boolean) {
    val containerBg = if (isConfigured) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0x18EA5B5B)
    val strokeColor = if (isConfigured) MaterialTheme.colorScheme.outlineVariant else Color(0x40EA5B5B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, strokeColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isConfigured) Icons.Default.VpnKey else Icons.Default.Warning,
                    contentDescription = "Key Status Icon",
                    tint = if (isConfigured) MaterialTheme.colorScheme.primary else Color(0xFFD32F2F),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isConfigured) "Gemini Connection Secure" else "API Key Configuration Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isConfigured) MaterialTheme.colorScheme.onSurface else Color(0xFFC62828)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isConfigured) {
                    "Your 'GEMINI_API_KEY' has been successfully detected and compiled via BuildConfig. Real-time screen translation functions are ready."
                } else {
                    "A valid 'GEMINI_API_KEY' environment variable was not found in BuildConfig.\n\n" +
                    "To fix this, please enter your Gemini API Key in the Secrets panel in the Google AI Studio UI. The app will automatically inject it into the local .env properties config."
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (isConfigured) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF8E1B1B)
            )

            if (!isConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "*Note on apk security: Do not share the built APK publicly. Android binaries can be decompiled, which may expose the embedded prototype values.",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = Color(0xFFAA4B4B),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun HowToUseCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "How to Translate Screen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            InstructionRow(step = "1", text = "Ensure the 'Display Over Other Apps' permission status is GRANTED.")
            InstructionRow(step = "2", text = "Toggle on the 'Translation Overlay' switch at the top and click 'Start Now' on the permission prompt.")
            InstructionRow(step = "3", text = "Navigate to any screen/app containing foreign text (e.g., a game, PDF, or page) and tap the floating Bubble Translate icon.")
            InstructionRow(step = "4", text = "The bubble will dynamically spin while processing, and then launch the result dialog showing translated target contents. Tap close to dismiss.")
        }
    }
}

@Composable
fun InstructionRow(step: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
