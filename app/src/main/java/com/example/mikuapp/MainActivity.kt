package com.example.mikuapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val currentExpression by viewModel.currentExpression.collectAsStateWithLifecycle()
    val isTtsReady by viewModel.isTtsReady.collectAsStateWithLifecycle()
    val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val ttsError by viewModel.ttsError.collectAsStateWithLifecycle()
    val debugGeminiMode by viewModel.debugGeminiMode.collectAsStateWithLifecycle()
    val useGeminiVoice by viewModel.useGeminiVoice.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()

    var debugPanelOpen by remember { mutableStateOf(false) }

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
            viewModel.showStatus("Sin la cámara no puede ver, causa")
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
                    listOf(Color(0xFF03100E), Color(0xFF08251F), Color(0xFF03100E)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
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

            Spacer(Modifier.height(28.dp))

            CaptionPanel(
                caption = caption,
                userSpeech = userSpeech,
                connected = connected,
                isSpeaking = isSpeaking,
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
                    .padding(12.dp)
                    .size(width = 190.dp, height = 142.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, MikuCyan.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .background(Color.Black),
            ) {
                CameraOverlay(onFrame = viewModel::sendCameraFrame)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC04211F)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Miku ve esto",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
            statusText = "CARGANDO VOZ"
            statusColor = MikuMuted
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTED && isSpeaking -> {
            statusText = "CANTANDO"
            statusColor = MikuCyan
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTED -> {
            statusText = "EN LÍNEA"
            statusColor = MikuCyan
        }

        connectionState == GeminiLiveClient.ConnectionState.CONNECTING -> {
            statusText = "CONECTANDO"
            statusColor = Color(0xFFFFC145)
        }

        else -> {
            statusText = "DESCONECTADA"
            statusColor = MikuMuted
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "HATSUNE MIKU",
                color = MikuCyan,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = "Asistente vocal en tiempo real",
                color = MikuMuted,
                fontSize = 12.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MikuSurfaceVariant)
                    .border(1.dp, MikuCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .clickable { onDebugClick() },
            ) {
                Text(
                    text = "DBG",
                    color = MikuCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
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
        targetValue = if (isSpeaking) 1f + amplitude * 0.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "mikuScale",
    )

    val expressionScale = remember { Animatable(1f) }
    LaunchedEffect(expression) {
        if (expression != null) {
            expressionScale.snapTo(0.72f)
            expressionScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isSpeaking -> 0.3f + amplitude * 0.5f
            isConnected -> 0.25f
            else -> 0.08f
        },
        label = "mikuGlow",
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(300.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MikuCyan.copy(alpha = glowAlpha),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = center,
            )
        }

        Box(
            modifier = Modifier
                .size(230.dp)
                .scale(scale * expressionScale.value),
        ) {
            Image(
                painter = painterResource(
                    id = expression?.drawableRes ?: R.drawable.miku_expr_dormida,
                ),
                contentDescription = "Hatsune Miku",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(3.dp, MikuCyan.copy(alpha = 0.7f), CircleShape),
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
    isInitializing: Boolean,
    isTtsReady: Boolean,
    ttsError: String?,
    isListening: Boolean,
) {
    val displayText = when {
        ttsError != null -> ttsError
        isInitializing -> "Cargando la voz de Miku..."
        !connected -> "Conecta para hablar con Miku"
        caption.isNotBlank() -> caption
        else -> "Te escucho..."
    }

    val label = when {
        ttsError != null -> "ERROR DE VOZ"
        isInitializing -> "PREPARANDO"
        isSpeaking -> "MIKU DICE"
        connected && isListening -> "ESCUCHANDO"
        connected -> "MICRÓFONO APAGADO"
        isTtsReady -> "EN ESPERA"
        else -> "VOZ NO DISPONIBLE"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp),
        shape = RoundedCornerShape(20.dp),
        color = MikuSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MikuCyan.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = label,
                color = if (ttsError != null) Color(0xFFFF8A80) else MikuCyan.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            if (userSpeech.isNotBlank()) {
                Text(
                    text = "VOS: $userSpeech",
                    color = MikuMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = displayText,
                color = MikuOnSurface,
                fontSize = 17.sp,
                lineHeight = 24.sp,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleControl(
            glyph = if (connected) "■" else "▶",
            label = if (connected) "Desconectar" else "Conectar",
            active = connected,
            enabled = connected || canConnect,
            onClick = onConnectClick,
        )
        CircleControl(
            glyph = if (isListening) "●" else "○",
            label = if (isListening) "Micrófono activo" else "Micrófono apagado",
            active = isListening,
            enabled = connected,
            onClick = onMicClick,
        )
        CircleControl(
            glyph = "◉",
            label = if (isCameraActive) "Cámara activa" else "Cámara apagada",
            active = isCameraActive,
            enabled = connected,
            onClick = onCameraClick,
        )
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
        !enabled -> MikuMuted.copy(alpha = 0.5f)
        active -> Color(0xFF04211F)
        else -> MikuCyanLight
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(background)
                .border(
                    width = 1.dp,
                    color = MikuCyan.copy(alpha = if (enabled) 0.6f else 0.15f),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = glyphColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = if (enabled) MikuOnSurface else MikuMuted,
            fontSize = 12.sp,
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

    LaunchedEffect(previewView) {
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
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            MikuLog.d("Camara", "Cámara enlazada: enviando frames de video a Miku")
        } catch (t: Throwable) {
            MikuLog.d("Camara", "ERROR activando cámara: ${t.message}")
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
