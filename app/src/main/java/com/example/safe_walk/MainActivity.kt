package com.example.safe_walk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<SafeWalkViewModel>()
    private lateinit var sensorHandler: SensorHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorHandler = SensorHandler(this, viewModel)
        sensorHandler.start()

        viewModel.onEmergencyTriggered = {
            makeEmergencyCall()
        }

        setContent {
            var showMainApp by remember { mutableStateOf(false) }

            if (!showMainApp) {
                OnboardingScreen(onContinue = { showMainApp = true })
            } else {
                CameraPermissionWrapper {
                    SafeWalkMainScreen(viewModel)
                }
            }
        }
    }

    private fun makeEmergencyCall() {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:112")
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorHandler.stop()
    }

    @Composable
    fun CameraPermissionWrapper(content: @Composable () -> Unit) {
        val context = LocalContext.current
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
            )
        }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasPermission = granted }

        LaunchedEffect(Unit) {
            if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
        }

        if (hasPermission) {
            content()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "A aplicação precisa de acesso à câmara para funcionar.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }

    @Composable
    fun OnboardingScreen(onContinue: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_image),
                contentDescription = "Logo SafeWalk",
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Text(text = "SafeWalk", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Esta App monitoriza quedas através do acelerómetro e utiliza a câmara com IA " +
                        "para detetar objetos no seu caminho",
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onContinue) {
                Text("Começar Monitorização")
            }
        }
    }

    @Composable
    fun SafeWalkMainScreen(viewModel: SafeWalkViewModel) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        var previewWidth by remember { mutableStateOf(1f) }
        var previewHeight by remember { mutableStateOf(1f) }
        var imageWidth by remember { mutableStateOf(1) }
        var imageHeight by remember { mutableStateOf(1) }

        val previewView = remember { PreviewView(context) }
        var camera by remember { mutableStateOf<Camera?>(null) }

        // MediaPlayer para o som personalizado
        val player = remember {
            try {
                MediaPlayer.create(context, R.raw.beep)
            } catch (e: Exception) {
                null
            }
        }

        // Garante que o player é libertado quando a View é destruída
        DisposableEffect(Unit) {
            onDispose {
                player?.release()
            }
        }

        // Toca o som quando objetos são detetados
        LaunchedEffect(viewModel.detectedObjects.isNotEmpty()) {
            while (viewModel.detectedObjects.isNotEmpty()) {
                player?.let {
                    if (!it.isPlaying) {
                        it.seekTo(0)
                        it.start()
                    }
                }
                delay(1000) // Intervalo de 1 segundo
            }
        }

        // Controlar lanterna baseado na luminosidade
        LaunchedEffect(viewModel.isLowLight, camera) {
            camera?.cameraControl?.enableTorch(viewModel.isLowLight)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        previewWidth = coords.size.width.toFloat()
                        previewHeight = coords.size.height.toFloat()
                    }
            ) { view ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also {
                            it.setAnalyzer(
                                Executors.newSingleThreadExecutor(),
                                ObjectDetectorAnalyzer(
                                    onObjectsDetected = { objects -> viewModel.detectedObjects = objects },
                                    onImageSize = { w, h -> imageWidth = w; imageHeight = h },
                                    onBrightnessCalculated = { brightness ->
                                        if (viewModel.currentLux == 0f || !viewModel.hasLightSensor) {
                                            viewModel.updateLightLevel(brightness)
                                        }
                                    }
                                )
                            )
                        }

                    cameraProvider.unbindAll()

                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                }, ContextCompat.getMainExecutor(context))
            }

            DetectionOverlay(
                detectedObjects = viewModel.detectedObjects,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                previewWidth = previewWidth,
                previewHeight = previewHeight
            )

            UIOverlay(viewModel)
        }
    }

    @Composable
    fun DetectionOverlay(
        detectedObjects: List<DetectedObject>,
        imageWidth: Int,
        imageHeight: Int,
        previewWidth: Float,
        previewHeight: Float
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = previewWidth / imageWidth
            val scaleY = previewHeight / imageHeight

            detectedObjects.forEach { obj ->
                val rect = obj.boundingBox
                drawRect(
                    color = Color(0xFFFF6600),
                    topLeft = Offset(rect.left * scaleX, rect.top * scaleY),
                    size = Size(rect.width() * scaleX, rect.height() * scaleY),
                    style = Stroke(width = 4f)
                )
            }
        }

        detectedObjects.forEach { obj ->
            val rect = obj.boundingBox
            val label = obj.labels.firstOrNull()?.text ?: "Objeto"
            val confidence = obj.labels.firstOrNull()?.confidence ?: 0f

            val imageWidthSafe = imageWidth.takeIf { it > 0 } ?: 1
            val imageHeightSafe = imageHeight.takeIf { it > 0 } ?: 1

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = (rect.left.toFloat() / imageWidthSafe * previewWidth).dp,
                        top   = (rect.top.toFloat()  / imageHeightSafe * previewHeight).dp
                    )
            ) {
                Surface(
                    color = Color(0xCCFF6600),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "$label (${"%.0f".format(confidence * 100)}%)",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun UIOverlay(viewModel: SafeWalkViewModel) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            if (viewModel.isLowLight) {
                Badge(
                    containerColor = Color.Yellow,
                    contentColor = Color.Black,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Text("LUZ LIGADA (LOW LIGHT)", modifier = Modifier.padding(4.dp))
                }
            }

            // debuggers
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Luminosidade: ${"%.1f".format(viewModel.currentLux)} lux",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Força G: ${"%.2f".format(viewModel.currentForce)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (viewModel.fallDetected) {
                AlertDialog(
                    onDismissRequest = { /* Não permite fechar clicando fora */ },
                    title = { Text("ALERTA DE QUEDA!", color = Color.Red, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Foi detetada uma possível queda.")
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Chamada de emergência em:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${viewModel.countdownValue}s",
                                style = MaterialTheme.typography.headlineLarge,
                                color = Color.Red
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.cancelFall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                        ) {
                            Text("Estou Bem (Fechar)", color = Color.Black)
                        }
                    }
                )
            }
            
            if (viewModel.isEmergencyCalled) {
                AlertDialog(
                    onDismissRequest = { viewModel.resetEmergency() },
                    title = { Text("EMERGÊNCIA") },
                    text = { Text("A discagem para o 112 foi iniciada.") },
                    confirmButton = {
                        Button(onClick = { viewModel.resetEmergency() }) {
                            Text("OK")
                        }
                    }
                )
            }
        }
    }
}
