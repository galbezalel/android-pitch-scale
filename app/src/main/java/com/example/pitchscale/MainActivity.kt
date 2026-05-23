package com.example.pitchscale

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.File
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pitchscale.service.PitchShifterService
import kotlinx.coroutines.delay
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private var pitchService: PitchShifterService? = null
    private var isBound = false
    private var isServiceRunningState = mutableStateOf(false)
    private var semitonesState = mutableStateOf(0)
    private var amplitudeState = mutableStateOf(0.0f)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PitchShifterService.PitchShifterBinder
            pitchService = binder.getService()
            isBound = true
            isServiceRunningState.value = true
            pitchService?.setSemitoneShift(semitonesState.value)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pitchService = null
            isBound = false
            isServiceRunningState.value = false
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchMediaProjection()
        } else {
            Toast.makeText(this, "Microphone permission is required to capture audio", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, PitchShifterService::class.java).apply {
                putExtra(PitchShifterService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PitchShifterService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceRunningState.value = true
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)


        val logFile = File(getExternalFilesDir(null), "crash_log.txt")
        if (logFile.exists()) {
            try {
                val crashContent = logFile.readText()
                logFile.delete()
                showCrashDialog(crashContent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            PitchScalerTheme {
                PitchScalerDashboard()
            }
        }

        // Start pulling amplitude updates if service is running
        lifecycleScopeTimer()
    }

    private fun lifecycleScopeTimer() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isBound && isServiceRunningState.value) {
                    amplitudeState.value = pitchService?.currentAmplitude ?: 0.0f
                } else {
                    amplitudeState.value = 0.0f
                }
                mainHandler.postDelayed(this, 33) // ~30 fps
            }
        })
    }

    private fun toggleService() {
        if (isServiceRunningState.value) {
            stopPitchService()
        } else {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            launchMediaProjection()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopPitchService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val serviceIntent = Intent(this, PitchShifterService::class.java)
        stopService(serviceIntent)
        isServiceRunningState.value = false
        pitchService = null
        amplitudeState.value = 0f
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    @Composable
    fun PitchScalerDashboard() {
        val isRunning by isServiceRunningState
        val semitones by semitonesState
        val amplitude by amplitudeState

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F12))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = "PITCH SCALER",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = if (isRunning) "Active Background Shifting" else "System Audio Engine Idle",
                    fontSize = 13.sp,
                    color = if (isRunning) Color(0xFF00F2FE) else Color(0x66FFFFFF),
                    fontWeight = FontWeight.Medium
                )
            }

            // Siri Waveform Visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                SiriWaveform(amplitude = amplitude, isRunning = isRunning)
            }

            // Pitch controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                // Displays semitones value
                Text(
                    text = when {
                        semitones > 0 -> "+$semitones st"
                        semitones < 0 -> "$semitones st"
                        else -> "Original Pitch"
                    },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Minus Button
                    CircularControlButton(
                        symbol = "-",
                        enabled = isRunning,
                        onClick = {
                            if (semitonesState.value > -12) {
                                semitonesState.value--
                                pitchService?.setSemitoneShift(semitonesState.value)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(48.dp))

                    // Plus Button
                    CircularControlButton(
                        symbol = "+",
                        enabled = isRunning,
                        onClick = {
                            if (semitonesState.value < 12) {
                                semitonesState.value++
                                pitchService?.setSemitoneShift(semitonesState.value)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Power Toggle Button
                val powerGradient = if (isRunning) {
                    Brush.linearGradient(listOf(Color(0xFFE21143), Color(0xFFFF5252)))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE)))
                }

                Box(
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth(0.7f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(powerGradient)
                        .clickable { toggleService() }
                        .shadow(8.dp, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRunning) "STOP ENGINE" else "START ENGINE",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }

    @Composable
    fun SiriWaveform(amplitude: Float, isRunning: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
        val phase1 by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase1"
        )
        val phase2 by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (-2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase2"
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2

            if (isRunning && amplitude > 0.005f) {
                val colors = listOf(
                    Color(0x2200F2FE), // Neon Blue
                    Color(0x557F00FF), // Violet
                    Color(0x9900F2FE), // Bright Neon Blue
                    Color(0xFFE21143)  // Pulsing Pink/Red
                )
                val phases = listOf(phase1, phase2, phase1 + 1.2f, phase2 - 1.8f)
                // Map amplitude to px heights
                val maxAmpPx = (amplitude * 90.dp.toPx()).coerceAtMost(centerY - 10f)
                val amplitudes = listOf(maxAmpPx * 0.7f, maxAmpPx * 0.5f, maxAmpPx * 1.0f, maxAmpPx * 0.8f)
                val strokeWidths = listOf(1.5.dp, 2.dp, 3.dp, 1.dp)

                for (w in 0 until 4) {
                    val path = Path()
                    path.moveTo(0f, centerY)

                    var x = 0f
                    while (x < width) {
                        // Hann envelope: starts at 0, peaks in middle, ends at 0
                        val envelope = sin(Math.PI * x / width).toFloat()
                        val y = centerY + sin(2 * Math.PI * x / 280f + phases[w]).toFloat() * amplitudes[w] * envelope
                        path.lineTo(x, y)
                        x += 4f
                    }
                    path.lineTo(width, centerY)

                    drawPath(
                        path = path,
                        color = colors[w],
                        style = Stroke(width = strokeWidths[w].toPx())
                    )
                }
            } else {
                // Static zero-line with a neon glow
                drawLine(
                    color = Color(0x334FACFE),
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }

    @Composable
    fun CircularControlButton(
        symbol: String,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val buttonBg = if (enabled) Color(0xFF1E1E24) else Color(0xFF141417)
        val borderBg = if (enabled) Color(0xFF2E2E38) else Color(0xFF1A1A1F)
        val textCol = if (enabled) Color.White else Color(0x33FFFFFF)

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(buttonBg)
                .border(1.dp, borderBg, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                color = textCol
            )
        }
    }

    private fun showCrashDialog(crashContent: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Previous Crash Detected")
            .setMessage(crashContent.take(1500) + if (crashContent.length > 1500) "\n\n... (truncated)" else "")
            .setPositiveButton("Copy & Close") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Crash Log", crashContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

@Composable
fun PitchScalerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00F2FE),
            background = Color(0xFF0F0F12),
            surface = Color(0xFF1E1E24)
        ),
        content = content
    )
}
