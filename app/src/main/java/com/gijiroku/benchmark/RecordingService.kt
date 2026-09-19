package com.gijiroku.benchmark

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that prioritizes authenticated encrypted PCM recording. */
class RecordingService : Service() {

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pauseMonitor = Object()
    private var recordThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (running.get()) return
        if (AiProcessingState.isProcessing.value) {
            RecordingState.failureMessage.value = AppLanguage.text("AI処理中は録音を開始できません", "Cannot start recording during AI processing")
            RecorderWidgetProvider.updateAll(this)
            stopSelf()
            return
        }
        running.set(true)
        paused.set(false)
        RecordingState.reset()
        RecordingState.isRecording.value = true
        startForegroundCompat(buildNotification(paused = false))

        val recordingStore = RecordingStore(this)
        val reference = try {
            recordingStore.beginRecording()
        } catch (error: Throwable) {
            RecordingState.failureMessage.value = AppLanguage.text("暗号鍵を作成できず、録音を開始できませんでした", "Could not create encryption keys or start recording")
            RecordingState.isRecording.value = false
            running.set(false)
            RecorderWidgetProvider.updateAll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        RecordingState.lastRecording.value = reference
        RecorderWidgetProvider.updateAll(this)

        recordThread = Thread {
            runRecordingLoop(reference, recordingStore)
        }.also { it.start() }
    }

    private fun runRecordingLoop(
        reference: RecordingReference,
        recordingStore: RecordingStore
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            RecordingState.isRecording.value = false
            RecordingState.isPaused.value = false
            RecordingState.failureMessage.value = AppLanguage.text("マイク権限がないため録音できません", "Cannot record without microphone permission")
            RecorderWidgetProvider.updateAll(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it <= 0) SAMPLE_RATE * 2 else it }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        val buf = ShortArray(minBuf)
        var capturedSamples = 0L
        var lastWidgetElapsedSecond = -1L
        var microphoneActive = false
        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            MeetingKeyManager(this).useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                EncryptedAudioWriter(
                    file = java.io.File(reference.encryptedFilePath),
                    key = key,
                    metadata = EncryptedAudioMetadata(sampleRate = SAMPLE_RATE, channels = 1)
                ).use { writer ->
                    audioRecord.startRecording()
                    microphoneActive = true
                    while (running.get()) {
                        if (paused.get()) {
                            if (microphoneActive) {
                                audioRecord.stop()
                                microphoneActive = false
                            }
                            synchronized(pauseMonitor) {
                                while (running.get() && paused.get()) pauseMonitor.wait()
                            }
                            continue
                        }
                        if (!microphoneActive) {
                            audioRecord.startRecording()
                            microphoneActive = true
                        }
                        val n = audioRecord.read(buf, 0, buf.size)
                        if (n > 0) {
                            writer.appendPcm16(buf, n)
                            capturedSamples += n
                            val elapsedMs = capturedSamples * 1000L / SAMPLE_RATE
                            RecordingState.elapsedMs.value = elapsedMs
                            val elapsedSecond = elapsedMs / 1_000L
                            if (elapsedSecond != lastWidgetElapsedSecond) {
                                lastWidgetElapsedSecond = elapsedSecond
                                RecorderWidgetProvider.updateAll(this)
                            }
                            var peak = 0
                            for (index in 0 until n) peak = maxOf(peak, kotlin.math.abs(buf[index].toInt()))
                            RecordingState.inputPeak.value = peak / 32768f
                        } else if (n < 0) {
                            throw IllegalStateException("AudioRecord read failed: $n")
                        }
                    }
                }
            }
            val durationMs = capturedSamples * 1000L / SAMPLE_RATE
            RecordingState.lastRecording.value = recordingStore.markFinalized(reference, durationMs)
        } catch (error: Throwable) {
            RecordingState.failureMessage.value = AppLanguage.text("録音が中断されました。保存済み部分を復旧できます", "Recording interrupted. The saved portion can be recovered.")
            RecordingState.lastRecording.value = reference
        } finally {
            running.set(false)
            paused.set(false)
            RecordingState.isPaused.value = false
            if (microphoneActive || audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { audioRecord.stop() }
            }
            audioRecord.release()
            buf.fill(0)
            RecordingState.inputPeak.value = 0f
            RecordingState.isRecording.value = false
            RecorderWidgetProvider.updateAll(this)
        }
    }

    private fun pauseRecording() {
        if (!running.get() || !paused.compareAndSet(false, true)) return
        RecordingState.isPaused.value = true
        RecordingState.inputPeak.value = 0f
        updateNotification(paused = true)
        RecorderWidgetProvider.updateAll(this)
    }

    private fun resumeRecording() {
        if (!running.get() || !paused.compareAndSet(true, false)) return
        RecordingState.isPaused.value = false
        synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
        updateNotification(paused = false)
        RecorderWidgetProvider.updateAll(this)
    }

    private fun stopRecording() {
        if (!running.get()) {
            RecordingState.isRecording.value = false
            RecordingState.isPaused.value = false
            RecorderWidgetProvider.updateAll(this)
            stopSelf()
            return
        }
        running.set(false)
        paused.set(false)
        RecordingState.isPaused.value = false
        synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
        // メインスレッド（onStartCommand）をブロックしないよう別スレッドで待つ
        Thread {
            recordThread?.join(3000)
            RecordingState.isRecording.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(paused: Boolean) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(paused))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val channel = NotificationChannel(CHANNEL_ID, AppLanguage.text("録音", "Record"), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val openRecorder = PendingIntent.getActivity(
            this,
            OPEN_RECORDER_REQUEST,
            Intent(this, RecordActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopRecording = PendingIntent.getService(
            this,
            STOP_RECORDING_REQUEST,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseOrResume = PendingIntent.getService(
            this,
            PAUSE_RESUME_REQUEST,
            Intent(this, RecordingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (paused) AppLanguage.text("録音を一時停止中", "Recording paused") else AppLanguage.text("録音中", "Recording"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openRecorder)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) AppLanguage.text("再開", "Resume") else AppLanguage.text("一時停止", "Pause"),
                pauseOrResume
            )
            .addAction(android.R.drawable.ic_media_pause, AppLanguage.text("停止して保存", "Stop and save"), stopRecording)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.gijiroku.benchmark.action.START"
        const val ACTION_PAUSE = "com.gijiroku.benchmark.action.PAUSE"
        const val ACTION_RESUME = "com.gijiroku.benchmark.action.RESUME"
        const val ACTION_STOP = "com.gijiroku.benchmark.action.STOP"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_RECORDER_REQUEST = 20_001
        private const val STOP_RECORDING_REQUEST = 20_002
        private const val PAUSE_RESUME_REQUEST = 20_003
    }
}
