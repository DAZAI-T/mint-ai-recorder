package com.gijiroku.benchmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gijiroku.benchmark.databinding.ActivityRecordBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** Recording screen. AI processing starts only after the recording is finalized. */
class RecordActivity : SecureActivity() {

    private lateinit var binding: ActivityRecordBinding
    private lateinit var modelStore: ModelStore
    private var analyzing = false
    private var finalTranscriptExpanded = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                actuallyStartRecording()
            } else {
                Toast.makeText(this, AppLanguage.text("マイクの権限がないと録音できません", "Microphone permission is required to record"), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        finalTranscriptExpanded = savedInstanceState?.getBoolean(STATE_FINAL_TRANSCRIPT_EXPANDED) == true
        modelStore = ModelStore(this)
        val recordingStore = RecordingStore(this)
        val requestedMeeting = intent.getStringExtra(EXTRA_MEETING_ID)
            ?.let(recordingStore::find)
            ?.takeIf { File(it.encryptedFilePath).isFile }
        RecordingState.lastRecording.value = requestedMeeting ?: recordingStore.latest()

        updateAiConfigurationSummary()

        binding.buttonRecord.setOnClickListener {
            when {
                RecordingState.isPaused.value -> resumeRecording()
                RecordingState.isRecording.value -> pauseRecording()
                else -> requestPermissionsAndStart()
            }
        }
        binding.buttonStopAndSaveRecording.setOnClickListener { stopRecording() }

        binding.buttonOpenBenchmark.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.buttonOpenProviderSettings.setOnClickListener {
            startActivity(Intent(this, ProviderSettingsActivity::class.java))
        }

        binding.buttonOpenMeetings.setOnClickListener {
            startActivity(Intent(this, MeetingLibraryActivity::class.java))
        }

        binding.buttonFinalize.setOnClickListener { runFinalization() }
        binding.buttonSaveRecordingOnly.setOnClickListener { finishRecordingWithoutAi() }
        binding.buttonAudioEgressProtection.setOnClickListener { toggleAudioEgressProtection() }
        binding.buttonDeleteMeeting.setOnClickListener { showDeleteChoices() }
        binding.textFinalTranscript.doOnTextChanged { text, _, _, _ ->
            binding.textFinalTranscript.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            updateFinalTranscriptExpansion()
        }
        binding.buttonToggleFinalTranscript.setOnClickListener {
            finalTranscriptExpanded = !finalTranscriptExpanded
            updateFinalTranscriptExpansion()
        }
        binding.buttonPreviewRecording.setOnClickListener {
            RecordingState.lastRecording.value?.let {
                startActivity(Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, it.meetingId))
            }
        }
        binding.buttonRecordingTemplate.setOnClickListener { chooseRecordingTemplate() }

        observeState()
        binding.root.post { handleWidgetAiConfirmation(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_MEETING_ID)
            ?.let { RecordingStore(this).find(it) }
            ?.let { RecordingState.lastRecording.value = it }
        binding.root.post { handleWidgetAiConfirmation(intent) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FINAL_TRANSCRIPT_EXPANDED, finalTranscriptExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::modelStore.isInitialized) {
            updateAiConfigurationSummary()
        }
    }

    private fun runFinalization() {
        val reference = RecordingState.lastRecording.value ?: return
        if (RecordingState.isRecording.value || !reference.finalized) {
            Toast.makeText(this, AppLanguage.text("録音を停止して保存完了を待ってください", "Stop recording and wait for it to finish saving"), Toast.LENGTH_SHORT).show()
            return
        }
        val audioSource = EncryptedRecordingAudioSource(this, reference)
        val preferences = ProviderPreferences(this)
        val transcriptionProviderId = preferences.selectedProvider(PipelineStage.TRANSCRIPTION)
        val formattingProviderId = preferences.selectedProvider(PipelineStage.TEXT_FORMATTING)
        val summaryProviderId = preferences.selectedProvider(PipelineStage.SUMMARIZATION)
        if (transcriptionProviderId == ProviderIds.LOCAL_WHISPER && !modelStore.hasFinalModel()) {
            Toast.makeText(this, AppLanguage.text("確定処理用モデルが未設定です。先に選択してください", "Select a processing model first"), Toast.LENGTH_LONG).show()
            return
        }
        val numSpeakers = binding.editNumSpeakers.text.toString().toIntOrNull()?.takeIf { it > 0 }
        val sendsAudio = transcriptionProviderId in CLOUD_AUDIO_PROVIDERS
        val sendsTranscript = formattingProviderId in CLOUD_FORMATTING_PROVIDERS ||
            summaryProviderId in CLOUD_SUMMARY_PROVIDERS
        if (sendsAudio && AudioEgressPolicy.state.value == AudioEgressState.BLOCK) {
            showAudioProtectionBlocked(numSpeakers)
            return
        }
        if (sendsAudio || sendsTranscript) {
            val selected = setOfNotNull(transcriptionProviderId, formattingProviderId, summaryProviderId)
            val secrets = SecretStore(this)
            if (selected.any { it in OPENAI_PROVIDERS } && !secrets.hasSecret(SecretStore.OPENAI_API_KEY)) {
                Toast.makeText(this, AppLanguage.text("OpenAI APIキーが未設定です", "OpenAI API key is not configured"), Toast.LENGTH_LONG).show()
                return
            }
            if (selected.any { it in GEMINI_PROVIDERS } && !secrets.hasSecret(SecretStore.GEMINI_API_KEY)) {
                Toast.makeText(this, AppLanguage.text("Gemini APIキーが未設定です", "Gemini API key is not configured"), Toast.LENGTH_LONG).show()
                return
            }
            showCloudExecutionConfirmation(audioSource, numSpeakers, sendsAudio, sendsTranscript)
            return
        }
        executeFinalization(audioSource, numSpeakers)
    }

    private fun finishRecordingWithoutAi() {
        val reference = RecordingState.lastRecording.value
        if (reference?.finalized != true || RecordingState.isRecording.value) {
            Toast.makeText(this, AppLanguage.text("録音を停止して保存完了を待ってください", "Stop recording and wait for it to finish saving"), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, AppLanguage.text("AI処理を行わず録音を保存しました", "Recording saved without AI processing"), Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MeetingLibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun handleWidgetAiConfirmation(launchIntent: Intent) {
        if (!launchIntent.getBooleanExtra(EXTRA_CONFIRM_WIDGET_AI, false)) return
        launchIntent.removeExtra(EXTRA_CONFIRM_WIDGET_AI)
        runWhenAppUnlocked {
            val reference = RecordingState.lastRecording.value
            if (reference?.finalized != true || RecordingState.isRecording.value) {
                Toast.makeText(this, AppLanguage.text("AI処理できる保存済み録音がありません", "No saved recording available for AI processing"), Toast.LENGTH_LONG).show()
                return@runWhenAppUnlocked
            }
            val configurationError = widgetAiConfigurationError()
            if (configurationError != null) {
                AlertDialog.Builder(this)
                    .setTitle(AppLanguage.text("AI設定を確認してください", "Check AI settings"))
                    .setMessage(configurationError)
                    .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
                    .setPositiveButton(AppLanguage.text("設定を開く", "Open settings")) { _, _ ->
                        startActivity(Intent(this, ProviderSettingsActivity::class.java))
                    }
                    .show()
                return@runWhenAppUnlocked
            }
            val scope = AiExecutionSecurity.currentScope(this)
            if (!scope.usesCloud || DailyAiConsentStore(this).isValid(scope)) {
                RecorderWidgetActionReceiver.startAiService(this, reference.meetingId)
                return@runWhenAppUnlocked
            }
            AlertDialog.Builder(this)
                .setTitle(AppLanguage.text("外部送信を許可しますか", "Allow external transmission?"))
                .setMessage(
                    AppLanguage.text("送信データ: ${scope.dataLabels.joinToString("、")}\n", "Data sent: ${scope.dataLabels.joinToString(", ")}\n") +
                        "Provider: ${scope.providerLabels.joinToString("、")}\n" +
                        AppLanguage.text("送信先: ${scope.destinations.joinToString("、")}\n\n", "Destinations: ${scope.destinations.joinToString(", ")}\n\n") +
                        (if (scope.sendsAudio) AppLanguage.text("録音音声には個人を識別し得る声の特徴が含まれます。\n\n", "Recorded audio contains voice characteristics that may identify individuals.\n\n") else "") +
                        AppLanguage.text("許可はこのProvider・モデル・送信内容にだけ、アプリ再起動または24時間後まで有効です。変更時や期限後は再確認します。", "Permission is valid only for this provider, model, and data scope until the app restarts or 24 hours pass. Changes or expiry require confirmation again. ") +
                        AppLanguage.text("手動参加者名の対応表は送信しません。", "The participant-name mapping is never sent.")
                )
                .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
                .setPositiveButton(AppLanguage.text("許可して開始", "Allow and start")) { _, _ ->
                    DailyAiConsentStore(this).grant(scope)
                    RecorderWidgetActionReceiver.startAiService(this, reference.meetingId)
                }
                .show()
        }
    }

    private fun widgetAiConfigurationError(): String? {
        val preferences = ProviderPreferences(this)
        val transcription = preferences.selectedProvider(PipelineStage.TRANSCRIPTION)
        val summary = preferences.selectedProvider(PipelineStage.SUMMARIZATION)
        if (summary == null) return AppLanguage.text("要約Providerが未設定です。", "No summary provider selected.")
        if (transcription == ProviderIds.LOCAL_WHISPER && !modelStore.hasFinalModel()) {
            return AppLanguage.text("文字起こし用のローカルモデルが未設定です。", "No local transcription model selected.")
        }
        if (summary == ProviderIds.LOCAL_QWEN3_SUMMARY && !modelStore.hasQwenSummaryModel()) {
            return AppLanguage.text("要約用のローカルモデルが未設定です。", "No local summary model selected.")
        }
        val selected = setOfNotNull(
            transcription,
            preferences.selectedProvider(PipelineStage.TEXT_FORMATTING),
            summary
        )
        val secrets = SecretStore(this)
        if (selected.any { it in OPENAI_PROVIDERS } && !secrets.hasSecret(SecretStore.OPENAI_API_KEY)) {
            return AppLanguage.text("OpenAI APIキーが未設定です。", "OpenAI API key is not configured.")
        }
        if (selected.any { it in GEMINI_PROVIDERS } && !secrets.hasSecret(SecretStore.GEMINI_API_KEY)) {
            return AppLanguage.text("Gemini APIキーが未設定です。", "Gemini API key is not configured.")
        }
        return null
    }

    private fun toggleAudioEgressProtection() {
        if (AudioEgressPolicy.state.value == AudioEgressState.ALLOW_FOR_SESSION) {
            AudioEgressPolicy.block()
            Toast.makeText(this, AppLanguage.text("音声の外部送信を停止・保護しました", "Audio uploads stopped and protection enabled"), Toast.LENGTH_SHORT).show()
        } else {
            requestAudioEgressPermission()
        }
    }

    private fun showAudioProtectionBlocked(numSpeakers: Int?) {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("音声外部送信保護がONです", "Audio upload protection is ON"))
            .setMessage(
                AppLanguage.text("クラウド音声認識は開始されていません。ローカルWhisperへ変更するか、", "Cloud transcription has not started. Switch to local Whisper, or ") +
                    AppLanguage.text("音声に声の特徴が含まれることを確認して、このセッションだけ保護を解除してください。", "acknowledge that audio contains voice characteristics and disable protection for this session only.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setNeutralButton(AppLanguage.text("ローカルに変更", "Switch to local")) { _, _ ->
                ProviderPreferences(this).selectProvider(
                    PipelineStage.TRANSCRIPTION,
                    ProviderIds.LOCAL_WHISPER
                )
                updateAiConfigurationSummary()
                Toast.makeText(this, AppLanguage.text("ローカルWhisperへ変更しました", "Switched to local Whisper"), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(AppLanguage.text("保護を解除", "Disable protection")) { _, _ ->
                requestAudioEgressPermission { runFinalizationWithSpeakerCount(numSpeakers) }
            }
            .show()
    }

    private fun runFinalizationWithSpeakerCount(numSpeakers: Int?) {
        binding.editNumSpeakers.setText(numSpeakers?.toString().orEmpty())
        runFinalization()
    }

    private fun requestAudioEgressPermission(onAllowed: (() -> Unit)? = null) {
        val transcriptionProviderId = ProviderPreferences(this).selectedProvider(PipelineStage.TRANSCRIPTION)
        val isGemini = transcriptionProviderId == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION
        val endpoint = if (isGemini) GeminiRestClient.API_BASE else OpenAiWhisperProvider.TRANSCRIPTIONS_ENDPOINT
        val origin = AudioEgressConsentStore.normalizeHttpsOrigin(
            endpoint
        )
        val consentStore = AudioEgressConsentStore(this)
        if (consentStore.hasAcknowledged(origin)) {
            AudioEgressPolicy.allowForCurrentSession()
            onAllowed?.invoke()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("音声の外部送信を許可しますか？", "Allow audio uploads?"))
            .setMessage(
                "Provider: ${if (isGemini) "Google Gemini" else "OpenAI"}\n" +
                    AppLanguage.text("送信先: $origin\n\n", "Destination: $origin\n\n") +
                    AppLanguage.text("録音音声には、発言内容だけでなく個人を識別し得る声の特徴が含まれます。", "Recorded audio contains both speech and voice characteristics that may identify individuals. ") +
                    AppLanguage.text("送信後の保存・学習・削除条件は送信先Providerの規約に従います。\n\n", "Retention, training, and deletion after upload are governed by the destination provider's terms.\n\n") +
                    AppLanguage.text("声の特徴を端末外へ出したくない場合はローカルWhisperを使用してください。", "Use local Whisper if you want voice characteristics to stay on this device. ") +
                    AppLanguage.text("解除は現在のアプリセッションだけ有効です。", "Protection is disabled only for this app session.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("理解して解除", "Understand and disable")) { _, _ ->
                consentStore.acknowledge(origin)
                AudioEgressPolicy.allowForCurrentSession()
                onAllowed?.invoke()
            }
            .show()
    }

    private fun showDeleteChoices() {
        val reference = RecordingState.lastRecording.value ?: return
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("削除するデータを選択", "Choose data to delete"))
            .setItems(arrayOf(AppLanguage.text("削除", "Delete"), AppLanguage.text("音声データのみ削除", "Delete audio only"))) { _, which ->
                if (which == 0) confirmFullDeletion(reference) else confirmAudioDeletion(reference)
            }
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .show()
    }

    private fun confirmFullDeletion(reference: RecordingReference) {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("会議を完全に削除しますか？", "Permanently delete this meeting?"))
            .setMessage(
                AppLanguage.text("録音、文字起こし、話者ラベル、要約、編集履歴の鍵を直ちに破棄します。", "Keys for the recording, transcript, speaker labels, summary, and edit history will be destroyed immediately. ") +
                    AppLanguage.text("この操作は取り消せません。すでに外部へ保存・送信した複製は削除されません。", "This cannot be undone. Previously exported or transmitted copies will not be deleted.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("完全に削除", "Delete permanently")) { _, _ -> executeDeletion(reference, audioOnly = false) }
            .show()
    }

    private fun confirmAudioDeletion(reference: RecordingReference) {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("音声データのみ削除しますか？", "Delete audio data only?"))
            .setMessage(
                AppLanguage.text("録音音声の鍵を直ちに破棄します。文字起こし、話者ラベル、要約、編集履歴は残りますが、", "The audio key will be destroyed immediately. Transcripts, speaker labels, summaries, and edit history remain, but ") +
                    AppLanguage.text("再生・再文字起こし・再話者分離はできなくなります。この操作は取り消せません。", "playback, retranscription, and speaker separation will no longer be possible. This cannot be undone.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("音声のみ削除", "Delete audio only")) { _, _ -> executeDeletion(reference, audioOnly = true) }
            .show()
    }

    private fun executeDeletion(reference: RecordingReference, audioOnly: Boolean) {
        binding.buttonDeleteMeeting.isEnabled = false
        Thread {
            val result = runCatching {
                val store = RecordingStore(this)
                if (audioOnly) store.deleteAudio(reference) else store.deleteMeeting(reference)
            }
            runOnUiThread {
                result.onSuccess { deletion ->
                    RecordingState.lastRecording.value = null
                    if (!audioOnly) binding.textFinalTranscript.text = ""
                    val cleanup = if (deletion.encryptedFileRemoved) "" else
                        AppLanguage.text(" 暗号ファイルは残りましたが、鍵破棄済みで復号できません。", " Encrypted files remain, but the keys are destroyed and the files cannot be decrypted.")
                    Toast.makeText(
                        this,
                        (if (audioOnly) AppLanguage.text("音声データを削除しました。", "Audio data deleted.") else AppLanguage.text("会議を削除しました。", "Meeting deleted.")) + cleanup,
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(this, AppLanguage.text("削除できませんでした: ${error.message}", "Could not delete: ${error.message}"), Toast.LENGTH_LONG).show()
                    binding.buttonDeleteMeeting.isEnabled = true
                }
            }
        }.start()
    }

    private fun showCloudExecutionConfirmation(
        audioSource: AudioSource,
        numSpeakers: Int?,
        sendsAudio: Boolean,
        sendsTranscript: Boolean
    ) {
        val consentScope = AiExecutionSecurity.currentScope(this)
        if (DailyAiConsentStore(this).isValid(consentScope)) {
            executeFinalization(audioSource, numSpeakers)
            return
        }
        val estimatedDurationMs = audioSource.estimatedDurationMs
        val durationLine = estimatedDurationMs?.let {
            AppLanguage.text("録音時間: 約%.2f分\n", "Recording duration: about %.2f min\n").format(it / 60_000.0)
        } ?: AppLanguage.text("録音時間: 中断録音のため確定処理時に検証\n", "Recording duration: will be verified during processing of the interrupted recording\n")
        val preferences = ProviderPreferences(this)
        val transcriptionProviderId = preferences.selectedProvider(PipelineStage.TRANSCRIPTION)
        val formattingProviderId = preferences.selectedProvider(PipelineStage.TEXT_FORMATTING)
        val summaryProviderId = preferences.selectedProvider(PipelineStage.SUMMARIZATION)
        val usesGemini = setOfNotNull(
            transcriptionProviderId,
            formattingProviderId,
            summaryProviderId
        ).any { it in GEMINI_PROVIDERS }
        val estimatedCost = estimatedDurationMs
            ?.takeIf { transcriptionProviderId == ProviderIds.OPENAI_WHISPER &&
                CloudProviderSettings(this).openAiTranscriptionModel == "whisper-1" }
            ?.let(OpenAiWhisperProvider::estimateCostUsd)
        val dataKinds = buildList {
            if (sendsAudio) add(AppLanguage.text("録音音声", "Recorded audio"))
            if (sendsTranscript) add(AppLanguage.text("整形済み文字起こし", "Formatted transcript"))
        }.joinToString("、")
        val providers = buildList {
            if (transcriptionProviderId == ProviderIds.OPENAI_WHISPER) add("OpenAI Whisper API")
            if (transcriptionProviderId == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION) add(AppLanguage.text("Google Gemini 音声認識", "Google Gemini transcription"))
            if (formattingProviderId == ProviderIds.OPENAI_RESPONSES_FORMATTING) add(AppLanguage.text("OpenAI テキスト整形", "OpenAI text formatting"))
            if (formattingProviderId == ProviderIds.GEMINI_FLASH_LITE_FORMATTING) add(AppLanguage.text("Google Gemini 整形", "Google Gemini formatting"))
            if (summaryProviderId == ProviderIds.OPENAI_RESPONSES_SUMMARY) add(AppLanguage.text("OpenAI 構造化要約", "OpenAI structured summary"))
            if (summaryProviderId == ProviderIds.GEMINI_FLASH_LITE_SUMMARY) add(AppLanguage.text("Google Gemini 要約", "Google Gemini summary"))
        }.joinToString("、")
        val destinations = buildList {
            if (setOfNotNull(transcriptionProviderId, formattingProviderId, summaryProviderId).any { it in OPENAI_PROVIDERS }) {
                add("api.openai.com")
            }
            if (usesGemini) {
                add("generativelanguage.googleapis.com")
            }
        }.joinToString("、")
        val costLine = buildString {
            if (sendsAudio && transcriptionProviderId == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION) {
                append(AppLanguage.text("Gemini料金: 選択したモデル・利用枠により異なります\n", "Gemini pricing: depends on the selected model and usage tier\n"))
            } else if (sendsAudio && estimatedCost != null) {
                append(AppLanguage.text("音声認識概算: $%.4f以上\n", "Estimated transcription cost: at least $%.4f\n").format(estimatedCost))
            } else if (sendsAudio) {
                append(AppLanguage.text("音声認識概算: 事前確定不可\n", "Transcription cost: cannot be determined in advance\n"))
            }
            if (sendsTranscript) append(AppLanguage.text("要約料金: トークン従量（事前確定不可）\n", "Summary cost: per token (cannot be determined in advance)\n"))
        }
        val retryLine = if (usesGemini) {
            AppLanguage.text("一時エラー時は同じGeminiへ最大3回再送信する場合があります。\n", "Temporary errors may trigger up to 3 retries to the same Gemini provider.\n")
        } else ""
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("クラウドへデータを送信します", "Send data to the cloud"))
            .setMessage(
                AppLanguage.text("送信データ: $dataKinds\n", "Data sent: $dataKinds\n") +
                AppLanguage.text("送信先: $destinations\n", "Destinations: $destinations\n") +
                    "Provider: $providers\n" +
                    durationLine +
                    costLine + "\n" +
                    AppLanguage.text("音声は端末内で復号し、平文ファイルを作らず送信します。\n", "Audio is decrypted on device and sent without creating plaintext files.\n") +
                    retryLine +
                    AppLanguage.text("手動参加者名の対応表は送信しません。クラウド間の自動切り替えは行いません。", "Participant-name mappings are never sent. There is no automatic switching between cloud providers.")
                    + AppLanguage.text("\nこのProvider・モデル・送信内容への確認は、アプリ再起動または24時間後まで有効です。", "\nConfirmation for this provider, model, and data scope is valid until the app restarts or 24 hours pass.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
                .setPositiveButton(AppLanguage.text("許可して実行", "Allow and run")) { _, _ ->
                DailyAiConsentStore(this).grant(consentScope)
                executeFinalization(audioSource, numSpeakers)
            }
            .show()
    }

    private fun executeFinalization(audioSource: AudioSource, numSpeakers: Int?) {
        finalTranscriptExpanded = false
        analyzing = true
        AiProcessingState.isProcessing.value = true
        AiProcessingState.progressLabel.value = AppLanguage.text("録音を解析中", "Analyzing recording")
        RecorderWidgetProvider.updateAll(this)
        binding.buttonRecord.isEnabled = false
        binding.buttonStopAndSaveRecording.isEnabled = false
        binding.buttonDeleteMeeting.isEnabled = false
        binding.buttonRecordingTemplate.isEnabled = false
        binding.buttonFinalize.isEnabled = false
        binding.buttonSaveRecordingOnly.isEnabled = false
        binding.textFinalTranscript.text = AppLanguage.text("録音を解析しています…", "Analyzing recording…")
        Thread {
            val result = try {
                FinalizationRunner(modelStore).run(audioSource, numSpeakers) { progress ->
                    runOnUiThread {
                        AiProcessingState.progressLabel.value = progress.label
                        RecorderWidgetProvider.updateAll(this)
                        binding.textFinalTranscript.text =
                            "${progress.label}（${progress.completed}/${progress.total}）"
                    }
                }
            } catch (e: Exception) {
                AppLanguage.text("エラー: ${e.message}", "Error: ${e.message}")
            }
            runOnUiThread {
                analyzing = false
                AiProcessingState.isProcessing.value = false
                AiProcessingState.progressLabel.value = null
                RecorderWidgetProvider.updateAll(this)
                binding.textFinalTranscript.text = result
                binding.buttonFinalize.isEnabled = true
                binding.buttonRecord.isEnabled = true
                binding.buttonStopAndSaveRecording.isEnabled = true
                binding.buttonDeleteMeeting.isEnabled = true
                binding.buttonRecordingTemplate.isEnabled = true
                binding.buttonSaveRecordingOnly.isEnabled =
                    RecordingState.lastRecording.value?.finalized == true
            }
        }.start()
    }

    private fun updateFinalTranscriptExpansion() {
        val text = binding.textFinalTranscript.text?.toString().orEmpty()
        val canExpand = isLongText(text, COLLAPSED_RESULT_LINES)
        binding.textFinalTranscript.maxLines = if (finalTranscriptExpanded) Int.MAX_VALUE else COLLAPSED_RESULT_LINES
        binding.textFinalTranscript.ellipsize = if (finalTranscriptExpanded) null else TextUtils.TruncateAt.END
        binding.buttonToggleFinalTranscript.visibility = if (canExpand) View.VISIBLE else View.GONE
        binding.buttonToggleFinalTranscript.text = getString(
            if (finalTranscriptExpanded) R.string.ui_121 else R.string.ui_120
        )
    }

    private fun isLongText(text: String, collapsedLines: Int): Boolean =
        text.lineSequence().count() > collapsedLines ||
            text.length > collapsedLines * APPROXIMATE_CHARACTERS_PER_LINE

    private fun updateAiConfigurationSummary() {
        val preferences = ProviderPreferences(this)
        val transcription = preferences.selectedProvider(PipelineStage.TRANSCRIPTION)
        val summary = preferences.selectedProvider(PipelineStage.SUMMARIZATION)
        binding.textAiConfiguration.text =
            AppLanguage.text("音声認識: ${providerLabelWithModel(transcription, PipelineStage.TRANSCRIPTION)}\n", "Transcription: ${providerLabelWithModel(transcription, PipelineStage.TRANSCRIPTION)}\n") +
                AppLanguage.text("要約: ${providerLabelWithModel(summary, PipelineStage.SUMMARIZATION)}\n", "Summary: ${providerLabelWithModel(summary, PipelineStage.SUMMARIZATION)}\n") +
                AppLanguage.text("録音中はAI処理を行いません。Provider・モデルは設定画面で変更できます。", "AI runs after recording stops. Change providers and models in Settings.")
        binding.buttonRecordingTemplate.text =
            AppLanguage.text("議事録の用途：", "Minutes template: ") + MeetingMinutesTemplates.find(MinutesTemplateSettings(this).selectedTemplateId).displayName + "　⌄"
        updatePrivacyLabel()
    }

    private fun chooseRecordingTemplate() {
        val settings = MinutesTemplateSettings(this)
        val templates = MeetingMinutesTemplates.builtIns
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("この解析で作る議事録", "Minutes for this recording"))
            .setSingleChoiceItems(templates.map { it.displayName }.toTypedArray(),
                templates.indexOfFirst { it.id == settings.selectedTemplateId }) { dialog, index ->
                settings.selectedTemplateId = templates[index].id
                updateAiConfigurationSummary()
                dialog.dismiss()
            }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun updatePrivacyLabel() {
        val preferences = ProviderPreferences(this)
        val cloudAudio = preferences.selectedProvider(PipelineStage.TRANSCRIPTION) in CLOUD_AUDIO_PROVIDERS
        val cloudText = preferences.selectedProvider(PipelineStage.TEXT_FORMATTING) in CLOUD_FORMATTING_PROVIDERS ||
            preferences.selectedProvider(PipelineStage.SUMMARIZATION) in CLOUD_SUMMARY_PROVIDERS
        val blocked = AudioEgressPolicy.state.value == AudioEgressState.BLOCK
        val route = when {
            cloudAudio && blocked -> AppLanguage.text("音声送信が必要な構成・現在はブロック中", "Audio upload required · Currently blocked")
            cloudAudio -> AppLanguage.text("音声をクラウドへ送信する構成", "Configured to send audio to the cloud")
            cloudText -> AppLanguage.text("文字起こしのみクラウドへ送信する構成", "Configured to send only transcripts to the cloud")
            else -> AppLanguage.text("AI処理は端末内で完結", "AI processing stays on this device")
        }
        binding.buttonAudioEgressProtection.text = route + "\n" +
            if (blocked) AppLanguage.text("音声送信：禁止　／　タップで変更", "Audio upload: Blocked / Tap to change") else AppLanguage.text("音声送信：許可（アプリ終了まで）", "Audio upload: Allowed until app exits")
        binding.buttonAudioEgressProtection.setTextColor(ContextCompat.getColor(this,
            if (cloudAudio) R.color.voice_warning else R.color.voice_accent))
    }

    private fun updateRecordingMonitor() {
        val recording = RecordingState.isRecording.value
        val paused = RecordingState.isPaused.value
        val duration = if (recording) RecordingState.elapsedMs.value else
            RecordingState.lastRecording.value?.durationMs ?: 0L
        val seconds = duration / 1000
        binding.textRecordingTime.text = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        binding.textMonitorHint.text = when {
            paused -> AppLanguage.text("Ⅱ 録音を一時停止中", "Ⅱ Recording paused")
            recording -> AppLanguage.text("● 録音中 ・ マイクの入力音量", "● Recording · Microphone input level")
            RecordingState.lastRecording.value?.finalized == false -> AppLanguage.text("録音が未完了です。保存済み部分を確認できます", "Recording incomplete. You can review the saved portion.")
            RecordingState.lastRecording.value != null -> AppLanguage.text("録音を保存しました", "Recording saved")
            else -> AppLanguage.text("録音を開始すると入力音量が表示されます", "Start recording to see the input level")
        }
    }

    private fun providerLabelWithModel(providerId: String?, stage: PipelineStage): String {
        val model = when (providerId) {
            ProviderIds.LOCAL_WHISPER ->
                modelStore.selectedLocalModel(LocalModelLibraryKind.TRANSCRIPTION)?.displayName
            ProviderIds.LOCAL_QWEN3_SUMMARY ->
                modelStore.selectedLocalModel(LocalModelLibraryKind.SUMMARIZATION)?.displayName
            ProviderIds.OPENAI_WHISPER -> CloudProviderSettings(this)
                .selectedModel(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION)
            ProviderIds.OPENAI_RESPONSES_FORMATTING -> CloudProviderSettings(this)
                .selectedModel(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING)
            ProviderIds.OPENAI_RESPONSES_SUMMARY -> CloudProviderSettings(this)
                .selectedModel(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION)
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION,
            ProviderIds.GEMINI_FLASH_LITE_FORMATTING,
            ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> CloudProviderSettings(this)
                .selectedModel(CloudModelProvider.GEMINI, stage)
            else -> null
        }
        return providerLabel(providerId) + (model?.let { " / $it" } ?: "")
    }

    private fun providerLabel(providerId: String?): String = when (providerId) {
        ProviderIds.LOCAL_WHISPER -> AppLanguage.text("ローカルWhisper", "Local Whisper")
        ProviderIds.OPENAI_WHISPER -> "OpenAI"
        ProviderIds.OPENAI_RESPONSES_FORMATTING -> "OpenAI"
        ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION -> "Google Gemini"
        ProviderIds.GEMINI_FLASH_LITE_FORMATTING -> "Google Gemini"
        ProviderIds.LOCAL_QWEN3_SUMMARY -> AppLanguage.text("ローカルllama.cpp", "Local llama.cpp")
        ProviderIds.OPENAI_RESPONSES_SUMMARY -> "OpenAI"
        ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> "Google Gemini"
        null -> AppLanguage.text("使用しない", "Disabled")
        else -> providerId
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) actuallyStartRecording() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun actuallyStartRecording() {
        if (AiProcessingState.isProcessing.value) {
            Toast.makeText(this, AppLanguage.text("AI処理中は録音を開始できません", "Cannot start recording during AI processing"), Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        )
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun pauseRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE))
    }

    private fun resumeRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_RESUME))
    }

    private fun updateRecordingControls() {
        val recording = RecordingState.isRecording.value
        val paused = RecordingState.isPaused.value
        binding.buttonRecord.text = when {
            paused -> AppLanguage.text("▶ 録音再開", "▶ Resume recording")
            recording -> AppLanguage.text("Ⅱ 一時停止", "Ⅱ Pause")
            else -> AppLanguage.text("● 録音開始", "● Start recording")
        }
        binding.buttonRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (recording && !paused) android.graphics.Color.rgb(179, 38, 30)
            else ContextCompat.getColor(this, R.color.voice_accent)
        )
        binding.buttonRecord.setTextColor(
            if (recording && !paused) android.graphics.Color.WHITE
            else ContextCompat.getColor(this, R.color.voice_on_accent)
        )
        binding.buttonStopAndSaveRecording.visibility = if (recording) View.VISIBLE else View.GONE
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RecordingState.isRecording.collect { recording ->
                        updateRecordingControls()
                        if (recording) {
                            binding.textFinalTranscript.text = ""
                            binding.recordingLevel.clear()
                        }
                        updateRecordingMonitor()
                        updateStatusLine()
                    }
                }
                launch {
                    RecordingState.isPaused.collect {
                        updateRecordingControls()
                        updateRecordingMonitor()
                        updateStatusLine()
                    }
                }
                launch {
                    AiProcessingState.isProcessing.collect { processing ->
                        if (!analyzing) {
                            binding.buttonRecord.isEnabled = !processing
                            binding.buttonStopAndSaveRecording.isEnabled = !processing
                        }
                        updateStatusLine()
                    }
                }
                launch {
                    AiProcessingState.progressLabel.collect { updateStatusLine() }
                }
                launch { RecordingState.failureMessage.collect { updateStatusLine() } }
                launch {
                    AudioEgressPolicy.state.collect { updatePrivacyLabel() }
                }
                launch { RecordingState.elapsedMs.collect { updateRecordingMonitor() } }
                launch { RecordingState.inputPeak.collect {
                    if (RecordingState.isRecording.value) binding.recordingLevel.pushLevel(it)
                } }
                launch {
                    combine(RecordingState.lastRecording, RecordingState.isRecording) { recordingReference, recording ->
                        recordingReference to recording
                    }.collect { (recordingReference, recording) ->
                        val enabled = recordingReference != null && !recording
                        binding.buttonFinalize.isEnabled = enabled && !analyzing
                        binding.buttonSaveRecordingOnly.isEnabled =
                            enabled && recordingReference?.finalized == true && !analyzing
                        binding.buttonPreviewRecording.isEnabled = enabled
                        updateRecordingMonitor()
                    }
                }
                launch {
                    combine(RecordingState.lastRecording, RecordingState.isRecording) { reference, recording ->
                        reference != null && !recording
                    }.collect { enabled -> binding.buttonDeleteMeeting.isEnabled = enabled && !analyzing }
                }
            }
        }
    }

    private fun updateStatusLine() {
        val failure = RecordingState.failureMessage.value
        binding.textStatus.text =
            when {
                failure != null -> failure
                AiProcessingState.isProcessing.value ->
                    AiProcessingState.progressLabel.value ?: AppLanguage.text("AI処理中", "AI processing")
                RecordingState.isPaused.value -> AppLanguage.text("録音を一時停止中です。「録音再開」または「停止して保存」を選べます。", "Recording paused. Choose \"Resume recording\" or \"Stop and save\".")
                RecordingState.isRecording.value -> AppLanguage.text("暗号化して録音中です。AI処理は録音停止後に実行します。", "Recording with encryption. AI runs after recording stops.")
                RecordingState.lastRecording.value != null ->
                    AppLanguage.text("録音は保存済みです。そのまま終了するか、必要な場合だけAI処理を実行できます。", "Recording saved. You can finish or run AI only if needed.")
                else -> AppLanguage.text("録音待機中", "Ready to record")
            }
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_CONFIRM_WIDGET_AI = "confirm_widget_ai"
        private const val STATE_FINAL_TRANSCRIPT_EXPANDED = "final_transcript_expanded"
        private const val COLLAPSED_RESULT_LINES = 8
        private const val APPROXIMATE_CHARACTERS_PER_LINE = 36
        private val CLOUD_AUDIO_PROVIDERS = setOf(
            ProviderIds.OPENAI_WHISPER,
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION
        )
        private val CLOUD_SUMMARY_PROVIDERS = setOf(
            ProviderIds.OPENAI_RESPONSES_SUMMARY,
            ProviderIds.GEMINI_FLASH_LITE_SUMMARY
        )
        private val CLOUD_FORMATTING_PROVIDERS = setOf(
            ProviderIds.OPENAI_RESPONSES_FORMATTING,
            ProviderIds.GEMINI_FLASH_LITE_FORMATTING
        )
        private val OPENAI_PROVIDERS = setOf(
            ProviderIds.OPENAI_WHISPER,
            ProviderIds.OPENAI_RESPONSES_FORMATTING,
            ProviderIds.OPENAI_RESPONSES_SUMMARY
        )
        private val GEMINI_PROVIDERS = setOf(
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION,
            ProviderIds.GEMINI_FLASH_LITE_FORMATTING,
            ProviderIds.GEMINI_FLASH_LITE_SUMMARY
        )
    }
}
