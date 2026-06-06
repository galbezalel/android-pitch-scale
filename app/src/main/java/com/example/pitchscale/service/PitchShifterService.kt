package com.example.pitchscale.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.pitchscale.MainActivity
import com.example.pitchscale.dsp.SoundTouchNative
import kotlin.math.abs

class PitchShifterService : Service() {

    private val binder = PitchShifterBinder()
    
    private val sampleRate = 44100
    private val pitchShifter = SoundTouchNative().apply {
        setSampleRate(sampleRate)
        setChannels(1)
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var dspThread: DspThread? = null
    
    private var mediaSession: MediaSession? = null
    
    private var originalMediaVolume = -1
    private var currentUsage = AudioAttributes.USAGE_ALARM
    @Volatile
    private var requiresTrackRecreation = false
    
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkAndUpdateRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkAndUpdateRouting()
        }
    }
    
    private fun areHeadphonesConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun checkAndUpdateRouting() {
        val hasHeadphones = areHeadphonesConnected()
        val targetUsage = if (hasHeadphones) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_ALARM
        }

        if (targetUsage != currentUsage) {
            currentUsage = targetUsage
            applyEchoHackVolumes(hasHeadphones)
            requiresTrackRecreation = true
        }
    }
    
    private fun applyEchoHackVolumes(hasHeadphones: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (originalMediaVolume == -1) {
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        
        val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetStream = if (hasHeadphones) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_ALARM
        val maxTarget = audioManager.getStreamMaxVolume(targetStream)
        
        val targetVol = if (maxMedia > 0) ((originalMediaVolume.toFloat() / maxMedia.toFloat()) * maxTarget).toInt() else 0
        
        audioManager.setStreamVolume(targetStream, targetVol, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restoreMediaVolume() {
        if (originalMediaVolume != -1) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
            originalMediaVolume = -1
        }
    }

    @Volatile
    private var isRunning = false

    @Volatile
    var currentAmplitude = 0.0f
        private set

    companion object {
        private const val CHANNEL_ID = "pitch_scaler_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    inner class PitchShifterBinder : Binder() {
        fun getService(): PitchShifterService = this@PitchShifterService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && resultData != null) {
            startPitchShifting(resultCode, resultData)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startPitchShifting(resultCode: Int, resultData: Intent) {
        if (isRunning) return
        isRunning = true

        // Create Foreground Notification
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            stopSelf()
            return
        }

        // Setup Audio Capture and Audio Track
        setupAudio()

        // Register audio device listener to handle headphone plug/unplug
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        
        setupMediaSession()

        // Start Processing Thread
        dspThread = DspThread()
        dspThread?.start()
    }

    private fun setupMediaSession() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        mediaSession = MediaSession(this, "PitchShifterSession").apply {
            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(playbackState)

            val currentVol = audioManager.getStreamVolume(if (areHeadphonesConnected()) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_ALARM)

            val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 15, currentVol) {
                override fun onAdjustVolume(direction: Int) {
                    val targetStream = if (areHeadphonesConnected()) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_ALARM
                    audioManager.adjustStreamVolume(targetStream, direction, AudioManager.FLAG_SHOW_UI)
                    // Aggressively keep the media stream muted to prevent Spotify echo
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
            }
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
    }

    private fun setupAudio() {
        val proj = mediaProjection ?: return

        // 1. AudioPlaybackCapture Configuration (Android 10+)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        // 2. AudioRecord
        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        // Determine initial usage and volume
        val hasHeadphones = areHeadphonesConnected()
        currentUsage = if (hasHeadphones) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY else AudioAttributes.USAGE_ALARM
        applyEchoHackVolumes(hasHeadphones)

        audioTrack = createAudioTrack()
    }

    private fun createAudioTrack(): AudioTrack {
        val trackBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(currentUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBufferSize)
            .build()
    }

    fun setSemitoneShift(semitones: Int) {
        pitchShifter.setPitchSemiTones(semitones.toFloat())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pitch Scaler Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the background audio capture and pitch shift engine."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pitch Scaler Active")
            .setContentText("Scaling background playback audio...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopShifting()
    }

    fun stopShifting() {
        isRunning = false
        currentAmplitude = 0f
        
        try {
            dspThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(true)
        pitchShifter.dispose()
        restoreMediaVolume()
    }

    private inner class DspThread : Thread("PitchShifterDspThread") {
        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            val record = audioRecord ?: return
            var track = audioTrack

            val chunkSize = 512
            val recordBuf = ShortArray(chunkSize)
            val playBuf = ShortArray(chunkSize * 4)

            try {
                record.startRecording()
                track?.play()

                while (isRunning) {
                    if (requiresTrackRecreation) {
                        requiresTrackRecreation = false
                        try {
                            track?.stop()
                            track?.release()
                        } catch (e: Exception) {}
                        
                        track = createAudioTrack()
                        audioTrack = track
                        track?.play()
                    }

                    val readResult = record.read(recordBuf, 0, chunkSize)
                    if (readResult <= 0) continue

                    // Calculate amplitude for UI visualizer
                    var maxAmplitude = 0.0f
                    for (i in 0 until readResult) {
                        val sampleVal = abs(recordBuf[i].toFloat() / 32768.0f)
                        if (sampleVal > maxAmplitude) {
                            maxAmplitude = sampleVal
                        }
                    }

                    // Smooth decay for visualizer amplitude
                    currentAmplitude = currentAmplitude * 0.7f + maxAmplitude * 0.3f

                    // Run the SoundTouch Pitch Shifter
                    var received = pitchShifter.process(recordBuf, readResult, playBuf)
                    while (received > 0 && isRunning) {
                        track?.write(playBuf, 0, received)
                        received = pitchShifter.process(ShortArray(0), 0, playBuf)
                    }
                }

                // Flush remaining samples on stop
                var received = pitchShifter.flush(playBuf)
                while (received > 0) {
                    track?.write(playBuf, 0, received)
                    received = pitchShifter.flush(playBuf)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    record.stop()
                } catch (e: Exception) {}
                try {
                    record.release()
                } catch (e: Exception) {}
                try {
                    track?.stop()
                } catch (e: Exception) {}
                try {
                    track?.release()
                } catch (e: Exception) {}
            }
        }
    }
}
