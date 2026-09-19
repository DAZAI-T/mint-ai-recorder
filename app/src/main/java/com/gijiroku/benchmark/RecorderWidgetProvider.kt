package com.gijiroku.benchmark

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat

class RecorderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, RecorderWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val recording = RecordingState.isRecording.value
            val paused = RecordingState.isPaused.value
            val processing = AiProcessingState.isProcessing.value
            val views = RemoteViews(context.packageName, R.layout.recorder_widget)
            val latest = RecordingState.lastRecording.value ?: RecordingStore(context).latest()
            val elapsedMs = if (recording) {
                RecordingState.elapsedMs.value
            } else {
                latest?.durationMs ?: RecordingState.elapsedMs.value
            }.coerceAtLeast(0L)
            views.setTextViewText(
                R.id.textWidgetStatus,
                when {
                    processing -> AiProcessingState.progressLabel.value ?: AppLanguage.text("AI処理中", "AI processing")
                    paused -> AppLanguage.text("Ⅱ 録音を一時停止中", "Ⅱ Recording paused")
                    recording -> AppLanguage.text("● 暗号化して録音中", "● Recording with encryption")
                    else -> AppLanguage.text("録音待機中", "Ready to record")
                }
            )
            // Do not use RemoteViews Chronometer here. Its host can keep advancing after a
            // service is stopped or the process is reclaimed. The service updates this text
            // once per second while recording; every inactive state is therefore immutable.
            views.setTextViewText(R.id.textWidgetElapsed, widgetElapsedLabel(elapsedMs))
            views.setTextViewText(
                R.id.buttonWidgetPrimary,
                when {
                    processing -> AppLanguage.text("AI処理中", "AI processing")
                    paused -> AppLanguage.text("▶ 録音再開", "▶ Resume recording")
                    recording -> AppLanguage.text("Ⅱ 一時停止", "Ⅱ Pause")
                    else -> AppLanguage.text("● 録音開始", "● Start recording")
                }
            )
            views.setInt(
                R.id.buttonWidgetPrimary,
                "setBackgroundResource",
                R.drawable.widget_action_start
            )
            views.setTextColor(R.id.buttonWidgetPrimary, context.getColor(R.color.voice_on_accent))
            val primaryAction = when {
                paused -> RecorderWidgetActionReceiver.ACTION_RESUME
                recording -> RecorderWidgetActionReceiver.ACTION_PAUSE
                else -> RecorderWidgetActionReceiver.ACTION_START
            }
            val primaryIntent = Intent(context, RecorderWidgetActionReceiver::class.java).setAction(primaryAction)
            views.setOnClickPendingIntent(
                R.id.buttonWidgetPrimary,
                PendingIntent.getBroadcast(
                    context,
                    appWidgetId,
                    primaryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            val secondaryAction = if (recording) {
                RecorderWidgetActionReceiver.ACTION_STOP_SAVE
            } else {
                RecorderWidgetActionReceiver.ACTION_AI_PROCESS
            }
            views.setTextViewText(
                R.id.buttonWidgetSecondary,
                when {
                    processing -> AppLanguage.text("操作できません", "Unavailable")
                    recording -> AppLanguage.text("■ 停止して保存", "■ Stop and save")
                    else -> AppLanguage.text("AI処理", "AI processing")
                }
            )
            views.setInt(
                R.id.buttonWidgetSecondary,
                "setBackgroundResource",
                if (recording) R.drawable.widget_action_stop else R.drawable.widget_action_secondary
            )
            views.setTextColor(
                R.id.buttonWidgetSecondary,
                if (recording) android.graphics.Color.WHITE else context.getColor(R.color.voice_accent)
            )
            views.setOnClickPendingIntent(
                R.id.buttonWidgetSecondary,
                PendingIntent.getBroadcast(
                    context,
                    appWidgetId + SECONDARY_REQUEST_OFFSET,
                    Intent(context, RecorderWidgetActionReceiver::class.java).setAction(secondaryAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setBoolean(R.id.buttonWidgetPrimary, "setEnabled", !processing)
            views.setBoolean(R.id.buttonWidgetSecondary, "setEnabled", !processing)
            val openIntent = Intent(context, MeetingLibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.widgetOpenApp,
                PendingIntent.getActivity(
                    context,
                    appWidgetId + OPEN_REQUEST_OFFSET,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            manager.updateAppWidget(appWidgetId, views)
        }

        private const val OPEN_REQUEST_OFFSET = 10_000
        private const val SECONDARY_REQUEST_OFFSET = 20_000
    }
}

internal fun widgetElapsedLabel(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}

/** Non-exported receiver so only this app's immutable widget PendingIntent can toggle recording. */
class RecorderWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (AiProcessingState.isProcessing.value) {
            Toast.makeText(context, AppLanguage.text("AI処理中はウィジェットを操作できません", "Widget controls are unavailable during AI processing"), Toast.LENGTH_SHORT).show()
            RecorderWidgetProvider.updateAll(context)
            return
        }
        when (intent?.action) {
            ACTION_START -> startRecording(context)
            ACTION_PAUSE -> sendRecordingAction(context, RecordingService.ACTION_PAUSE)
            ACTION_RESUME -> sendRecordingAction(context, RecordingService.ACTION_RESUME)
            ACTION_STOP_SAVE -> sendRecordingAction(context, RecordingService.ACTION_STOP)
            ACTION_AI_PROCESS -> startAiProcessing(context)
        }
    }

    private fun startRecording(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, AppLanguage.text("最初にアプリでマイク権限を許可してください", "Grant microphone permission in the app first"), Toast.LENGTH_LONG).show()
            context.startActivity(Intent(context, RecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        )
    }

    private fun sendRecordingAction(context: Context, action: String) {
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    private fun startAiProcessing(context: Context) {
        if (RecordingState.isRecording.value) {
            Toast.makeText(context, AppLanguage.text("先に録音を停止して保存してください", "Stop and save the recording first"), Toast.LENGTH_SHORT).show()
            return
        }
        val reference = RecordingStore(context).latest()
        if (reference?.finalized != true) {
            Toast.makeText(context, AppLanguage.text("AI処理できる保存済み録音がありません", "No saved recording available for AI processing"), Toast.LENGTH_LONG).show()
            return
        }
        if (ProviderPreferences(context).selectedProvider(PipelineStage.SUMMARIZATION) == null) {
            Toast.makeText(context, AppLanguage.text("設定で要約Providerを選択してください", "Select a summary provider in Settings"), Toast.LENGTH_LONG).show()
            context.startActivity(Intent(context, ProviderSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val scope = AiExecutionSecurity.currentScope(context)
        if (scope.usesCloud && !DailyAiConsentStore(context).isValid(scope)) {
            context.startActivity(
                Intent(context, RecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(RecordActivity.EXTRA_MEETING_ID, reference.meetingId)
                    .putExtra(RecordActivity.EXTRA_CONFIRM_WIDGET_AI, true)
            )
            return
        }
        startAiService(context, reference.meetingId)
    }

    companion object {
        const val ACTION_START = "com.gijiroku.benchmark.widget.START_RECORDING"
        const val ACTION_PAUSE = "com.gijiroku.benchmark.widget.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.gijiroku.benchmark.widget.RESUME_RECORDING"
        const val ACTION_STOP_SAVE = "com.gijiroku.benchmark.widget.STOP_AND_SAVE"
        const val ACTION_AI_PROCESS = "com.gijiroku.benchmark.widget.START_AI"

        fun startAiService(context: Context, meetingId: String) {
            if (AiProcessingState.isProcessing.value) return
            AiProcessingState.isProcessing.value = true
            AiProcessingState.progressLabel.value = AppLanguage.text("AI処理を準備中", "Preparing AI processing")
            RecorderWidgetProvider.updateAll(context)
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AiProcessingService::class.java)
                        .setAction(AiProcessingService.ACTION_START)
                        .putExtra(AiProcessingService.EXTRA_MEETING_ID, meetingId)
                )
            }.onFailure {
                AiProcessingState.isProcessing.value = false
                AiProcessingState.progressLabel.value = null
                RecorderWidgetProvider.updateAll(context)
                Toast.makeText(context, AppLanguage.text("AI処理を開始できませんでした", "Could not start AI processing"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
