package com.interviewmitra.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (recorder == null) startCapture()
        return START_NOT_STICKY
    }
    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("listening", "Interview listening", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "listening").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Interview Mitra is listening…").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(7, notification)
    }
    private fun startCapture() {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400)).also { record ->
            record.startRecording()
            scope.launch {
                val buffer = ByteArray(6400) // 200 ms at 16 kHz PCM16
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) AudioBus.pcm.tryEmit(buffer.copyOf(read))
                }
            }
        }
    }
    override fun onDestroy() { recorder?.run { stop(); release() }; recorder = null; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
