package com.gijiroku.benchmark

import android.content.Intent
import android.app.AlertDialog
import android.view.View
import android.widget.ListView
import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.widget.ArrayAdapter
import android.widget.Toast
import com.gijiroku.benchmark.databinding.ActivityMeetingLibraryBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MeetingLibraryActivity : SecureActivity() {
    private lateinit var binding: ActivityMeetingLibraryBinding
    private var meetingIds: List<String> = emptyList()
    private var meetingLabels: List<CharSequence> = emptyList()
    private val selectedIds = linkedSetOf<String>()
    private var selectionMode = false
    private var deleting = false
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeetingLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.listMeetings.emptyView = binding.textEmptyMeetings
        selectionMode = savedInstanceState?.getBoolean("selectionMode") ?: false
        selectedIds.addAll(savedInstanceState?.getStringArrayList("selectedIds").orEmpty())
        binding.buttonSelectMeetings.setOnClickListener {
            selectionMode = !selectionMode
            selectedIds.clear()
            renderMeetings()
        }
        binding.buttonDeleteSelected.setOnClickListener { confirmDeletion() }
        binding.buttonNewRecording.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, ProviderSettingsActivity::class.java))
        }
        binding.listMeetings.setOnItemClickListener { _, _, position, _ ->
            val meetingId = meetingIds.getOrNull(position) ?: return@setOnItemClickListener
            if (deleting) return@setOnItemClickListener
            if (selectionMode) {
                if (!selectedIds.add(meetingId)) selectedIds.remove(meetingId)
                updateSelectionControls()
                return@setOnItemClickListener
            }
            startActivity(
                Intent(this, MeetingDetailActivity::class.java)
                    .putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meetingId)
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selectionMode", selectionMode)
        outState.putStringArrayList("selectedIds", ArrayList(selectedIds))
        super.onSaveInstanceState(outState)
    }

    private fun renderMeetings() {
        binding.listMeetings.clearChoices()
        binding.listMeetings.choiceMode = if (selectionMode) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_NONE
        binding.listMeetings.adapter = ArrayAdapter(this,
            if (selectionMode) R.layout.item_meeting_selectable else R.layout.item_meeting, meetingLabels)
        meetingIds.forEachIndexed { index, id ->
            if (selectionMode && id in selectedIds) binding.listMeetings.setItemChecked(index, true)
        }
        updateSelectionControls()
    }

    private fun updateSelectionControls() {
        binding.buttonSelectMeetings.text = if (selectionMode) AppLanguage.text("選択をキャンセル", "Cancel selection") else AppLanguage.text("会議を選択", "Select meetings")
        binding.buttonSelectMeetings.isEnabled = !deleting && meetingIds.isNotEmpty()
        binding.buttonDeleteSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.buttonDeleteSelected.text = if (deleting) AppLanguage.text("削除しています…", "Deleting…") else AppLanguage.text("選択した会議を削除（${selectedIds.size}件）", "Delete selected meetings (${selectedIds.size})")
        binding.buttonDeleteSelected.isEnabled = !deleting && selectedIds.isNotEmpty()
        binding.listMeetings.isEnabled = !deleting
        binding.buttonNewRecording.isEnabled = !deleting
        binding.buttonSettings.isEnabled = !deleting
    }

    private fun confirmDeletion() {
        val targets = selectedIds.toList()
        if (deleting || targets.isEmpty()) return
        if (RecordingState.isRecording.value || AiProcessingState.isProcessing.value) {
            Toast.makeText(this, AppLanguage.text("録音・AI処理が終了してから削除してください", "Wait for recording and AI processing to finish before deleting"), Toast.LENGTH_LONG).show()
            return
        }
        val titles = targets.mapNotNull { id ->
            meetingIds.indexOf(id).takeIf { it >= 0 }?.let { meetingLabels[it].toString().substringBefore('\n') }
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("${targets.size}件の会議を完全に削除しますか？", "Permanently delete ${targets.size} meetings?"))
            .setMessage(titles.joinToString("\n") + AppLanguage.text("\n\n録音、文字起こし、話者ラベル、要約、編集履歴を削除します。鍵を直ちに破棄するため、この操作は取り消せません。書き出し済みのファイルは削除されません。", "\n\nRecordings, transcripts, speaker labels, summaries, and edit history will be deleted. Keys are destroyed immediately, so this cannot be undone. Exported files will not be deleted."))
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("完全に削除", "Delete permanently")) { _, _ -> deleteMeetings(targets) }
            .show()
    }

    private fun deleteMeetings(targets: List<String>) {
        if (deleting) return
        if (RecordingState.isRecording.value || AiProcessingState.isProcessing.value) {
            Toast.makeText(this, AppLanguage.text("録音・AI処理が終了してから削除してください", "Wait for recording and AI processing to finish before deleting"), Toast.LENGTH_LONG).show()
            return
        }
        deleting = true
        refreshGeneration++
        updateSelectionControls()
        val context = applicationContext
        Thread {
            val results = targets.associateWith { id ->
                runCatching {
                    val store = RecordingStore(context)
                    val reference = store.find(id) ?: error(AppLanguage.text("会議が見つかりません", "Meeting not found"))
                    store.deleteMeeting(reference)
                }
            }
            val deleted = results.filterValues { it.isSuccess }.keys
            if (RecordingState.lastRecording.value?.meetingId in deleted) RecordingState.lastRecording.value = null
            RecorderWidgetProvider.updateAll(context)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                deleting = false
                selectedIds.removeAll(deleted)
                selectionMode = selectedIds.isNotEmpty()
                val failures = targets.size - deleted.size
                val cleanup = results.values.any { it.getOrNull()?.encryptedFileRemoved == false }
                Toast.makeText(this, AppLanguage.text("${deleted.size}件の会議を削除しました。", "Deleted ${deleted.size} meetings.") +
                    (if (failures > 0) AppLanguage.text(" ${failures}件は削除できませんでした。", " Could not delete ${failures} meetings.") else "") +
                    (if (cleanup) AppLanguage.text(" 一部の暗号ファイルは残りましたが、鍵は破棄済みです。", " Some encrypted files remain, but their keys have been destroyed.") else ""), Toast.LENGTH_LONG).show()
                refreshMeetings()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshMeetings()
    }

    private fun refreshMeetings() {
        if (deleting) return
        val generation = ++refreshGeneration
        binding.textEmptyMeetings.text = AppLanguage.text("会議を読み込んでいます…", "Loading meeting…")
        Thread {
            val result = runCatching {
                EncryptedMeetingDatabase(this).use { database ->
                    database.listMeetingIds()
                        .mapNotNull(database::readMeeting)
                        .sortedByDescending(EncryptedMeetingMetadata::createdAtEpochMs)
                        .map { metadata ->
                            val revisions = database.listCurrentRevisions(metadata.meetingId).associateBy { it.artifactId }
                            val summary = revisions["summary"]?.let {
                                runCatching { PipelineArtifactCodec.decodeSummary(it.content) }.getOrNull()
                            }
                            val speakers = revisions["diarization"]?.let {
                                runCatching { PipelineArtifactCodec.decodeDiarization(it.content) }.getOrNull()
                            }
                            val lastRun = database.listProviderRuns(metadata.meetingId).maxByOrNull { it.startedAtEpochMs }
                            val state = when {
                                metadata.audioState == AudioState.RECORDING -> AppLanguage.text("録音中・未完了", "Recording / incomplete")
                                lastRun?.status == ExecutionStatus.FAILED -> AppLanguage.text("解析でエラー ・ 再実行できます", "Processing error · You can retry")
                                summary != null -> AppLanguage.text("✓ 議事録作成済み", "✓ Minutes created")
                                "formatted-text" in revisions || "transcript" in revisions -> AppLanguage.text("✓ 文字起こし済み", "✓ Transcribed")
                                else -> AppLanguage.text("○ 未解析", "○ Not processed")
                            }
                            MeetingCard(metadata, summary?.title.orEmpty(), summary?.summary.orEmpty(),
                                speakers?.let { (if (it.isSpeakerCountSpecified) AppLanguage.text("指定", "Specified") else AppLanguage.text("推定", "Estimated")) + AppLanguage.text(" ${it.speakerCount}人", " ${it.speakerCount} speakers") },
                                state)
                        }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != refreshGeneration) return@runOnUiThread
                result.onSuccess { meetings ->
                    meetingIds = meetings.map { it.metadata.meetingId }
                    meetingLabels = meetings.map(::meetingLabel)
                    selectedIds.retainAll(meetingIds.toSet())
                    renderMeetings()
                    binding.textEmptyMeetings.text =
                        AppLanguage.text("保存済みの会議はありません。\n「新しい録音」から始めてください。", "No saved meetings.\nStart with \"New recording\".")
                }.onFailure {
                    meetingIds = emptyList()
                    meetingLabels = emptyList()
                    selectedIds.clear()
                    updateSelectionControls()
                    binding.listMeetings.adapter = null
                    binding.textEmptyMeetings.text = AppLanguage.text("会議一覧を読み込めませんでした", "Could not load meeting list")
                    Toast.makeText(this, AppLanguage.text("会議一覧を読み込めませんでした", "Could not load meeting list"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun meetingLabel(card: MeetingCard): CharSequence {
        val meeting = card.metadata
        val created = DATE_FORMATTER.format(Instant.ofEpochMilli(meeting.createdAtEpochMs))
        val duration = meeting.durationMs?.let(::durationLabel) ?: AppLanguage.text("時間不明", "Unknown duration")
        val title = card.title.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { AppLanguage.text("録音 ・ $created", "Recording · $created") }
        return SpannableStringBuilder().apply {
            append(title)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.18f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n$created ・ $duration")
            card.speakers?.let { append(" ・ $it") }
            card.summary.replace(Regex("\\s+"), " ").trim().take(110).takeIf { it.isNotBlank() }
                ?.let { append("\n$it") }
            append("\n${card.state}")
            if (meeting.audioState == AudioState.DELETED) append(AppLanguage.text(" ・ 音声削除済み", " · Audio deleted"))
        }
    }

    private data class MeetingCard(
        val metadata: EncryptedMeetingMetadata, val title: String, val summary: String,
        val speakers: String?, val state: String
    )

    private fun durationLabel(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1_000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
