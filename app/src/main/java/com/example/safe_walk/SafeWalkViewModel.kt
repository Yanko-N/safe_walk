package com.example.safe_walk

import android.os.CountDownTimer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.objects.DetectedObject
import kotlin.math.*

class SafeWalkViewModel : ViewModel() {
    var isLowLight by mutableStateOf(false)
    var currentLux by mutableStateOf(0f)
    var currentForce by mutableStateOf(0f)
    var hasLightSensor by mutableStateOf(true)
    var detectedObjects by mutableStateOf<List<DetectedObject>>(emptyList())
    var fallDetected by mutableStateOf(false)
    var countdownValue by mutableStateOf(10)
    var isEmergencyCalled by mutableStateOf(false)

    private var countdownTimer: CountDownTimer? = null
    
    // Buffer para evitar flash
    private var lastStateChangeRequestTime = 0L
    private var pendingNewState: Boolean? = null
    private val STABILITY_DELAY = 5000L // 5 segundos de estabilidade necessária

    var onLowLightChanged: ((Boolean) -> Unit)? = null
    var onEmergencyTriggered: (() -> Unit)? = null

    fun updateLightLevel(lux: Float) {
        currentLux = lux
        
        // Se estiver escuro, precisa de mais luz para desligar (25).
        // Se estiver claro, precisa de muito pouca luz para ligar (10).
        val threshold = if (isLowLight) 35.0f else 10.0f
        val isCurrentlyDark = lux < threshold

        if (isCurrentlyDark != isLowLight) {
            val now = System.currentTimeMillis()
            
            // Se mudou de ideia sobre o estado pendente, reinicia o cronómetro
            if (pendingNewState != isCurrentlyDark) {
                pendingNewState = isCurrentlyDark
                lastStateChangeRequestTime = now
            }
            
            // Só aplica a mudança se o estado se mantiver consistente pelo tempo de delay
            if (now - lastStateChangeRequestTime > STABILITY_DELAY) {
                isLowLight = isCurrentlyDark
                pendingNewState = null
                onLowLightChanged?.invoke(isLowLight)
            }
        } else {
            // Se a leitura atual é igual com o estado real, cancelamos qualquer cena
            pendingNewState = null
        }
    }

    fun checkFallDetection(values: FloatArray) {
        val totalForce = sqrt(values[0].pow(2) + values[1].pow(2) + values[2].pow(2))
        currentForce = totalForce

        if (fallDetected || isEmergencyCalled) return
        
        if (totalForce > 30f) {
            if (detectedObjects.isEmpty()) {
                triggerFallAlert()
            }
        }
    }

    private fun triggerFallAlert() {
        fallDetected = true
        countdownValue = 10
        
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownValue = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                if (fallDetected) {
                    isEmergencyCalled = true
                    onEmergencyTriggered?.invoke()
                    fallDetected = false
                }
            }
        }.start()
    }

    fun cancelFall() {
        fallDetected = false
        countdownTimer?.cancel()
    }
    
    fun resetEmergency() {
        isEmergencyCalled = false
    }
}
