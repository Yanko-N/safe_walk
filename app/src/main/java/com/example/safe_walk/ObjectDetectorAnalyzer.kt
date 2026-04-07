package com.example.safe_walk

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ObjectDetectorAnalyzer(
    private val onObjectsDetected: (List<DetectedObject>) -> Unit,
    private val onImageSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onBrightnessCalculated: (Float) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()
        .build()

    private val detector = ObjectDetection.getClient(options)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }

        onImageSize(imageProxy.width, imageProxy.height)

        // Calcular brilho da imagem manualmente qunao n temos sensor de luz
        calculateBrightness(imageProxy)

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { objects -> onObjectsDetected(objects) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun calculateBrightness(imageProxy: ImageProxy) {
        val plane = imageProxy.planes[0] // Plano Y luz
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        var totalLuminance = 0L
        for (i in data.indices step 10) { // passar 10 em 10 para ser mais rapido e n ser pixel a pixel
            totalLuminance += data[i].toInt() and 0xFF
        }

        val avgLuminance = totalLuminance.toFloat() / (data.size / 10f)
        // Normalizar para uma escala aproximada de lux
        onBrightnessCalculated(avgLuminance)
    }
}
