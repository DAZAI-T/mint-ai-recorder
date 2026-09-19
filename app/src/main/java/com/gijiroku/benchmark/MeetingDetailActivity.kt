package com.gijiroku.benchmark

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.gijiroku.benchmark.databinding.ActivityMeetingDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class MeetingDetailActivity : SecureActivity() {
    private lateinit var binding: ActivityMeetingDetailBinding
    private lateinit var meetingId: String
    private var persistedText = ""
    private var evidence: List<SummaryEvidence> = emptyList()
    private var audioAvailable = false
    private var loaded = false
    private var selectedEvidenceRange: TimeRange? = null
    private var playingEvidence = false
    private var editing = false
    private var audioDurationMs = 0L
    private var playbackPositionMs = 0L
    private var fullPlaybackActive = false
    private var fullPlaybackPaused = false
    private var restartAfterSeek = false
    private var summaryExpanded = false
    private var transcriptExpanded = false
    private lateinit var recordingPlayer: EncryptedRecordingPlayer
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackProgressUpdater = object : Runnable {
        override fun run() {
            if (!fullPlaybackActive) return
            if (!fullPlaybackPaused) {
                recordingPlayer.currentPositionMs()?.let {
                    playbackPositionMs = it.coerceIn(0L, audioDurationMs)
                    updatePlaybackUi()
                }
            }
            playbackHandler.postDelayed(this, PLAYBACK_PROGRESS_INTERVAL_MS)
        }
    }

    private val createMarkdownDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeetingExportFormat.MARKDOWN.mimeType)
    ) { uri -> uri?.let { writeExport(it, MeetingExportFormat.MARKDOWN) } }

    private val createPdfDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeetingExportFormat.PDF.mimeType)
    ) { uri -> uri?.let { writeExport(it, MeetingExportFormat.PDF) } }

    private val createAudioDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> uri?.let(::writeAudioExport) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meetingId = intent.getStringExtra(EXTRA_MEETING_ID).orEmpty()
        if (!MEETING_ID.matches(meetingId)) {
            finish()
            return
        }
        binding = ActivityMeetingDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val restoredStart = savedInstanceState?.getLong(STATE_EVIDENCE_START, -1L) ?: -1L
        val restoredEnd = savedInstanceState?.getLong(STATE_EVIDENCE_END, -1L) ?: -1L
        if (restoredStart >= 0 && restoredEnd >= restoredStart) {
            selectedEvidenceRange = TimeRange(restoredStart, restoredEnd)
        }
        playbackPositionMs = savedInstanceState?.getLong(STATE_PLAYBACK_POSITION, 0L) ?: 0L
        editing = savedInstanceState?.getBoolean(STATE_EDITING, false) == true
        summaryExpanded = savedInstanceState?.getBoolean(STATE_SUMMARY_EXPANDED, false) == true
        transcriptExpanded = savedInstanceState?.getBoolean(STATE_TRANSCRIPT_EXPANDED, false) == true
        recordingPlayer = EncryptedRecordingPlayer(this)
        binding.buttonSaveEdit.setOnClickListener { if (editing) saveEdit() else setEditing(true) }
        binding.buttonCancelEdit.setOnClickListener { cancelEdit() }
        binding.buttonVersions.setOnClickListener { showVersions() }
        binding.buttonEvidence.setOnClickListener { showEvidence() }
        binding.buttonReprocess.setOnClickListener { openForReprocessing() }
        binding.buttonPlayEvidence.setOnClickListener { toggleEvidencePlayback() }
        binding.buttonPlayPause.setOnClickListener { toggleFullPlayback() }
        binding.buttonReplayTen.setOnClickListener { skipFullPlayback(-SKIP_INTERVAL_MS) }
        binding.buttonForwardTen.setOnClickListener { skipFullPlayback(SKIP_INTERVAL_MS) }
        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                playbackPositionMs = progress.toLong().coerceIn(0L, audioDurationMs)
                updatePlaybackTime()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                restartAfterSeek = fullPlaybackActive && !fullPlaybackPaused
                stopFullPlayback(keepPosition = true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (restartAfterSeek) startFullPlayback(playbackPositionMs)
                restartAfterSeek = false
            }
        })
        binding.buttonExportMeeting.setOnClickListener { showExportWarning() }
        binding.buttonExportAudio.setOnClickListener { showAudioExportWarning() }
        binding.buttonDeleteMeeting.setOnClickListener { showDeleteChoices() }
        binding.buttonToggleSummary.setOnClickListener {
            summaryExpanded = !summaryExpanded
            updateSummaryExpansion()
        }
        binding.buttonToggleTranscript.setOnClickListener {
            transcriptExpanded = !transcriptExpanded
            updateTranscriptExpansion()
        }
        binding.buttonJumpTranscript.setOnClickListener {
            binding.meetingScroll.smoothScrollTo(0, binding.textTranscriptPreview.top)
        }
        binding.buttonBackToTop.setOnClickListener { binding.meetingScroll.smoothScrollTo(0, 0) }
        loadMeeting()
    }

    override fun onStop() {
        stopAllPlayback()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (!loaded || !editing) return
        val draft = binding.editTranscript.text.toString()
        if (draft != persistedText) {
            runCatching {
                EncryptedMeetingDatabase(this).use {
                    it.saveWorkingDraft(meetingId, ARTIFACT_FORMATTED, draft)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedEvidenceRange?.let {
            outState.putLong(STATE_EVIDENCE_START, it.startMs)
            outState.putLong(STATE_EVIDENCE_END, it.endMs)
        }
        outState.putBoolean(STATE_EDITING, editing)
        outState.putBoolean(STATE_SUMMARY_EXPANDED, summaryExpanded)
        outState.putBoolean(STATE_TRANSCRIPT_EXPANDED, transcriptExpanded)
        outState.putLong(STATE_PLAYBACK_POSITION, playbackPositionMs)
        super.onSaveInstanceState(outState)
    }

    private fun loadMeeting(message: String? = null) {
        loaded = false
        binding.textMeetingInfo.text = AppLanguage.text("会議を読み込んでいます…", "Loading meeting…")
        Thread {
            val result = runCatching {
                EncryptedMeetingDatabase(this).use { database ->
                    val metadata = database.readMeeting(meetingId) ?: error("Meeting not found")
                    val current = database.listCurrentRevisions(meetingId)
                    val byArtifact = current.associateBy(ArtifactRevisionRecord::artifactId)
                    val document = MeetingExportDocumentBuilder.build(metadata, current)
                    val summary = byArtifact[ARTIFACT_SUMMARY]?.let {
                        runCatching { PipelineArtifactCodec.decodeSummary(it.content) }.getOrNull()
                    }
                    val draft = database.readWorkingDraft(meetingId, ARTIFACT_FORMATTED)?.content
                        ?.let(TranscriptTimestampParser::normalizeMillisecondRanges)
                    DetailData(
                        metadata = metadata,
                        editableText = document.transcript,
                        draft = draft,
                        summary = summary,
                        versionCount = database.listRevisions(meetingId, ARTIFACT_FORMATTED).size,
                        providerRunCount = database.listProviderRuns(meetingId).size
                    )
                }
            }
            runOnUiThread {
                result.onSuccess { data -> render(data, message) }
                    .onFailure {
                        binding.textMeetingInfo.text = AppLanguage.text("会議を読み込めませんでした", "Could not load meeting")
                        Toast.makeText(this, AppLanguage.text("会議を読み込めませんでした", "Could not load meeting"), Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    private fun render(data: DetailData, message: String?) {
        val created = DATE_FORMATTER.format(Instant.ofEpochMilli(data.metadata.createdAtEpochMs))
        val duration = data.metadata.durationMs?.let(::formatDuration) ?: AppLanguage.text("時間不明", "Unknown duration")
        val audioState = when (data.metadata.audioState) {
            AudioState.RECORDING -> AppLanguage.text("録音未完了", "Recording incomplete")
            AudioState.FINALIZED -> AppLanguage.text("音声あり", "Audio available")
            AudioState.DELETED -> AppLanguage.text("音声削除済み", "Audio deleted")
        }
        binding.textMeetingInfo.text = "$created ・ $duration ・ $audioState\n" +
            AppLanguage.text("本文 ${data.versionCount}版／処理履歴 ${data.providerRunCount}件", "${data.versionCount} text versions / ${data.providerRunCount} processing records")
        evidence = data.summary?.evidence.orEmpty()
        binding.textSummary.text = data.summary?.let(::summaryPreview) ?: AppLanguage.text("要約はありません", "No summary")
        binding.textSummary.movementMethod = if (data.summary?.evidence.orEmpty().isEmpty()) null
        else LinkMovementMethod.getInstance()
        binding.textSummary.highlightColor = Color.TRANSPARENT
        updateSummaryExpansion()
        binding.buttonEvidence.isEnabled = evidence.isNotEmpty()
        val displayedText = data.draft ?: data.editableText
        binding.editTranscript.setText(displayedText)
        bindTranscriptPreview(displayedText)
        updateTranscriptExpansion()
        bindTimestampLinks(displayedText)
        persistedText = data.editableText
        audioAvailable = data.metadata.audioState == AudioState.FINALIZED &&
            RecordingStore(this).find(meetingId)?.let { File(it.encryptedFilePath).isFile } == true
        audioDurationMs = data.metadata.durationMs
            ?: RecordingStore(this).find(meetingId)?.durationMs
            ?: 0L
        playbackPositionMs = playbackPositionMs.coerceIn(0L, audioDurationMs)
        val fullPlaybackAvailable = audioAvailable && audioDurationMs > 0L
        binding.seekPlayback.max = audioDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        binding.seekPlayback.isEnabled = fullPlaybackAvailable
        binding.buttonPlayPause.isEnabled = fullPlaybackAvailable
        binding.buttonReplayTen.isEnabled = fullPlaybackAvailable
        binding.buttonForwardTen.isEnabled = fullPlaybackAvailable
        binding.textPlaybackStatus.text = when {
            data.metadata.audioState == AudioState.DELETED -> AppLanguage.text("音声データは削除されています", "Audio data has been deleted")
            !audioAvailable -> AppLanguage.text("再生できる音声がありません", "No audio available for playback")
            audioDurationMs <= 0L -> AppLanguage.text("音声の長さを取得できません", "Cannot determine audio duration")
            else -> AppLanguage.text("端末内の暗号化音声を再生します", "Playing encrypted audio on this device")
        }
        updatePlaybackUi()
        binding.buttonReprocess.isEnabled = audioAvailable
        binding.buttonExportAudio.isEnabled = audioAvailable
        binding.buttonPlayEvidence.isEnabled = audioAvailable && selectedEvidenceRange != null
        binding.buttonVersions.isEnabled = data.versionCount > 0
        binding.buttonDeleteMeeting.isEnabled = true
        binding.textEvidencePosition.text = when {
            message != null -> message
            data.draft != null && data.draft != data.editableText -> AppLanguage.text("未保存の編集を復元しました", "Restored unsaved edits")
            selectedEvidenceRange != null -> AppLanguage.text("選択済みの根拠時刻から音声を再生できます", "Play audio from the selected evidence timestamp")
            else -> AppLanguage.text("根拠時刻はまだ選択されていません", "No evidence timestamp selected")
        }
        if (data.draft != null && data.draft != data.editableText) editing = true
        setEditing(editing)
        loaded = true
    }

    private fun setEditing(enabled: Boolean) {
        editing = enabled
        binding.textTranscriptPreview.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.editTranscript.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.buttonCancelEdit.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.buttonSaveEdit.text = if (enabled) AppLanguage.text("編集を保存", "Save edits") else AppLanguage.text("編集する", "Edit")
        updateTranscriptExpansion()
        if (enabled) {
            binding.editTranscript.requestFocus()
            binding.editTranscript.setSelection(binding.editTranscript.length())
        }
    }

    private fun cancelEdit() {
        val changed = binding.editTranscript.text.toString() != persistedText
        if (!changed) {
            setEditing(false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("編集を破棄しますか？", "Discard edits?"))
            .setMessage(AppLanguage.text("保存していない変更と復元用の下書きを削除します。", "Unsaved changes and the recovery draft will be deleted."))
            .setNegativeButton(AppLanguage.text("編集を続ける", "Keep editing"), null)
            .setPositiveButton(AppLanguage.text("破棄", "Discard")) { _, _ ->
                binding.editTranscript.setText(persistedText)
                bindTranscriptPreview(persistedText)
                bindTimestampLinks(persistedText)
                runCatching {
                    EncryptedMeetingDatabase(this).use {
                        it.deleteWorkingDraft(meetingId, ARTIFACT_FORMATTED)
                    }
                }
                setEditing(false)
            }
            .show()
    }

    private fun saveEdit() {
        val updated = binding.editTranscript.text.toString().trim()
        if (updated.isEmpty()) {
            Toast.makeText(this, AppLanguage.text("空の文字起こしは保存できません", "Cannot save an empty transcript"), Toast.LENGTH_SHORT).show()
            return
        }
        binding.buttonSaveEdit.isEnabled = false
        Thread {
            val result = runCatching {
                EncryptedMeetingDatabase(this).use { database ->
                    val currentFormatted = database.currentRevision(meetingId, ARTIFACT_FORMATTED)
                    val transcript = database.currentRevision(meetingId, ARTIFACT_TRANSCRIPT)
                    val artifact = TextDocumentArtifact(
                        text = updated,
                        provenance = ArtifactProvenance(
                            providerId = USER_EDIT_PROVIDER,
                            createdAtEpochMs = System.currentTimeMillis(),
                            inputHash = ArtifactHasher.sha256(updated)
                        )
                    )
                    database.appendRevision(
                        meetingId,
                        ArtifactRevisionDraft(
                            artifactId = ARTIFACT_FORMATTED,
                            content = PipelineArtifactCodec.encodeDocument(artifact),
                            revisionKind = RevisionKind.USER_EDITED,
                            parentRevisionId = currentFormatted?.revisionId,
                            inputRevisionIds = listOfNotNull(transcript?.revisionId),
                            providerId = USER_EDIT_PROVIDER,
                            author = "USER",
                            sourceRanges = currentFormatted?.sourceRanges ?: transcript?.sourceRanges.orEmpty()
                        )
                    )
                    database.clearCurrentRevision(meetingId, ARTIFACT_SUMMARY)
                    database.deleteWorkingDraft(meetingId, ARTIFACT_FORMATTED)
                }
            }
            runOnUiThread {
                binding.buttonSaveEdit.isEnabled = true
                result.onSuccess {
                    editing = false
                    loadMeeting(AppLanguage.text("編集を新しい版として保存しました。要約は再作成できます。", "Edits saved as a new version. You can regenerate the summary."))
                }
                    .onFailure { Toast.makeText(this, AppLanguage.text("編集を保存できませんでした", "Could not save edits"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showVersions() {
        Thread {
            val result = runCatching {
                EncryptedMeetingDatabase(this).use {
                    it.listRevisions(meetingId, ARTIFACT_FORMATTED).sortedByDescending(ArtifactRevisionRecord::createdAtEpochMs)
                }
            }
            runOnUiThread {
                result.onSuccess { versions ->
                    if (versions.isEmpty()) {
                        Toast.makeText(this, AppLanguage.text("比較できる本文の版はありません", "No text versions to compare"), Toast.LENGTH_SHORT).show()
                    } else showVersionPicker(versions)
                }.onFailure {
                    Toast.makeText(this, AppLanguage.text("版を読み込めませんでした", "Could not load versions"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showVersionPicker(versions: List<ArtifactRevisionRecord>) {
        val labels = versions.map { revision ->
            val time = DATE_FORMATTER.format(Instant.ofEpochMilli(revision.createdAtEpochMs))
            AppLanguage.text("$time ・ ${revisionKindLabel(revision.revisionKind)} ・ ${revision.providerId ?: "Providerなし"}", "$time · ${revisionKindLabel(revision.revisionKind)} · ${revision.providerId ?: "No provider"}")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("比較する版を選択", "Choose a version to compare"))
            .setItems(labels) { _, which -> compareVersion(versions[which]) }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun compareVersion(selected: ArtifactRevisionRecord) {
        val older = decodeDocumentText(selected)
        val current = displayedTranscript()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("選択版 −／現在表示 ＋", "Selected version − / Current display +"))
            .setMessage(TextVersionDiff.compare(older, current))
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .setPositiveButton(AppLanguage.text("この版を復元", "Restore this version")) { _, _ -> restoreVersion(selected.revisionId) }
            .show()
    }

    private fun restoreVersion(revisionId: String) {
        Thread {
            val result = runCatching {
                EncryptedMeetingDatabase(this).use { database ->
                    database.restoreRevision(meetingId, revisionId)
                    database.clearCurrentRevision(meetingId, ARTIFACT_SUMMARY)
                    database.deleteWorkingDraft(meetingId, ARTIFACT_FORMATTED)
                }
            }
            runOnUiThread {
                result.onSuccess { loadMeeting(AppLanguage.text("選択した内容を新しい復元版として保存しました", "Saved selected content as a new restored version")) }
                    .onFailure { Toast.makeText(this, AppLanguage.text("版を復元できませんでした", "Could not restore version"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showEvidence() {
        if (evidence.isEmpty()) return
        val labels = evidence.map(::evidenceLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("要約の根拠時刻", "Summary evidence timestamps"))
            .setItems(labels) { _, which -> moveToEvidence(evidence[which]) }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun moveToEvidence(item: SummaryEvidence) {
        val range = item.sourceRanges.firstOrNull() ?: return
        selectedEvidenceRange = range
        val full = formatTimestamp(range.startMs)
        val text = displayedTranscript()
        val nearestTimestamp = TranscriptTimestampParser.closestTo(text, range.startMs)
        if (!editing) {
            transcriptExpanded = true
            updateTranscriptExpansion()
        }
        scrollTranscriptTo(nearestTimestamp?.textStart ?: 0)
        binding.textEvidencePosition.text = AppLanguage.text("根拠: $full〜${formatTimestamp(range.endMs)}（${fieldLabel(item)}）", "Evidence: $full–${formatTimestamp(range.endMs)} (${fieldLabel(item)})")
        binding.buttonPlayEvidence.isEnabled = audioAvailable
    }

    private fun activateEvidence(field: String, itemIndex: Int?) {
        val item = evidence.firstOrNull { it.field == field && it.itemIndex == itemIndex }
            ?: evidence.firstOrNull { it.field == field }
            ?: return
        moveToEvidence(item)
        if (audioAvailable) {
            recordingPlayer.stop()
            playingEvidence = false
            startSelectedPlayback()
        }
    }

    private fun toggleEvidencePlayback() {
        if (playingEvidence) {
            stopEvidencePlayback()
            return
        }
        startSelectedPlayback()
    }

    private fun startSelectedPlayback() {
        val range = selectedEvidenceRange ?: return
        val reference = RecordingStore(this).find(meetingId) ?: return
        stopFullPlayback(keepPosition = true)
        playingEvidence = true
        binding.buttonPlayEvidence.text = AppLanguage.text("音声を停止", "Stop audio")
        val startMs = range.startMs.coerceAtLeast(0)
        val endMs = range.endMs.coerceAtLeast(startMs + MIN_EVIDENCE_PLAYBACK_MS)
        recordingPlayer.play(reference, startMs, endMs) { error ->
            runOnUiThread {
                playingEvidence = false
                binding.buttonPlayEvidence.text = AppLanguage.text("選択位置から再生", "Play from selected position")
                if (error != null) {
                    Toast.makeText(this, AppLanguage.text("根拠音声を再生できませんでした", "Could not play evidence audio"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopEvidencePlayback() {
        if (::recordingPlayer.isInitialized) recordingPlayer.stop()
        playingEvidence = false
        if (::binding.isInitialized) binding.buttonPlayEvidence.text = AppLanguage.text("選択位置から再生", "Play from selected position")
    }

    private fun toggleFullPlayback() {
        when {
            fullPlaybackActive && !fullPlaybackPaused -> pauseFullPlayback()
            fullPlaybackActive && fullPlaybackPaused -> resumeFullPlayback()
            else -> startFullPlayback(playbackPositionMs)
        }
    }

    private fun startFullPlayback(requestedPositionMs: Long) {
        if (!audioAvailable || audioDurationMs <= 0L) return
        val reference = RecordingStore(this).find(meetingId) ?: return
        stopEvidencePlayback()
        val startMs = requestedPositionMs
            .takeIf { it < audioDurationMs }
            ?.coerceAtLeast(0L)
            ?: 0L
        playbackPositionMs = startMs
        fullPlaybackActive = true
        fullPlaybackPaused = false
        binding.textPlaybackStatus.text = AppLanguage.text("再生中", "Playing")
        updatePlaybackUi()
        playbackHandler.removeCallbacks(playbackProgressUpdater)
        playbackHandler.post(playbackProgressUpdater)
        recordingPlayer.play(reference, startMs, audioDurationMs) { error ->
            runOnUiThread {
                if (!fullPlaybackActive) return@runOnUiThread
                fullPlaybackActive = false
                fullPlaybackPaused = false
                playbackHandler.removeCallbacks(playbackProgressUpdater)
                if (error == null) playbackPositionMs = audioDurationMs
                binding.textPlaybackStatus.text = if (error == null) {
                    AppLanguage.text("再生が終わりました", "Playback finished")
                } else {
                    AppLanguage.text("音声を再生できませんでした", "Could not play audio")
                }
                updatePlaybackUi()
                if (error != null) {
                    Toast.makeText(this, AppLanguage.text("音声を再生できませんでした", "Could not play audio"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pauseFullPlayback() {
        recordingPlayer.currentPositionMs()?.let { playbackPositionMs = it.coerceIn(0L, audioDurationMs) }
        if (!recordingPlayer.pause()) {
            stopFullPlayback(keepPosition = true)
            return
        }
        fullPlaybackPaused = true
        binding.textPlaybackStatus.text = AppLanguage.text("一時停止中", "Paused")
        updatePlaybackUi()
    }

    private fun resumeFullPlayback() {
        if (!recordingPlayer.resume()) {
            fullPlaybackActive = false
            fullPlaybackPaused = false
            startFullPlayback(playbackPositionMs)
            return
        }
        fullPlaybackPaused = false
        binding.textPlaybackStatus.text = AppLanguage.text("再生中", "Playing")
        updatePlaybackUi()
    }

    private fun skipFullPlayback(deltaMs: Long) {
        val wasPlaying = fullPlaybackActive && !fullPlaybackPaused
        val current = recordingPlayer.currentPositionMs() ?: playbackPositionMs
        stopFullPlayback(keepPosition = false)
        playbackPositionMs = (current + deltaMs).coerceIn(0L, audioDurationMs)
        if (wasPlaying) startFullPlayback(playbackPositionMs) else updatePlaybackUi()
    }

    private fun stopFullPlayback(keepPosition: Boolean) {
        if (fullPlaybackActive && keepPosition) {
            recordingPlayer.currentPositionMs()?.let {
                playbackPositionMs = it.coerceIn(0L, audioDurationMs)
            }
        }
        fullPlaybackActive = false
        fullPlaybackPaused = false
        playbackHandler.removeCallbacks(playbackProgressUpdater)
        if (::recordingPlayer.isInitialized) recordingPlayer.stop()
        if (::binding.isInitialized) {
            if (audioAvailable && audioDurationMs > 0L) binding.textPlaybackStatus.text = AppLanguage.text("停止中", "Stopped")
            updatePlaybackUi()
        }
    }

    private fun stopAllPlayback() {
        stopFullPlayback(keepPosition = true)
        stopEvidencePlayback()
    }

    private fun updatePlaybackUi() {
        if (!::binding.isInitialized) return
        val progress = playbackPositionMs.coerceIn(0L, audioDurationMs)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (binding.seekPlayback.progress != progress) binding.seekPlayback.progress = progress
        binding.buttonPlayPause.text = if (fullPlaybackActive && !fullPlaybackPaused) {
            AppLanguage.text("Ⅱ 一時停止", "Ⅱ Pause")
        } else {
            AppLanguage.text("▶ 再生", "▶ Play")
        }
        updatePlaybackTime()
    }

    private fun updatePlaybackTime() {
        binding.textPlaybackTime.text =
            "${formatDuration(playbackPositionMs)} / ${formatDuration(audioDurationMs)}"
    }

    private fun bindTimestampLinks(transcript: String) {
        val timestamps = TranscriptTimestampParser.parse(transcript).take(MAX_TIMESTAMP_SHORTCUTS)
        if (timestamps.isEmpty()) {
            binding.textTimestampLinks.text = AppLanguage.text("この文字起こしには再生できるタイムスタンプがありません", "This transcript has no playable timestamps")
            binding.textTimestampLinks.movementMethod = null
            return
        }
        val prefix = AppLanguage.text("タップして再生: ", "Tap to play: ")
        val plain = prefix + timestamps.joinToString("  ") { it.label }
        val spannable = SpannableString(plain)
        var searchFrom = prefix.length
        timestamps.forEach { timestamp ->
            val start = plain.indexOf(timestamp.label, searchFrom)
            if (start >= 0) {
                val end = start + timestamp.label.length
                spannable.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            playFromTimestamp(timestamp)
                        }
                    },
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                searchFrom = end
            }
        }
        binding.textTimestampLinks.text = spannable
        binding.textTimestampLinks.movementMethod = LinkMovementMethod.getInstance()
        binding.textTimestampLinks.highlightColor = Color.TRANSPARENT
    }

    private fun bindTranscriptPreview(transcript: String) {
        if (transcript.isBlank()) {
            binding.textTranscriptPreview.text = AppLanguage.text("文字起こしはまだありません", "No transcript yet")
            binding.textTranscriptPreview.movementMethod = null
            return
        }
        val preview = SpannableString(transcript)
        TranscriptTimestampParser.parse(transcript).forEach { timestamp ->
            preview.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = playFromTimestamp(timestamp)
                },
                timestamp.textStart,
                timestamp.textEndExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.textTranscriptPreview.text = preview
        binding.textTranscriptPreview.movementMethod = LinkMovementMethod.getInstance()
        binding.textTranscriptPreview.highlightColor = Color.TRANSPARENT
    }

    private fun updateSummaryExpansion() {
        val text = binding.textSummary.text?.toString().orEmpty()
        val canExpand = isLongText(text, COLLAPSED_SUMMARY_LINES)
        binding.textSummary.maxLines = if (summaryExpanded) Int.MAX_VALUE else COLLAPSED_SUMMARY_LINES
        binding.textSummary.ellipsize = if (summaryExpanded) null else TextUtils.TruncateAt.END
        binding.buttonToggleSummary.visibility = if (canExpand) View.VISIBLE else View.GONE
        binding.buttonToggleSummary.text = getString(if (summaryExpanded) R.string.ui_121 else R.string.ui_120)
    }

    private fun updateTranscriptExpansion() {
        val text = binding.textTranscriptPreview.text?.toString().orEmpty()
        val canExpand = isLongText(text, COLLAPSED_TRANSCRIPT_LINES)
        binding.textTranscriptPreview.maxLines = if (transcriptExpanded) Int.MAX_VALUE else COLLAPSED_TRANSCRIPT_LINES
        binding.textTranscriptPreview.ellipsize = if (transcriptExpanded) null else TextUtils.TruncateAt.END
        binding.buttonToggleTranscript.visibility = if (!editing && canExpand) View.VISIBLE else View.GONE
        binding.buttonToggleTranscript.text = getString(if (transcriptExpanded) R.string.ui_121 else R.string.ui_120)
    }

    private fun isLongText(text: String, collapsedLines: Int): Boolean =
        text.lineSequence().count() > collapsedLines ||
            text.length > collapsedLines * APPROXIMATE_CHARACTERS_PER_LINE

    private fun displayedTranscript(): String = if (editing) {
        binding.editTranscript.text.toString()
    } else {
        binding.textTranscriptPreview.text.toString()
    }

    private fun scrollTranscriptTo(characterIndex: Int) {
        if (editing) {
            binding.editTranscript.setSelection(characterIndex.coerceAtMost(binding.editTranscript.length()))
            binding.editTranscript.post {
                val layout = binding.editTranscript.layout ?: return@post
                val line = layout.getLineForOffset(characterIndex.coerceIn(0, binding.editTranscript.length()))
                binding.meetingScroll.smoothScrollTo(0, binding.editTranscript.top + layout.getLineTop(line))
            }
        } else {
            binding.textTranscriptPreview.post {
                val layout = binding.textTranscriptPreview.layout ?: return@post
                val line = layout.getLineForOffset(
                    characterIndex.coerceIn(0, binding.textTranscriptPreview.length())
                )
                binding.meetingScroll.smoothScrollTo(
                    0,
                    binding.textTranscriptPreview.top + layout.getLineTop(line)
                )
            }
        }
    }

    private fun playFromTimestamp(timestamp: TranscriptTimestamp) {
        if (!audioAvailable) {
            Toast.makeText(this, AppLanguage.text("この会議の音声は利用できません", "Audio is unavailable for this meeting"), Toast.LENGTH_SHORT).show()
            return
        }
        selectedEvidenceRange = timestamp.range
        if (!editing) {
            transcriptExpanded = true
            updateTranscriptExpansion()
        }
        scrollTranscriptTo(timestamp.textStart)
        binding.textEvidencePosition.text =
            AppLanguage.text("再生位置: ${timestamp.label}〜${formatTimestamp(timestamp.range.endMs)}", "Position: ${timestamp.label}–${formatTimestamp(timestamp.range.endMs)}")
        binding.buttonPlayEvidence.isEnabled = true
        recordingPlayer.stop()
        playingEvidence = false
        startSelectedPlayback()
    }

    private fun openForReprocessing() {
        if (!audioAvailable) return
        val reference = RecordingStore(this).find(meetingId) ?: return
        RecordingState.lastRecording.value = reference
        startActivity(Intent(this, RecordActivity::class.java).putExtra(RecordActivity.EXTRA_MEETING_ID, meetingId))
    }

    private fun showExportWarning() {
        if (editing && binding.editTranscript.text.toString() != persistedText) {
            Toast.makeText(this, AppLanguage.text("編集内容を保存してから書き出してください", "Save edits before exporting"), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("暗号化されない書き出しです", "This export is not encrypted"))
            .setMessage(
                AppLanguage.text("保存した議事録ファイルは、このアプリの暗号化と削除の対象外になります。", "Exported minutes are outside this app's encryption and deletion controls. ") +
                    AppLanguage.text("保存先で適切に管理・削除してください。", "Manage and delete them securely at the destination.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("形式を選ぶ", "Choose format")) { _, _ -> showExportFormats() }
            .show()
    }

    private fun showAudioExportWarning() {
        if (!audioAvailable) {
            Toast.makeText(this, AppLanguage.text("保存できる音声データがありません", "No audio data to save"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("暗号化されない音声を保存します", "Save unencrypted audio"))
            .setMessage(
                AppLanguage.text("保存先には通常のWAVファイルとして書き出され、このアプリの暗号化・削除・", "Audio will be exported as a standard WAV file, outside this app's encryption, deletion, and ") +
                    AppLanguage.text("スクリーンショット保護の対象外になります。保存先で安全に管理してください。", "screenshot protection. Manage the file securely at the destination.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("保存先を選ぶ", "Choose destination")) { _, _ ->
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date())
                createAudioDocument.launch("recording-$timestamp.wav")
            }
            .show()
    }

    private fun writeAudioExport(uri: Uri) {
        val reference = RecordingStore(this).find(meetingId) ?: return
        binding.buttonExportAudio.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        EncryptedRecordingWavExporter(this@MeetingDetailActivity).write(reference, output)
                    } ?: error(AppLanguage.text("保存先を開けません", "Cannot open destination"))
                }
            }
            result.onSuccess {
                Toast.makeText(this@MeetingDetailActivity, AppLanguage.text("音声ファイルを保存しました", "Audio file saved"), Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(
                    this@MeetingDetailActivity,
                    error.message ?: AppLanguage.text("音声ファイルを保存できませんでした", "Could not save audio file"),
                    Toast.LENGTH_LONG
                ).show()
            }
            binding.buttonExportAudio.isEnabled = audioAvailable
        }
    }

    private fun showExportFormats() {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("議事録を書き出す", "Export minutes"))
            .setItems(arrayOf(AppLanguage.text("Markdownファイル", "Markdown file"), AppLanguage.text("PDFファイル", "PDF file"))) { _, which ->
                val format = if (which == 0) MeetingExportFormat.MARKDOWN else MeetingExportFormat.PDF
                val filename = defaultExportFilename(format)
                when (format) {
                    MeetingExportFormat.MARKDOWN -> createMarkdownDocument.launch(filename)
                    MeetingExportFormat.PDF -> createPdfDocument.launch(filename)
                }
            }
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .show()
    }

    private fun writeExport(uri: Uri, format: MeetingExportFormat) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val document = MeetingExportService(this@MeetingDetailActivity).buildDocument(meetingId)
                    check(document.transcript.isNotBlank() || document.summary != null) {
                        AppLanguage.text("確定処理後の議事録がまだありません", "No processed minutes yet")
                    }
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        when (format) {
                            MeetingExportFormat.MARKDOWN -> document.writeMarkdown(output)
                            MeetingExportFormat.PDF -> AndroidMeetingPdfWriter.write(document, output)
                        }
                    } ?: error(AppLanguage.text("保存先を開けません", "Cannot open destination"))
                }
            }
            result.onSuccess {
                Toast.makeText(this@MeetingDetailActivity, AppLanguage.text("議事録を保存しました", "Minutes saved"), Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(
                    this@MeetingDetailActivity,
                    error.message ?: AppLanguage.text("議事録を保存できませんでした", "Could not save minutes"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDeleteChoices() {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("削除するデータを選択", "Choose data to delete"))
            .setItems(arrayOf(AppLanguage.text("削除", "Delete"), AppLanguage.text("音声データのみ削除", "Delete audio only"))) { _, which ->
                if (which == 0) confirmFullDeletion() else confirmAudioDeletion()
            }
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .show()
    }

    private fun confirmFullDeletion() {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("会議を完全に削除しますか？", "Permanently delete this meeting?"))
            .setMessage(
                AppLanguage.text("録音、文字起こし、話者ラベル、要約、編集履歴の鍵を直ちに破棄します。", "Keys for the recording, transcript, speaker labels, summary, and edit history will be destroyed immediately. ") +
                    AppLanguage.text("この操作は取り消せません。書き出し済みのファイルは削除されません。", "This cannot be undone. Exported files will not be deleted.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("完全に削除", "Delete permanently")) { _, _ -> executeDeletion(audioOnly = false) }
            .show()
    }

    private fun confirmAudioDeletion() {
        if (!audioAvailable) {
            Toast.makeText(this, AppLanguage.text("音声データはすでに削除されています", "Audio data is already deleted"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("音声データのみ削除しますか？", "Delete audio data only?"))
            .setMessage(
                AppLanguage.text("録音音声の鍵を直ちに破棄します。議事録と編集履歴は残りますが、", "The audio encryption key will be destroyed immediately. Minutes and edit history remain, but ") +
                    AppLanguage.text("再生・再文字起こし・再話者分離はできなくなります。この操作は取り消せません。", "playback, retranscription, and speaker separation will no longer be possible. This cannot be undone.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("音声のみ削除", "Delete audio only")) { _, _ -> executeDeletion(audioOnly = true) }
            .show()
    }

    private fun executeDeletion(audioOnly: Boolean) {
        val reference = RecordingStore(this).find(meetingId) ?: run {
            Toast.makeText(this, AppLanguage.text("削除対象を確認できませんでした", "Could not identify the data to delete"), Toast.LENGTH_LONG).show()
            return
        }
        stopAllPlayback()
        binding.buttonDeleteMeeting.isEnabled = false
        Thread {
            val result = runCatching {
                val store = RecordingStore(this)
                if (audioOnly) store.deleteAudio(reference) else store.deleteMeeting(reference)
            }
            runOnUiThread {
                result.onSuccess { deletion ->
                    if (RecordingState.lastRecording.value?.meetingId == meetingId) {
                        RecordingState.lastRecording.value = null
                    }
                    val cleanup = if (deletion.encryptedFileRemoved) "" else
                        AppLanguage.text(" 暗号ファイルは残りましたが、鍵破棄済みで復号できません。", " Encrypted files remain, but the keys are destroyed and the files cannot be decrypted.")
                    Toast.makeText(
                        this,
                        (if (audioOnly) AppLanguage.text("音声データを削除しました。", "Audio data deleted.") else AppLanguage.text("会議を削除しました。", "Meeting deleted.")) + cleanup,
                        Toast.LENGTH_LONG
                    ).show()
                    if (audioOnly) loadMeeting() else finish()
                }.onFailure { error ->
                    binding.buttonDeleteMeeting.isEnabled = true
                    Toast.makeText(this, AppLanguage.text("削除できませんでした: ${error.message}", "Could not delete: ${error.message}"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun defaultExportFilename(format: MeetingExportFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date())
        return "gijiroku-$timestamp.${format.extension}"
    }

    private fun summaryPreview(summary: MeetingSummaryArtifact): CharSequence {
        val out = SpannableStringBuilder()
        fun heading(label: String) {
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(label)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append('\n')
        }
        fun line(value: String, field: String, itemIndex: Int? = null) {
            out.append(value.ifBlank { AppLanguage.text("不明", "Unknown") })
            out.appendEvidenceLink(field, itemIndex)
            out.append('\n')
        }
        if (summary.title.isNotBlank()) {
            val start = out.length
            out.append(summary.title.trim())
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        heading(AppLanguage.text("概要", "Overview"))
        line(summary.summary, "summary")
        if (summary.purpose.isNotBlank()) {
            heading(AppLanguage.text("目的", "Purpose"))
            line(summary.purpose, "purpose")
        }
        if (summary.topics.isNotEmpty()) {
            heading(AppLanguage.text("主な議題・議論", "Topics and discussion"))
            summary.topics.forEachIndexed { index, topic ->
                val speakers = topic.speakers.takeIf { it.isNotEmpty() }
                    ?.joinToString("、", prefix = "（", postfix = "）").orEmpty()
                line("・${topic.topic}: ${topic.discussion}$speakers", "topics", index)
            }
        }
        heading(AppLanguage.text("決定事項", "Decisions"))
        if (summary.decisions.isEmpty()) out.append(AppLanguage.text("なし\n", "None\n"))
        else summary.decisions.forEachIndexed { index, value -> line("・$value", "decisions", index) }
        heading(AppLanguage.text("アクションアイテム", "Action items"))
        if (summary.actionItems.isEmpty()) out.append(AppLanguage.text("なし\n", "None\n"))
        else summary.actionItems.forEachIndexed { index, item ->
            line(
                AppLanguage.text("・${item.description}（担当: ${item.owner ?: "不明"}／期限: ${item.dueDate ?: "不明"}）", "· ${item.description} (Owner: ${item.owner ?: "Unknown"} / Due: ${item.dueDate ?: "Unknown"})"),
                "action_items",
                index
            )
        }
        heading(AppLanguage.text("未解決事項", "Open questions"))
        if (summary.openQuestions.isEmpty()) out.append(AppLanguage.text("なし\n", "None\n"))
        else summary.openQuestions.forEachIndexed { index, value -> line("・$value", "open_questions", index) }
        if (summary.importantInformation.isNotEmpty()) {
            heading(AppLanguage.text("重要情報", "Important information"))
            summary.importantInformation.forEachIndexed { index, value ->
                line("・$value", "important_information", index)
            }
        }
        return out.trimEnd()
    }

    private fun SpannableStringBuilder.appendEvidenceLink(field: String, itemIndex: Int?) {
        val hasEvidence = evidence.any { it.field == field && it.itemIndex == itemIndex }
        if (!hasEvidence) return
        append("  ")
        val start = length
        val range = evidence.first { it.field == field && it.itemIndex == itemIndex }.sourceRanges.firstOrNull()
        append("▶ ").append(range?.startMs?.let(::formatTimestamp) ?: AppLanguage.text("原音", "Original audio"))
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = activateEvidence(field, itemIndex)
            },
            start,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun evidenceLabel(item: SummaryEvidence): String {
        val ranges = item.sourceRanges.joinToString("、") {
            "${formatTimestamp(it.startMs)}〜${formatTimestamp(it.endMs)}"
        }.ifBlank { AppLanguage.text("時刻なし", "No timestamp") }
        return "${fieldLabel(item)} ・ $ranges"
    }

    private fun fieldLabel(item: SummaryEvidence): String = when (item.field) {
        "purpose" -> AppLanguage.text("目的", "Purpose")
        "topics" -> AppLanguage.text("議題${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}", "Topic${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}")
        "decisions" -> AppLanguage.text("決定事項${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}", "Decision${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}")
        "action_items" -> AppLanguage.text("対応項目${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}", "Action${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}")
        "open_questions" -> AppLanguage.text("未解決事項${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}", "Open question${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}")
        "important_information" -> AppLanguage.text("重要情報${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}", "Important information${item.itemIndex?.let { " ${it + 1}" }.orEmpty()}")
        "summary" -> AppLanguage.text("要約", "Summary")
        else -> item.field
    }

    private fun decodeDocumentText(revision: ArtifactRevisionRecord): String =
        TranscriptTimestampParser.normalizeMillisecondRanges(
            runCatching { PipelineArtifactCodec.decodeDocument(revision.content).text }
                .getOrElse { revision.content }
        )

    private fun revisionKindLabel(kind: RevisionKind): String = when (kind) {
        RevisionKind.AI_GENERATED -> AppLanguage.text("AI生成", "AI generated")
        RevisionKind.USER_EDITED -> AppLanguage.text("手動編集", "Manual edit")
        RevisionKind.RESTORED -> AppLanguage.text("復元", "Restored")
        RevisionKind.IMPORTED -> AppLanguage.text("読込", "Imported")
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1_000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        return "%02d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)
    }

    private data class DetailData(
        val metadata: EncryptedMeetingMetadata,
        val editableText: String,
        val draft: String?,
        val summary: MeetingSummaryArtifact?,
        val versionCount: Int,
        val providerRunCount: Int
    )

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
        private const val ARTIFACT_TRANSCRIPT = "transcript"
        private const val ARTIFACT_FORMATTED = "formatted-text"
        private const val ARTIFACT_SUMMARY = "summary"
        private const val USER_EDIT_PROVIDER = "user.edit"
        private const val MIN_EVIDENCE_PLAYBACK_MS = 1_000L
        private const val SKIP_INTERVAL_MS = 10_000L
        private const val PLAYBACK_PROGRESS_INTERVAL_MS = 250L
        private const val MAX_TIMESTAMP_SHORTCUTS = 200
        private const val COLLAPSED_SUMMARY_LINES = 6
        private const val COLLAPSED_TRANSCRIPT_LINES = 8
        private const val APPROXIMATE_CHARACTERS_PER_LINE = 36
        private const val STATE_EVIDENCE_START = "evidence_start_ms"
        private const val STATE_EVIDENCE_END = "evidence_end_ms"
        private const val STATE_EDITING = "editing"
        private const val STATE_SUMMARY_EXPANDED = "summary_expanded"
        private const val STATE_TRANSCRIPT_EXPANDED = "transcript_expanded"
        private const val STATE_PLAYBACK_POSITION = "playback_position_ms"
        private val MEETING_ID = Regex("[0-9a-fA-F-]{36}")
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
