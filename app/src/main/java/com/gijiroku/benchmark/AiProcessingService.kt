package com.gijiroku.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object AiProcessingState {
    val isProcessing = MutableStateFlow(false)
    val progressLabel = MutableStateFlow<String?>(null)
}

/** Runs an explicitly authorized post-recording pipeline without depending on an Activity lifetime. */
class AiProcessingService : Service() {
    private val running = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val meetingId = intent.getStringExtra(EXTRA_MEETING_ID)
            if (meetingId != null) startProcessing(meetingId)
        }
        return START_NOT_STICKY
    }

    private fun startProcessing(meetingId: String) {
        if (!running.compareAndSet(false, true)) return
        timedOut.set(false)
        if (RecordingState.isRecording.value) {
            abortStart()
            return
        }
        val reference = RecordingStore(this).find(meetingId)
        if (reference?.finalized != true) {
            abortStart()
            return
        }
        AiProcessingState.isProcessing.value = true
        AiProcessingState.progressLabel.value = AppLanguage.text("AI処理を準備中", "Preparing AI processing")
        RecorderWidgetProvider.updateAll(this)
        startForeground(NOTIFICATION_ID, buildNotification(AppLanguage.text("AI処理を準備中", "Preparing AI processing"), 0))

        worker = Thread {
            val scope = AiExecutionSecurity.currentScope(this)
            var errorMessage: String? = null
            try {
                require(ProviderPreferences(this).selectedProvider(PipelineStage.SUMMARIZATION) != null) {
                    AppLanguage.text("要約Providerが未設定です", "No summary provider selected")
                }
                require(!scope.usesCloud || DailyAiConsentStore(this).isValid(scope)) {
                    AppLanguage.text("外部送信の24時間確認が必要です", "24-hour permission for external transmission is required")
                }
                val modelStore = ModelStore(this)
                val preferences = ProviderPreferences(this)
                if (preferences.selectedProvider(PipelineStage.TRANSCRIPTION) == ProviderIds.LOCAL_WHISPER) {
                    require(modelStore.hasFinalModel()) { AppLanguage.text("文字起こしモデルが未設定です", "No transcription model selected") }
                }
                if (preferences.selectedProvider(PipelineStage.SUMMARIZATION) == ProviderIds.LOCAL_QWEN3_SUMMARY) {
                    require(modelStore.hasQwenSummaryModel()) { AppLanguage.text("要約モデルが未設定です", "No summary model selected") }
                }
                if (scope.sendsAudio) AudioEgressPolicy.allowForCurrentSession()
                FinalizationRunner(modelStore).run(EncryptedRecordingAudioSource(this, reference)) { progress ->
                    AiProcessingState.progressLabel.value = progress.label
                    updateNotification(progress.label, progress.completed)
                    RecorderWidgetProvider.updateAll(this)
                }
            } catch (error: Throwable) {
                errorMessage = error.message ?: AppLanguage.text("AI処理に失敗しました", "AI processing failed")
            } finally {
                if (timedOut.get() && errorMessage == null) {
                    errorMessage = AppLanguage.text("Androidのバックグラウンド処理時間上限に達しました", "Android background processing time limit reached")
                }
                if (scope.sendsAudio) AudioEgressPolicy.block()
                running.set(false)
                AiProcessingState.isProcessing.value = false
                AiProcessingState.progressLabel.value = null
                RecorderWidgetProvider.updateAll(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                showCompletionNotification(meetingId, errorMessage)
                stopSelf()
            }
        }.also { it.start() }
    }

    private fun abortStart() {
        running.set(false)
        AiProcessingState.isProcessing.value = false
        AiProcessingState.progressLabel.value = null
        RecorderWidgetProvider.updateAll(this)
        stopSelf()
    }

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut.set(true)
        running.set(false)
        worker?.interrupt()
        AudioEgressPolicy.block()
        AiProcessingState.isProcessing.value = false
        AiProcessingState.progressLabel.value = null
        RecorderWidgetProvider.updateAll(this)
        stopSelf(startId)
    }

    private fun buildNotification(label: String, progress: Int): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AppLanguage.text("AI処理中", "AI processing"))
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(6, progress.coerceIn(0, 6), false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification(label: String, progress: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(label, progress))
    }

    private fun showCompletionNotification(meetingId: String, errorMessage: String?) {
        createChannel()
        val openMeeting = PendingIntent.getActivity(
            this,
            OPEN_RESULT_REQUEST,
            Intent(this, MeetingDetailActivity::class.java)
                .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (errorMessage == null) AppLanguage.text("AI処理が完了しました", "AI processing completed") else AppLanguage.text("AI処理を完了できませんでした", "Could not complete AI processing"))
            .setContentText(errorMessage ?: AppLanguage.text("アプリで結果を確認できます", "View the results in the app"))
            .setSmallIcon(if (errorMessage == null) android.R.drawable.checkbox_on_background else android.R.drawable.stat_notify_error)
            .setContentIntent(openMeeting)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, AppLanguage.text("AI処理", "AI processing"), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.gijiroku.benchmark.action.START_AI_PROCESSING"
        const val EXTRA_MEETING_ID = "meeting_id"
        private const val CHANNEL_ID = "ai_processing"
        private const val NOTIFICATION_ID = 31
        private const val RESULT_NOTIFICATION_ID = 32
        private const val OPEN_RESULT_REQUEST = 30_001
    }
}
