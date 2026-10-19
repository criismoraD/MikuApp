package com.example.mikuapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Size
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mikuapp.agent.AgentState
import com.example.mikuapp.agent.MikuAccessibilityService
import com.example.mikuapp.camera.CameraVideoAnalyzer
import com.example.mikuapp.expressions.MikuExpression
import com.example.mikuapp.network.GeminiLiveClient
import com.example.mikuapp.ui.theme.MikuAppTheme
import com.example.mikuapp.util.MikuLog
import com.example.mikuapp.ui.theme.MikuBackground
import com.example.mikuapp.ui.theme.MikuCyan
import com.example.mikuapp.ui.theme.MikuCyanLight
import com.example.mikuapp.ui.theme.MikuMuted
import com.example.mikuapp.ui.theme.MikuOnSurface
import com.example.mikuapp.ui.theme.MikuSurface
import com.example.mikuapp.ui.theme.MikuSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MikuAppTheme {
                MikuScreen()
            }
        }
    }
}

@Composable
fun MikuScreen(viewModel: MikuViewModel = viewModel()) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val amplitude by viewModel.audioAmplitude.collectAsStateWithLifecycle()
    val caption by viewModel.caption.collectAsStateWithLifecycle()
    val userSpeech by viewModel.userSpeech.collectAsStateWithLifecycle()
    val isCameraActive by viewModel.isCameraActive.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.cameraLensFront.collectAsStateWithLifecycle()
    val currentExpression by viewModel.currentExpression.collectAsStateWithLifecycle()
    val isTtsReady by viewModel.isTtsReady.collectAsStateWithLifecycle()
    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val ttsError by viewModel.ttsError.collectAsStateWithLifecycle()
    val debugGeminiMode by viewModel.debugGeminiMode.collectAsStateWithLifecycle()
    val useGeminiVoice by viewModel.useGeminiVoice.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val notepadVisible by viewModel.notepadVisible.collectAsStateWithLifecycle()
    val notepadContent by viewModel.notepadContent.collectAsStateWithLifecycle()
    val notepadTitle by viewModel.notepadTitle.collectAsStateWithLifecycle()
    val youtubeUrl by viewModel.youtubeUrl.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()

    var debugPanelOpen by remember { mutableStateOf(false) }

    var accessibilityEnabled by remember {
        mutableStateOf(MikuAccessibilityService.isServiceEnabled(context))
    }
    val activityLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(activityLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = MikuAccessibilityService.isServiceEnabled(context)
            }
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            viewModel.connect(BuildConfig.GEMINI_API_KEY)
        } else {
            viewModel.showStatus("Sin el micrófono no puedo escucharte")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            viewModel.toggleCamera()
        } else {
            viewModel.showStatus("Sin la cámara me ahorro el trauma de verte")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeTts()
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4000)
            viewModel.clearStatusMessage()
        }
    }

    val connected = connectionState == GeminiLiveClient.ConnectionState.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF020B0A), Color(0xFF07221E), Color(0xFF020908)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MikuHeader(
                connectionState = connectionState,
                isInitializing = isInitializing,
                isSpeaking = isSpeaking,
                onDebugClick = { debugPanelOpen = true },
            )

            Spacer(Modifier.weight(1f))

            MikuAvatar(
                amplitude = amplitude,
                isSpeaking = isSpeaking,
                isConnected = connected,
                expression = currentExpression,
            )

            Spacer(Modifier.height(24.dp))

            CaptionPanel(
                caption = caption,
                userSpeech = userSpeech,
                connected = connected,
                isSpeaking = isSpeaking,
                amplitude = amplitude,
                isInitializing = isInitializing,
                isTtsReady = isTtsReady,
                ttsError = ttsError,
                isListening = isListening,
            )

            Spacer(Modifier.weight(1f))

            ControlPanel(
                connected = connected,
                isListening = isListening,
                isCameraActive = isCameraActive,
                canConnect = !isInitializing,
                onConnectClick = {
                    when {
                        connected -> viewModel.disconnect()
                        !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else -> viewModel.connect(BuildConfig.GEMINI_API_KEY)
                    }
                },
                onMicClick = {
                    viewModel.toggleMicrophone()
                },
                onCameraClick = {
                    when {
                        isCameraActive -> viewModel.toggleCamera()
                        !hasCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        else -> viewModel.toggleCamera()
                    }
                },
            )

            statusMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                StatusBanner(message = message)
            }
        }

        if (isCameraActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp, end = 20.dp)
                    .size(width = 176.dp, height = 132.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, MikuCyan.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                    .background(Color.Black),
            ) {
                CameraOverlay(
                    onFrame = viewModel::sendCameraFrame,
                    isFront = isFrontCamera,
                    onFlip = viewModel::flipCameraLens,
                )
                Text(
                    text = if (isFrontCamera) "FRONTAL" else "TRASERA",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x99031B18))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC031B18))
                        .clickable { viewModel.flipCameraLens() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⇄",
                        color = Color(0xFF39C5BB),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xD9031B18)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF2A85)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "MIKU VISION",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (debugPanelOpen) {
            DebugPanel(
                logEntries = logEntries,
                silenceVoice = debugGeminiMode,
                useGeminiVoice = useGeminiVoice,
                onSilenceVoiceChange = viewModel::setDebugGeminiMode,
                onUseGeminiVoiceChange = viewModel::setUseGeminiVoice,
                onTestVoice = viewModel::testVoice,
                onTestGemini = viewModel::testGemini,
                onShareLog = {
                    shareLog(context, onError = viewModel::showStatus)
                },
                onClearLog = viewModel::clearLog,
                onClose = { debugPanelOpen = false },
                agentState = agentState,
                accessibilityEnabled = accessibilityEnabled,
                onStartAgent = viewModel::startAgentTask,
                onStopAgent = viewModel::stopAgentTask,
                onOpenAccessibility = {
                    MikuAccessibilityService.openAccessibilitySettings(context)
                },
            )
        }

        if (notepadVisible) {
            MikuNotepadOverlay(
                title = notepadTitle,
                content = notepadContent,
                onContentChange = viewModel::setNotepadContent,
                onClear = viewModel::clearNotepad,
                onClose = viewModel::closeNotepad,
            )
        }

        youtubeUrl?.let { url ->
            MikuYouTubeOverlay(
                url = url,
                onClose = viewModel::closeYoutubeWindow,
                onScreenFrame = viewModel::sendScreenFrame,
            )
        }

        if (agentState.running || agentState.finalMessage != null) {
            ArtemisControlOverlay(
                state = agentState,
                accessibilityEnabled = accessibilityEnabled,
                onStop = viewModel::stopAgentTask,
                onClose = viewModel::clearAgentResult,
                onOpenAccessibility = {
                    MikuAccessibilityService.openAccessibilitySettings(context)
                },
            )
        }
    }
}

@Composable
private fun MikuHeader(
    connectionState: GeminiLiveClient.ConnectionState,
    isInitializing: Boolean,
    isSpeaking: Boolean,
    onDebugClick: () -> Unit,
) {
    val statusText: String
    val statusColor: Color
    when {
        isInitializing -> {
            statusText = "Sincronizando voz"
            statusColor = MikuMuted
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTED && isSpeaking -> {
            statusText = "Hablando"
            statusColor = Color(0xFFFF2A85)
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTED -> {
            statusText = "En línea"
            statusColor = MikuCyan
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTING -> {
            statusText = "Conectando"
            statusColor = Color(0xFFFFC145)
        }

        else -> {
            statusText = "Desconectada"
            statusColor = MikuMuted
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "Hatsune Miku",
                    color = MikuOnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "V ${BuildConfig.VERSION_NAME}",
                    color = MikuCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MikuSurface.copy(alpha = 0.75f))
                .border(1.dp, MikuCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .clickable { onDebugClick() },
        ) {
            Text(
                text = "Diagnóstico",
                color = MikuCyanLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun MikuAvatar(
    amplitude: Float,
    isSpeaking: Boolean,
    isConnected: Boolean,
    expression: MikuExpression?,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSpeaking) 1f + amplitude * 0.14f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "mikuScale",
    )

    val expressionScale = remember { Animatable(1f) }
    LaunchedEffect(expression) {
        if (expression != null) {
            expressionScale.snapTo(0.86f)
            expressionScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val transicionRespiracion = rememberInfiniteTransition(label = "Respiracion_Miku")
    val escalaRespiracionX by transicionRespiracion.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.038f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Escala_Respiracion_X",
    )
    val escalaRespiracionY by transicionRespiracion.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.054f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Escala_Respiracion_Y",
    )
    val desplazamientoRespiracionY by transicionRespiracion.animateFloat(
        initialValue = 3f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Desplazamiento_Respiracion_Y",
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isSpeaking -> 0.32f + amplitude * 0.45f
            isConnected -> 0.22f
            else -> 0.10f
        },
        label = "mikuGlow",
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(284.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MikuCyan.copy(alpha = glowAlpha),
                        Color(0xFFFF2A85).copy(alpha = if (isSpeaking) glowAlpha * 0.35f else 0.03f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = (size.minDimension / 2f) * escalaRespiracionX,
                center = center,
            )
            drawCircle(
                color = MikuCyan.copy(alpha = if (isSpeaking) 0.50f else 0.20f),
                radius = (size.minDimension / 2f) * (0.84f + amplitude * 0.10f),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx()),
            )
            drawCircle(
                color = Color(0xFFFF2A85).copy(alpha = if (isSpeaking) 0.42f else 0.10f),
                radius = (size.minDimension / 2f) * (0.91f + amplitude * 0.12f),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(scale * expressionScale.value)
                .clip(CircleShape)
                .border(2.5.dp, MikuCyan.copy(alpha = 0.88f), CircleShape),
        ) {
            Image(
                painter = painterResource(
                    id = expression?.drawableRes ?: R.drawable.miku_expr_dormida,
                ),
                contentDescription = "Hatsune Miku",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = escalaRespiracionX
                        scaleY = escalaRespiracionY
                        translationY = desplazamientoRespiracionY
                    },
            )
        }
    }
}

@Composable
private fun CaptionPanel(
    caption: String,
    userSpeech: String,
    connected: Boolean,
    isSpeaking: Boolean,
    amplitude: Float,
    isInitializing: Boolean,
    isTtsReady: Boolean,
    ttsError: String?,
    isListening: Boolean,
) {
    val displayText = when {
        isInitializing -> "Sincronizando voz de Miku..."
        !connected && caption.isBlank() -> "Conecta para conversar en tiempo real con Miku."
        caption.isNotBlank() -> caption
        else -> "Habla de una vez, no tengo toda tu corta vida..."
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        shape = RoundedCornerShape(22.dp),
        color = MikuSurface.copy(alpha = 0.90f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSpeaking) MikuCyan.copy(alpha = 0.75f) else MikuCyan.copy(alpha = 0.24f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (userSpeech.isNotBlank()) {
                Text(
                    text = "Tú: $userSpeech",
                    color = MikuMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MikuCyan.copy(alpha = 0.14f)),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = displayText,
                color = MikuOnSurface,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 23.sp,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun ControlPanel(
    connected: Boolean,
    isListening: Boolean,
    isCameraActive: Boolean,
    canConnect: Boolean,
    onConnectClick: () -> Unit,
    onMicClick: () -> Unit,
    onCameraClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MikuSurface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.20f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleControl(
                glyph = if (connected) "■" else "▶",
                label = if (connected) "Cortar" else "Conectar",
                active = connected,
                enabled = connected || canConnect,
                onClick = onConnectClick,
            )
            CircleControl(
                glyph = if (isListening) "●" else "○",
                label = "Micrófono",
                active = isListening,
                enabled = connected,
                onClick = onMicClick,
            )
            CircleControl(
                glyph = "◉",
                label = "Cámara",
                active = isCameraActive,
                enabled = connected,
                onClick = onCameraClick,
            )
        }
    }
}

@Composable
private fun CircleControl(
    glyph: String,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> MikuSurface
        active -> MikuCyan
        else -> MikuSurfaceVariant
    }
    val glyphColor = when {
        !enabled -> MikuMuted.copy(alpha = 0.45f)
        active -> Color(0xFF031815)
        else -> MikuCyanLight
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(background)
                .border(
                    width = if (active) 2.dp else 1.dp,
                    color = if (active) MikuCyanLight else MikuCyan.copy(alpha = if (enabled) 0.55f else 0.15f),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = glyphColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (enabled) MikuOnSurface else MikuMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MikuBackground.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.4f)),
    ) {
        Text(
            text = message,
            color = MikuOnSurface,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CameraOverlay(
    onFrame: (ByteArray) -> Unit,
    isFront: Boolean,
    onFlip: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(previewView, isFront) {
        try {
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    it.setAnalyzer(analyzerExecutor, CameraVideoAnalyzer(onFrame))
                }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            MikuLog.d("Camara", "Cámara ${if (isFront) "frontal" else "trasera"} enlazada: enviando frames de video a Miku")
        } catch (t: Throwable) {
            MikuLog.d("Camara", "ERROR activando cámara ${if (isFront) "frontal" else "trasera"}: ${t.message}")
        }
    }

    DisposableEffect(previewView) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            analyzerExecutor.shutdown()
        }
    }
}

@Composable
private fun DebugPanel(
    logEntries: List<MikuLog.Entry>,
    silenceVoice: Boolean,
    useGeminiVoice: Boolean,
    onSilenceVoiceChange: (Boolean) -> Unit,
    onUseGeminiVoiceChange: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    onTestGemini: () -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onClose: () -> Unit,
    agentState: AgentState,
    accessibilityEnabled: Boolean,
    onStartAgent: (String) -> Unit,
    onStopAgent: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.scrollToItem(logEntries.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF503100E))
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PANEL DEBUG",
                    color = MikuCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                DebugActionButton(label = "Cerrar", onClick = onClose)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "LOG (${logEntries.size} entradas)",
                color = MikuMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(6.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF02100D),
                border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.3f)),
            ) {
                if (logEntries.isEmpty()) {
                    Text(
                        text = "Sin entradas todavía",
                        color = MikuMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        items(logEntries) { entry ->
                            LogLine(entry)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DebugActionButton(
                    label = "Probar voz de Miku",
                    onClick = onTestVoice,
                    modifier = Modifier.weight(1f),
                )
                DebugActionButton(
                    label = "Probar Gemini",
                    onClick = onTestGemini,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DebugActionButton(label = "Compartir log", onClick = onShareLog)
                DebugActionButton(label = "Limpiar", onClick = onClearLog)
            }

            Spacer(Modifier.height(4.dp))

            ArtemisDebugSection(
                agentState = agentState,
                accessibilityEnabled = accessibilityEnabled,
                onStartAgent = onStartAgent,
                onStopAgent = onStopAgent,
                onOpenAccessibility = onOpenAccessibility,
            )

            DebugSwitchRow(
                label = "Silenciar voz (solo texto)",
                checked = silenceVoice,
                onCheckedChange = onSilenceVoiceChange,
            )

            DebugSwitchRow(
                label = "Voz de Gemini (en vez de la de Miku)",
                checked = useGeminiVoice,
                onCheckedChange = onUseGeminiVoiceChange,
            )
        }
    }
}

@Composable
private fun DebugSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MikuOnSurface,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04211F),
                checkedTrackColor = MikuCyan,
            ),
        )
    }
}

@Composable
private fun DebugActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MikuSurfaceVariant,
        border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.5f)),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
    ) {
        Text(
            text = label,
            color = MikuCyanLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LogLine(entry: MikuLog.Entry) {
    val color = when {
        entry.message.contains("ERROR") -> Color(0xFFFF8A80)
        entry.tag == "TTS" -> Color(0xFF88E0B0)
        entry.tag == "Frase" -> Color(0xFFFFE082)
        entry.tag == "Mic" -> Color(0xFF9FC6FF)
        entry.tag == "Miku" -> Color(0xFFFFB4D8)
        else -> MikuMuted
    }
    Text(
        text = "${entry.time}  ${entry.tag}  ${entry.message}",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private fun shareLog(context: android.content.Context, onError: (String) -> Unit) {
    runCatching {
        MikuLog.d("Log", "Compartiendo log...")
        val file = MikuLog.saveToCache(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MikuApp - Log de depuración")
            putExtra(Intent.EXTRA_TEXT, MikuLog.snapshotText().takeLast(8000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir log de Miku"))
    }.onFailure { error ->
        onError(error.message ?: "No se pudo compartir el log")
    }
}

@Composable
private fun MikuNotepadOverlay(
    title: String,
    content: String,
    onContentChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC010807))
            .systemBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF041915)),
            border = BorderStroke(1.5.dp, MikuCyan.copy(alpha = 0.6f)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Barra de título retro
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF092923))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "📄", fontSize = 16.sp)
                        Text(
                            text = title,
                            color = MikuOnSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DebugActionButton(
                            label = "Copiar",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Miku Notas", content)
                                clipboard?.setPrimaryClip(clip)
                            },
                        )
                        DebugActionButton(label = "Limpiar", onClick = onClear)
                        DebugActionButton(label = "✕", onClick = onClose)
                    }
                }

                // Menú simulado
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF061E1A))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    listOf("Archivo", "Edición", "Formato", "Ver", "Ayuda").forEach { item ->
                        Text(
                            text = item,
                            color = MikuMuted,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Área de texto
                BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF031210))
                        .padding(14.dp),
                    textStyle = TextStyle(
                        color = MikuOnSurface,
                        fontSize = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                    ),
                    cursorBrush = SolidColor(MikuCyan),
                    decorationBox = { innerTextField ->
                        if (content.isEmpty()) {
                            Text(
                                text = "El Bloc de Notas de Miku está listo...\nPídele a Miku que redacte algo o escribe aquí directamente.",
                                color = MikuMuted.copy(alpha = 0.5f),
                                fontSize = 13.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        innerTextField()
                    },
                )

                // Barra de estado
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF061E1A))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${content.length} caracteres | ${content.lines().size} líneas",
                        color = MikuMuted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "Redactado por Hatsune Miku",
                        color = MikuCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MikuYouTubeOverlay(
    url: String,
    onClose: () -> Unit,
    onScreenFrame: (ByteArray) -> Unit = {},
) {
    val context = LocalContext.current
    val webView = remember(url) {
        android.webkit.WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = android.webkit.WebChromeClient()
            webViewClient = android.webkit.WebViewClient()
            loadUrl(url)
        }
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    // Compartir la pantalla con Gemini: capturar el WebView como frames de video
    LaunchedEffect(webView) {
        while (true) {
            delay(1_500)
            try {
                if (webView.width > 0 && webView.height > 0) {
                    val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                    bitmap.recycle()
                    onScreenFrame(stream.toByteArray())
                }
            } catch (_: Exception) {
            }
        }
    }

    val windowLabel = when {
        url.contains("youtube") -> "YouTube dentro de Miku"
        else -> "Ventana interna de Miku"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6010807))
            .systemBarsPadding()
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF092923), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "▶", fontSize = 14.sp, color = MikuCyan)
                Text(
                    text = windowLabel,
                    color = MikuOnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            DebugActionButton(label = "✕ Cerrar", onClick = onClose)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF041915)),
            border = BorderStroke(1.5.dp, MikuCyan.copy(alpha = 0.6f)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ArtemisDebugSection(
    agentState: AgentState,
    accessibilityEnabled: Boolean,
    onStartAgent: (String) -> Unit,
    onStopAgent: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    var goal by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AGENTE ARTEMIS � CONTROL TOTAL",
                color = MikuCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (accessibilityEnabled) MikuCyan else Color(0xFFFF8A80),
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (accessibilityEnabled) "Activo" else "Inactivo",
                color = if (accessibilityEnabled) MikuCyanLight else Color(0xFFFF8A80),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(6.dp))

        if (!accessibilityEnabled) {
            DebugActionButton(
                label = "Activar accesibilidad",
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF031210),
            border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = goal,
                onValueChange = { goal = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    color = MikuOnSurface,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(MikuCyan),
                maxLines = 2,
                decorationBox = { innerTextField ->
                    if (goal.isEmpty()) {
                        Text(
                            text = "Tarea para el tel�fono: abre WhatsApp y escribe...",
                            color = MikuMuted.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                    innerTextField()
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugActionButton(
                label = if (agentState.running) {
                    "En curso (${agentState.step}/${agentState.maxSteps})"
                } else {
                    "Iniciar control"
                },
                onClick = {
                    if (!agentState.running && goal.isNotBlank()) {
                        onStartAgent(goal.trim())
                        goal = ""
                    }
                },
                modifier = Modifier.weight(1f),
            )
            DebugActionButton(
                label = "DETENER",
                onClick = onStopAgent,
                modifier = Modifier.weight(1f),
            )
        }

        if (agentState.running && agentState.lastAction.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Paso ${agentState.step}/${agentState.maxSteps} � ${agentState.lastAction}",
                color = MikuMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        agentState.finalMessage?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                color = if (agentState.success == true) MikuCyanLight else Color(0xFFFF8A80),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ArtemisControlOverlay(
    state: AgentState,
    accessibilityEnabled: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0010807))
            .systemBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF041915)),
            border = BorderStroke(
                1.5.dp,
                if (state.running) Color(0xFFFF2A85) else MikuCyan.copy(alpha = 0.7f),
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "?", color = Color(0xFFFF2A85), fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ARTEMIS � CONTROL TOTAL",
                        color = MikuCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!state.running) {
                        DebugActionButton(label = "?", onClick = onClose)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = when {
                        state.running -> "CONTROLANDO EL TEL�FONO"
                        state.success == true -> "TAREA COMPLETADA"
                        state.success == false -> "TAREA FALLIDA"
                        else -> "SIN TAREA ACTIVA"
                    },
                    color = when {
                        state.running -> Color(0xFFFF2A85)
                        state.success == true -> MikuCyanLight
                        else -> Color(0xFFFF8A80)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                if (state.goal.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Objetivo: ${state.goal}",
                        color = MikuOnSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }

                if (state.running) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Paso ${state.step}/${state.maxSteps}",
                        color = MikuCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.lastAction.isNotBlank()) {
                        Text(
                            text = "�ltima acci�n: ${state.lastAction}",
                            color = MikuMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    if (state.lastResult.isNotBlank()) {
                        Text(
                            text = state.lastResult,
                            color = MikuMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }

                state.finalMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        color = MikuOnSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }

                if (!accessibilityEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "El servicio de accesibilidad no est� activo.",
                        color = Color(0xFFFFC145),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    DebugActionButton(
                        label = "Abrir ajustes de accesibilidad",
                        onClick = onOpenAccessibility,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (state.running) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFF2A85).copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color(0xFFFF2A85)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onStop() },
                    ) {
                        Text(
                            text = "DETENER CONTROL",
                            color = Color(0xFFFF8AB8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                        )
                    }
                } else {
                    DebugActionButton(
                        label = "Cerrar",
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
