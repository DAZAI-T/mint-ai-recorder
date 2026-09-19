package com.gijiroku.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.gijiroku.benchmark.databinding.ActivityProviderSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Configures cloud credentials without ever displaying a stored secret in full. */
class ProviderSettingsActivity : SecureActivity() {

    private lateinit var binding: ActivityProviderSettingsBinding
    private lateinit var secretStore: SecretStore
    private lateinit var cloudSettings: CloudProviderSettings
    private lateinit var providerPreferences: ProviderPreferences
    private lateinit var minutesTemplateSettings: MinutesTemplateSettings
    private lateinit var appLockSettings: AppLockSettings
    private lateinit var screenshotProtectionSettings: ScreenshotProtectionSettings
    private lateinit var modelStore: ModelStore
    private var modelRefreshJob: Job? = null
    private var installingRecommendedModelId: String? = null
    private var updatingAppLockSwitch = false
    private var updatingScreenshotSwitch = false
    private var pendingBackupReference: RecordingReference? = null
    private var pendingBackupPassphrase: CharArray? = null
    private var pendingBackupRecovery = false
    private var pendingExportDocument: MeetingExportDocument? = null
    private var pendingExportFormat: MeetingExportFormat? = null
    private var settingsPage = "home"

    private val openUserTranscriptionModelDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importUserModel(LocalModelLibraryKind.TRANSCRIPTION, it) } }

    private val openUserSummaryModelDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importUserModel(LocalModelLibraryKind.SUMMARIZATION, it) } }

    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri -> handleBackupDestination(uri) }

    private val openBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::showRestoreCredentialDialog) }

    private val createMarkdownDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeetingExportFormat.MARKDOWN.mimeType)
    ) { uri -> handleExportDestination(uri, MeetingExportFormat.MARKDOWN) }

    private val createPdfDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeetingExportFormat.PDF.mimeType)
    ) { uri -> handleExportDestination(uri, MeetingExportFormat.PDF) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buttonLanguage.text = AppLanguage.text("言語：日本語", "Language: English")
        binding.buttonLanguage.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(AppLanguage.text("日本語 / English", "日本語 / English"))
                .setSingleChoiceItems(arrayOf("日本語", "English"), if (AppLanguage.code == "en") 1 else 0) { dialog, index ->
                    dialog.dismiss()
                    val language = if (index == 1) "en" else "ja"
                    if (language != AppLanguage.code) {
                        AppLanguage.save(this, language)
                        RecorderWidgetProvider.updateAll(applicationContext)
                        recreate()
                    }
                }
                .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
                .show()
        }
        secretStore = SecretStore(this)
        cloudSettings = CloudProviderSettings(this)
        providerPreferences = ProviderPreferences(this)
        minutesTemplateSettings = MinutesTemplateSettings(this)
        appLockSettings = AppLockSettings(this)
        screenshotProtectionSettings = ScreenshotProtectionSettings(this)
        modelStore = ModelStore(this)
        val appearance = AppearanceSettings(this)
        val appearanceModes = intArrayOf(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        val appearanceLabels = arrayOf(AppLanguage.text("端末設定に合わせる", "System default"), AppLanguage.text("ライト", "Light"), AppLanguage.text("ダーク", "Dark"))
        binding.buttonAppearance.text = AppLanguage.text("外観：", "Appearance: ") + appearanceLabels[
            appearanceModes.indexOf(appearance.mode).coerceAtLeast(0)
        ]
        binding.buttonAppearance.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(AppLanguage.text("外観", "Appearance"))
                .setSingleChoiceItems(appearanceLabels, appearanceModes.indexOf(appearance.mode)) { dialog, index ->
                    dialog.dismiss()
                    appearance.mode = appearanceModes[index]
                    appearance.apply()
                }
                .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
                .show()
        }

        binding.switchAppLock.isChecked = appLockSettings.enabled
        binding.switchAppLock.setOnCheckedChangeListener { _, checked ->
            if (updatingAppLockSwitch) return@setOnCheckedChangeListener
            if (checked) {
                appLockSettings.enabled = true
                AppLockController.markAuthenticated()
                Toast.makeText(this, AppLanguage.text("アプリロックをONにしました", "App lock enabled"), Toast.LENGTH_SHORT).show()
            } else {
                updatingAppLockSwitch = true
                binding.switchAppLock.isChecked = true
                updatingAppLockSwitch = false
                authenticateForSensitiveAction(AppLanguage.text("アプリロックをOFFにする", "Disable app lock")) {
                    appLockSettings.enabled = false
                    updatingAppLockSwitch = true
                    binding.switchAppLock.isChecked = false
                    updatingAppLockSwitch = false
                    Toast.makeText(this, AppLanguage.text("アプリロックをOFFにしました", "App lock disabled"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.switchAllowScreenshots.isChecked = screenshotProtectionSettings.screenshotsAllowed
        binding.switchAllowScreenshots.setOnCheckedChangeListener { _, checked ->
            if (updatingScreenshotSwitch) return@setOnCheckedChangeListener
            if (checked) {
                confirmScreenshotPermission()
            } else {
                screenshotProtectionSettings.screenshotsAllowed = false
                refreshScreenshotProtection()
                refreshScreenshotStatus()
                Toast.makeText(this, AppLanguage.text("スクリーンショットを禁止しました", "Screenshots blocked"), Toast.LENGTH_SHORT).show()
            }
        }

        binding.editOpenAiModel.setText(cloudSettings.openAiTranscriptionModel)
        binding.editOpenAiFormattingModel.setText(cloudSettings.openAiFormattingModel)
        binding.editOpenAiSummaryModel.setText(cloudSettings.openAiSummaryModel)
        binding.editGeminiTranscriptionModel.setText(
            cloudSettings.geminiModel(PipelineStage.TRANSCRIPTION).orEmpty()
        )
        binding.editGeminiFormattingModel.setText(
            cloudSettings.geminiModel(PipelineStage.TEXT_FORMATTING).orEmpty()
        )
        binding.editGeminiSummaryModel.setText(
            cloudSettings.geminiModel(PipelineStage.SUMMARIZATION).orEmpty()
        )
        binding.textOpenAiEndpoint.text = AppLanguage.text("送信先: ${CloudProviderSettings.OPENAI_BASE_URL}", "Destination: ${CloudProviderSettings.OPENAI_BASE_URL}")
        refreshCloudModelLabels()
        when (providerPreferences.selectedProvider(PipelineStage.TRANSCRIPTION)) {
            ProviderIds.OPENAI_WHISPER -> binding.radioOpenAiWhisper.isChecked = true
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION -> binding.radioGeminiTranscription.isChecked = true
            else -> binding.radioLocalWhisper.isChecked = true
        }
        when (providerPreferences.selectedProvider(PipelineStage.TEXT_FORMATTING)) {
            ProviderIds.OPENAI_RESPONSES_FORMATTING -> binding.radioOpenAiFormatting.isChecked = true
            ProviderIds.GEMINI_FLASH_LITE_FORMATTING -> binding.radioGeminiFormatting.isChecked = true
            else -> binding.radioLocalFormatting.isChecked = true
        }
        when (providerPreferences.selectedProvider(PipelineStage.SUMMARIZATION)) {
            ProviderIds.LOCAL_QWEN3_SUMMARY -> binding.radioLocalQwenSummary.isChecked = true
            ProviderIds.OPENAI_RESPONSES_SUMMARY -> binding.radioOpenAiSummary.isChecked = true
            ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> binding.radioGeminiSummary.isChecked = true
            else -> binding.radioNoSummary.isChecked = true
        }
        val templates = MeetingMinutesTemplates.builtIns
        binding.spinnerMinutesTemplate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            templates.map(MinutesTemplate::displayName)
        )
        binding.spinnerMinutesTemplate.setSelection(
            templates.indexOfFirst { it.id == minutesTemplateSettings.selectedTemplateId }.coerceAtLeast(0)
        )
        binding.editCustomMinutesInstructions.setText(minutesTemplateSettings.customInstructions)
        binding.editCustomMinutesInstructions.visibility = if (
            templates[binding.spinnerMinutesTemplate.selectedItemPosition].id == MinutesTemplateIds.CUSTOM
        ) View.VISIBLE else View.GONE
        binding.spinnerMinutesTemplate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.editCustomMinutesInstructions.visibility = if (
                    templates[position].id == MinutesTemplateIds.CUSTOM
                ) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.buttonSaveOpenAi.setOnClickListener { saveProviderSettings() }
        binding.buttonManageCloudModels.setOnClickListener { showCloudModelCategories() }
        binding.buttonDeleteOpenAiKey.setOnClickListener { deleteOpenAiKey() }
        binding.buttonDeleteGeminiKey.setOnClickListener { deleteGeminiKey() }
        binding.buttonInstallLiveModel.setOnClickListener {
            installRecommendedModel(OfficialModelCatalog.LIVE_WHISPER_ID, AppLanguage.text("軽量Whisper", "Lightweight Whisper"))
        }
        binding.buttonInstallFinalModel.setOnClickListener {
            installRecommendedModel(OfficialModelCatalog.FINAL_WHISPER_ID, AppLanguage.text("高精度Whisper", "High-accuracy Whisper"))
        }
        binding.buttonInstallSpeakerModel.setOnClickListener {
            installRecommendedModel(OfficialModelCatalog.SPEAKER_ID, AppLanguage.text("話者分離CAM++", "CAM++ speaker separation"))
        }
        binding.buttonInstallQwenModel.setOnClickListener {
            installRecommendedModel(OfficialModelCatalog.QWEN_SUMMARY_ID, AppLanguage.text("要約Qwen3-4B", "Qwen3-4B summary"))
        }
        binding.buttonDeleteLiveModel.setOnClickListener {
            confirmModelDeletion(OfficialModelCatalog.LIVE_WHISPER_ID, AppLanguage.text("軽量Whisper small-q5_1", "Lightweight Whisper small-q5_1"))
        }
        binding.buttonDeleteFinalModel.setOnClickListener {
            confirmModelDeletion(OfficialModelCatalog.FINAL_WHISPER_ID, AppLanguage.text("高精度Whisper large-v3-turbo-q5_0", "High-accuracy Whisper large-v3-turbo-q5_0"))
        }
        binding.buttonDeleteSpeakerModel.setOnClickListener {
            confirmModelDeletion(OfficialModelCatalog.SPEAKER_ID, AppLanguage.text("話者分離CAM++", "CAM++ speaker separation"))
        }
        binding.buttonDeleteQwenModel.setOnClickListener {
            confirmModelDeletion(OfficialModelCatalog.QWEN_SUMMARY_ID, AppLanguage.text("要約Qwen3-4B Q4_K_M", "Qwen3-4B Q4_K_M summary"))
        }
        binding.buttonVerifyLocalModels.setOnClickListener { refreshLocalModelStatus(showCompletion = true) }
        binding.buttonDownloadTranscriptionModel.setOnClickListener {
            showModelDownloadDialog(LocalModelLibraryKind.TRANSCRIPTION)
        }
        binding.buttonImportTranscriptionModel.setOnClickListener {
            openUserTranscriptionModelDocument.launch(MODEL_MIME_TYPES)
        }
        binding.buttonDownloadSummaryModel.setOnClickListener {
            showModelDownloadDialog(LocalModelLibraryKind.SUMMARIZATION)
        }
        binding.buttonImportSummaryModel.setOnClickListener {
            openUserSummaryModelDocument.launch(MODEL_MIME_TYPES)
        }
        binding.buttonManageLocalModels.setOnClickListener { showLocalModelKinds() }
        binding.buttonCreateEncryptedBackup.setOnClickListener { showBackupMeetingPicker() }
        binding.buttonRestoreEncryptedBackup.setOnClickListener {
            openBackupDocument.launch(arrayOf(BACKUP_MIME_TYPE, "application/octet-stream"))
        }
        binding.buttonExportMeeting.setOnClickListener { showExportWarning() }
        refreshStatus()
        refreshScreenshotStatus()
        setupSettingsNavigation(savedInstanceState?.getString("settings_page") ?: "home")
    }

    private fun setupSettingsNavigation(initialPage: String) {
        binding.buttonPipelineModels.setOnClickListener {
            AlertDialog.Builder(this).setTitle(AppLanguage.text("モデルを変更", "Change model"))
                .setItems(arrayOf(AppLanguage.text("ローカルモデル", "Local models"), AppLanguage.text("クラウドモデル", "Cloud models"))) { _, index ->
                    showSettingsPage(if (index == 0) "local" else "cloud")
                }.setNegativeButton(AppLanguage.text("閉じる", "Close"), null).show()
        }
        binding.buttonDeveloperTools.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.buttonSectionPipeline.setOnClickListener { showSettingsPage("pipeline") }
        binding.buttonSectionTemplates.setOnClickListener { showSettingsPage("templates") }
        binding.buttonSectionPrivacy.setOnClickListener { showSettingsPage("privacy") }
        binding.buttonSectionLocal.setOnClickListener { showSettingsPage("local") }
        binding.buttonSectionCloud.setOnClickListener { showSettingsPage("cloud") }
        binding.buttonSectionData.setOnClickListener { showSettingsPage("data") }
        binding.buttonSettingsBack.setOnClickListener { showSettingsPage("home") }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPage != "home") showSettingsPage("home") else finish()
            }
        })
        showSettingsPage(initialPage)
    }

    private fun showSettingsPage(page: String) {
        val pages = mapOf(
            "home" to binding.settingsHome, "pipeline" to binding.settingsPipeline,
            "privacy" to binding.settingsPrivacy, "local" to binding.settingsLocal,
            "cloud" to binding.settingsCloud, "templates" to binding.settingsTemplates,
            "data" to binding.settingsData
        )
        settingsPage = page.takeIf { it in pages } ?: "home"
        pages.forEach { (key, view) -> view.visibility = if (key == settingsPage) View.VISIBLE else View.GONE }
        binding.buttonSettingsBack.visibility = if (settingsPage == "home") View.GONE else View.VISIBLE
        binding.buttonSaveOpenAi.visibility =
            if (settingsPage in setOf("pipeline", "cloud", "templates")) View.VISIBLE else View.GONE
        binding.textSettingsTitle.text = when (settingsPage) {
            "pipeline" -> AppLanguage.text("AIの処理順", "AI processing stages")
            "privacy" -> AppLanguage.text("プライバシー", "Privacy")
            "local" -> AppLanguage.text("ローカルモデル", "Local models")
            "cloud" -> AppLanguage.text("クラウド接続", "Cloud connections")
            "templates" -> AppLanguage.text("議事録の用途", "Minutes template")
            "data" -> AppLanguage.text("データ管理", "Data management")
            else -> AppLanguage.text("設定", "Settings")
        }
        binding.settingsScroll.scrollTo(0, 0)
        refreshPipelineModelLabels()
    }

    private fun refreshPipelineModelLabels() {
        binding.radioLocalWhisper.text = AppLanguage.text("端末内\n", "On device\n") +
            (modelStore.selectedLocalModel(LocalModelLibraryKind.TRANSCRIPTION)?.displayName ?: AppLanguage.text("モデル未選択", "No model selected"))
        binding.radioLocalQwenSummary.text = AppLanguage.text("端末内\n", "On device\n") +
            (modelStore.selectedLocalModel(LocalModelLibraryKind.SUMMARIZATION)?.displayName ?: AppLanguage.text("モデル未選択", "No model selected"))
        binding.radioOpenAiWhisper.text = "OpenAI\n" + cloudSettings.openAiTranscriptionModel.ifBlank { AppLanguage.text("モデル未選択", "No model selected") }
        binding.radioOpenAiFormatting.text = "OpenAI\n" + cloudSettings.openAiFormattingModel.ifBlank { AppLanguage.text("モデル未選択", "No model selected") }
        binding.radioOpenAiSummary.text = "OpenAI\n" + cloudSettings.openAiSummaryModel.ifBlank { AppLanguage.text("モデル未選択", "No model selected") }
        binding.radioGeminiTranscription.text = "Google Gemini\n" +
            (cloudSettings.geminiModel(PipelineStage.TRANSCRIPTION) ?: AppLanguage.text("モデル未選択", "No model selected"))
        binding.radioGeminiFormatting.text = "Google Gemini\n" +
            (cloudSettings.geminiModel(PipelineStage.TEXT_FORMATTING) ?: AppLanguage.text("モデル未選択", "No model selected"))
        binding.radioGeminiSummary.text = "Google Gemini\n" +
            (cloudSettings.geminiModel(PipelineStage.SUMMARIZATION) ?: AppLanguage.text("モデル未選択", "No model selected"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_page", settingsPage)
        super.onSaveInstanceState(outState)
    }

    private fun confirmScreenshotPermission() {
        updatingScreenshotSwitch = true
        binding.switchAllowScreenshots.isChecked = false
        updatingScreenshotSwitch = false
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("スクリーンショットを許可しますか？", "Allow screenshots?"))
            .setMessage(
                AppLanguage.text("録音内容、文字起こし、要約、Provider設定などが画像や最近使ったアプリの", "Recordings, transcripts, summaries, and provider settings may remain in screenshots or recent-app ") +
                    AppLanguage.text("プレビューとして端末内・他アプリ・同期先へ残る可能性があります。", "previews on your device, in other apps, or at synced destinations.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("理解して許可", "Understand and allow")) { _, _ ->
                screenshotProtectionSettings.screenshotsAllowed = true
                updatingScreenshotSwitch = true
                binding.switchAllowScreenshots.isChecked = true
                updatingScreenshotSwitch = false
                refreshScreenshotProtection()
                refreshScreenshotStatus()
            }
            .show()
    }

    private fun refreshScreenshotStatus() {
        binding.textScreenshotStatus.text = if (screenshotProtectionSettings.screenshotsAllowed) {
            AppLanguage.text("許可中: このアプリの画面を撮影できます。機密情報の写り込みに注意してください。", "Allowed: Screenshots can be taken. Be careful not to capture confidential information.")
        } else {
            AppLanguage.text("保護中: スクリーンショットと最近使ったアプリのプレビューを禁止します。", "Protected: Screenshots and recent-app previews are blocked.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::modelStore.isInitialized) {
            refreshLocalModelStatus()
        }
    }

    private fun installRecommendedModel(modelId: String, label: String) {
        if (installingRecommendedModelId != null) {
            Toast.makeText(this, AppLanguage.text("別のモデルを導入中です", "Another model is being installed"), Toast.LENGTH_SHORT).show()
            return
        }
        installingRecommendedModelId = modelId
        installButtonFor(modelId).apply {
            isEnabled = false
            text = AppLanguage.text("ダウンロード・検証中…", "Downloading and verifying…")
        }
        Toast.makeText(this, AppLanguage.text("$label を原配布元からダウンロードします", "Downloading $label from its original source"), Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { modelStore.downloadRecommendedModel(modelId) }
            }
            installingRecommendedModelId = null
            outcome.onSuccess { result ->
                val message = if (result.alreadyInstalled) {
                    AppLanguage.text("$label は導入済みです", "$label is already installed")
                } else {
                    AppLanguage.text("$label を検証して導入しました。すぐに選択できます", "$label verified and installed. It is ready to select.")
                }
                Toast.makeText(this@ProviderSettingsActivity, message, Toast.LENGTH_LONG).show()
                refreshLocalModelStatus()
            }.onFailure { error ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("$label を導入できませんでした: ${error.message}", "Could not install $label: ${error.message}"),
                    Toast.LENGTH_LONG
                ).show()
                refreshLocalModelStatus()
            }
        }
    }

    private fun importUserModel(kind: LocalModelLibraryKind, uri: Uri) {
        Toast.makeText(this, AppLanguage.text("${kind.displayName}モデルを検証して読み込んでいます…", "Verifying and loading ${kind.displayName} model…"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { modelStore.importLocalModel(kind, uri) }
            }
            outcome.onSuccess { result ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    if (result.alreadyInstalled) AppLanguage.text("導入済みモデルを選択しました", "Installed model selected") else AppLanguage.text("モデルをライブラリへ追加して選択しました", "Model added to library and selected"),
                    Toast.LENGTH_LONG
                ).show()
                refreshLocalModelStatus()
            }.onFailure { error ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("モデルを読み込めませんでした: ${error.message}", "Could not load model: ${error.message}"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showModelDownloadDialog(kind: LocalModelLibraryKind) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = EditText(this).apply {
            hint = AppLanguage.text("一覧に表示する名前", "Display name")
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val url = EditText(this).apply {
            hint = AppLanguage.text("HTTPSモデルURL（${kind.extension}）", "HTTPS model URL (${kind.extension})")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        content.addView(name)
        content.addView(url)
        val dialog = AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("${kind.displayName}モデルを追加", "Add ${kind.displayName} model"))
            .setMessage(AppLanguage.text("URLからアプリ専用領域へダウンロードします。配布元、ライセンス、端末との互換性を確認してください。", "Download from the URL to private app storage. Check the source, license, and device compatibility."))
            .setView(content)
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("ダウンロード", "Download"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enteredName = name.text.toString().trim()
                val enteredUrl = url.text.toString().trim()
                if (enteredName.isEmpty() || enteredUrl.isEmpty()) {
                    Toast.makeText(this, AppLanguage.text("名前とHTTPS URLを入力してください", "Enter a name and HTTPS URL"), Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    downloadUserModel(kind, enteredUrl, enteredName)
                }
            }
        }
        dialog.show()
    }

    private fun downloadUserModel(
        kind: LocalModelLibraryKind,
        url: String,
        name: String,
        allowRedirectOrigin: String? = null
    ) {
        Toast.makeText(this, AppLanguage.text("モデルをダウンロードしています。この画面を閉じないでください。", "Downloading model. Keep this screen open."), Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    modelStore.downloadLocalModel(kind, url, name, allowRedirectOrigin)
                }
            }
            outcome.onSuccess { result ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    if (result.alreadyInstalled) AppLanguage.text("導入済みモデルを選択しました", "Installed model selected") else AppLanguage.text("モデルを追加して選択しました", "Model added and selected"),
                    Toast.LENGTH_LONG
                ).show()
                refreshLocalModelStatus()
            }.onFailure { error ->
                if (error is ModelCrossOriginRedirectException) {
                    confirmModelRedirect(kind, url, name, error.destination)
                } else {
                    Toast.makeText(
                        this@ProviderSettingsActivity,
                        AppLanguage.text("ダウンロードできませんでした: ${error.message}", "Could not download: ${error.message}"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmModelRedirect(kind: LocalModelLibraryKind, url: String, name: String, destination: java.net.URL) {
        val port = if (destination.port == -1) 443 else destination.port
        val destinationOrigin = "${destination.protocol}://${destination.host}:$port"
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("配布元が変わります", "Download source is changing"))
            .setMessage(AppLanguage.text("ダウンロード先が ${destination.host} へ移動します。この配布元から続行しますか？", "Download redirects to ${destination.host}. Continue from this source?"))
            .setNegativeButton(AppLanguage.text("中止", "Stop"), null)
            .setPositiveButton(AppLanguage.text("続行", "Continue")) { _, _ ->
                downloadUserModel(kind, url, name, destinationOrigin)
            }
            .show()
    }

    private fun showLocalModelKinds() {
        val kinds = LocalModelLibraryKind.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("ローカルモデルの工程", "Local model stage"))
            .setItems(kinds.map(LocalModelLibraryKind::displayName).toTypedArray()) { _, index ->
                showLocalModels(kinds[index])
            }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun showLocalModels(kind: LocalModelLibraryKind) {
        val models = modelStore.localModels(kind)
        if (models.isEmpty()) {
            Toast.makeText(this, AppLanguage.text("${kind.displayName}モデルはまだ導入されていません", "No ${kind.displayName} models installed yet"), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = modelStore.selectedLocalModel(kind)?.id
        val labels = models.map {
            val prefix = if (it.id == selected) "✓ " else ""
            "$prefix${it.displayName}（${formatBytes(it.byteSize)}）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("${kind.displayName}モデル", "${kind.displayName} model"))
            .setItems(labels) { _, index -> showLocalModelActions(kind, models[index]) }
            .setNegativeButton(AppLanguage.text("戻る", "Back")) { _, _ -> showLocalModelKinds() }
            .show()
    }

    private fun showLocalModelActions(kind: LocalModelLibraryKind, model: LocalModelRecord) {
        AlertDialog.Builder(this)
            .setTitle(model.displayName)
            .setMessage(AppLanguage.text("SHA-256: ${model.sha256}\n配布元: ${model.source.ifBlank { "不明" }}", "SHA-256: ${model.sha256}\nSource: ${model.source.ifBlank { "Unknown" }}"))
            .setItems(arrayOf(AppLanguage.text("このモデルを選択", "Select this model"), AppLanguage.text("モデルを削除", "Delete model"))) { _, action ->
                if (action == 0) {
                    modelStore.selectLocalModel(kind, model.id)
                    refreshLocalModelStatus()
                    Toast.makeText(this, AppLanguage.text("${model.displayName}を選択しました", "Selected ${model.displayName}"), Toast.LENGTH_SHORT).show()
                } else {
                    confirmLocalModelDeletion(kind, model)
                }
            }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun confirmLocalModelDeletion(kind: LocalModelLibraryKind, model: LocalModelRecord) {
        if (RecordingState.isRecording.value) {
            Toast.makeText(this, AppLanguage.text("録音を停止してからモデルを削除してください", "Stop recording before deleting models"), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("${model.displayName}を削除しますか？", "Delete ${model.displayName}?"))
            .setMessage(AppLanguage.text("モデルデータだけを削除します。録音・文字起こし・議事録は削除されません。", "Only model data will be deleted. Recordings, transcripts, and minutes remain."))
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("モデルのみ削除", "Delete model only")) { _, _ ->
                lifecycleScope.launch {
                    val outcome = runCatching { withContext(Dispatchers.IO) {
                        when (model.id) {
                            OfficialModelCatalog.FINAL_WHISPER_ID,
                            OfficialModelCatalog.QWEN_SUMMARY_ID -> modelStore.deleteOfficialModel(model.id)
                            else -> modelStore.deleteLocalModel(kind, model.id)
                        }
                    } }
                    outcome.onSuccess {
                        Toast.makeText(this@ProviderSettingsActivity, AppLanguage.text("モデルを削除しました", "Model deleted"), Toast.LENGTH_SHORT).show()
                        refreshLocalModelStatus()
                    }.onFailure {
                        Toast.makeText(this@ProviderSettingsActivity, AppLanguage.text("削除できませんでした: ${it.message}", "Could not delete: ${it.message}"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun confirmModelDeletion(modelId: String, label: String) {
        if (RecordingState.isRecording.value) {
            Toast.makeText(this, AppLanguage.text("録音を停止してからモデルを削除してください", "Stop recording before deleting models"), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("$label を削除しますか？", "Delete $label?"))
            .setMessage(
                AppLanguage.text("端末内のモデルファイルだけを削除します。録音、文字起こし、話者ラベル、要約、", "Only the model file on this device will be deleted. Recordings, transcripts, speaker labels, summaries, and ") +
                    AppLanguage.text("編集履歴は削除されません。必要になったら推奨モデルから再導入できます。", "edit history remain. You can reinstall from recommended models later.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("モデルのみ削除", "Delete model only")) { _, _ -> deleteOfficialModel(modelId, label) }
            .show()
    }

    private fun deleteOfficialModel(modelId: String, label: String) {
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { modelStore.deleteOfficialModel(modelId) }
            }
            outcome.onSuccess { result ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    if (result.filesRemoved > 0) AppLanguage.text("$label のみを削除しました", "Deleted $label only") else AppLanguage.text("$label は導入されていません", "$label is not installed"),
                    Toast.LENGTH_LONG
                ).show()
                refreshLocalModelStatus()
            }.onFailure { error ->
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("$label を削除できませんでした: ${error.message}", "Could not delete $label: ${error.message}"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshLocalModelStatus(showCompletion: Boolean = false) {
        modelRefreshJob?.cancel()
        val capability = LocalModelDeviceCapabilityEvaluator.evaluate(this)
        binding.textLocalModelCapability.text = buildString {
            append(AppLanguage.text("Android確認値: RAM ${formatGb(capability.reportedRamBytes)} GB / ", "Android reports: RAM ${formatGb(capability.reportedRamBytes)} GB / "))
            append(AppLanguage.text("空き容量 ${formatGb(capability.usableStorageBytes)} GB\n", "Free space ${formatGb(capability.usableStorageBytes)} GB\n"))
            if (capability.isFullLocalRecommended) {
                append(AppLanguage.text("フルローカル推奨条件（RAM 12GB級・空き8GB）を満たしています。", "Meets the full local setup recommendation (about 12GB RAM and 8GB free space)."))
            } else {
                val reasons = buildList {
                    if (!capability.hasRecommendedRam) add("RAM")
                    if (!capability.hasRequiredStorage) add(AppLanguage.text("空き容量", "Free space"))
                }.joinToString(AppLanguage.text("と", "and"))
                append(AppLanguage.text("$reasons がフルローカル推奨条件未満です。録音、既存成果物、軽量／クラウド構成は利用できます。", "$reasons is below the full local setup recommendation. Recording, existing results, and lightweight or cloud setups remain available."))
            }
        }
        binding.textModelCatalogVersion.text = AppLanguage.text("推奨モデル情報: ${modelStore.officialCatalogVersion()}\n", "Recommended model metadata: ${modelStore.officialCatalogVersion()}\n") +
            AppLanguage.text("原配布元から直接取得し、容量とSHA-256を照合します。", "Downloaded directly from the original source; size and SHA-256 are verified.")
        binding.textLocalModelLibrary.text = buildString {
            val transcription = modelStore.selectedLocalModel(LocalModelLibraryKind.TRANSCRIPTION)
            val summary = modelStore.selectedLocalModel(LocalModelLibraryKind.SUMMARIZATION)
            appendLine(AppLanguage.text("選択中の音声認識: ${transcription?.displayName ?: "未選択"}", "Selected transcription model: ${transcription?.displayName ?: "Not selected"}"))
            append(AppLanguage.text("選択中の要約: ${summary?.displayName ?: "未選択"}", "Selected summary model: ${summary?.displayName ?: "Not selected"}"))
        }

        val entries = listOf(
            Triple(OfficialModelCatalog.LIVE_WHISPER_ID, AppLanguage.text("軽量Whisper（音声認識）", "Lightweight Whisper (transcription)"), binding.textLiveModelDetails),
            Triple(OfficialModelCatalog.FINAL_WHISPER_ID, AppLanguage.text("高精度Whisper（確定処理）", "High-accuracy Whisper (processing)"), binding.textFinalModelDetails),
            Triple(OfficialModelCatalog.SPEAKER_ID, AppLanguage.text("CAM++（話者分離）", "CAM++ (speaker separation)"), binding.textSpeakerModelDetails),
            Triple(OfficialModelCatalog.QWEN_SUMMARY_ID, AppLanguage.text("Qwen3-4B Q4_K_M（ローカル要約）", "Qwen3-4B Q4_K_M (local summary)"), binding.textQwenModelDetails)
        )
        entries.forEach { (id, label, view) ->
            val entry = modelStore.modelEntry(id)
            val hasFile = modelStore.hasOfficialModelFile(id)
            view.text = modelDetails(entry, label, if (hasFile) AppLanguage.text("検証中…", "Verifying…") else AppLanguage.text("未導入", "Not installed"))
            deleteButtonFor(id).isEnabled = hasFile
            installButtonFor(id).apply {
                isEnabled = !hasFile && installingRecommendedModelId == null
                text = if (installingRecommendedModelId == id) AppLanguage.text("ダウンロード・検証中…", "Downloading and verifying…") else recommendedInstallLabel(id)
            }
        }
        binding.buttonVerifyLocalModels.isEnabled = false
        modelRefreshJob = lifecycleScope.launch {
            val verified = withContext(Dispatchers.IO) {
                entries.associate { (id, _, _) -> id to modelStore.verifyOfficialModel(id) }
            }
            entries.forEach { (id, label, view) ->
                val entry = modelStore.modelEntry(id)
                val status = when {
                    verified.getValue(id) -> AppLanguage.text("検証済み", "Verified")
                    modelStore.hasOfficialModelFile(id) -> AppLanguage.text("破損または非公式ファイル", "Corrupt or unofficial file")
                    else -> AppLanguage.text("未導入", "Not installed")
                }
                view.text = modelDetails(entry, label, status)
                deleteButtonFor(id).isEnabled = modelStore.hasOfficialModelFile(id)
                installButtonFor(id).apply {
                    isEnabled = !verified.getValue(id) && installingRecommendedModelId == null
                    text = when {
                        installingRecommendedModelId == id -> AppLanguage.text("ダウンロード・検証中…", "Downloading and verifying…")
                        verified.getValue(id) -> AppLanguage.text("導入済み", "Installed")
                        modelStore.hasOfficialModelFile(id) -> AppLanguage.text("再ダウンロードして修復", "Download again to repair")
                        else -> recommendedInstallLabel(id)
                    }
                }
            }
            binding.buttonVerifyLocalModels.isEnabled = true
            if (showCompletion) {
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("導入済みモデルの検証が完了しました", "Installed model verification completed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun modelDetails(entry: ModelManifestEntry, label: String, status: String): String =
        AppLanguage.text("$label\n状態: $status / 容量: ${formatGb(entry.byteSize)} GB / ${entry.quantization}\n", "$label\nStatus: $status / Size: ${formatGb(entry.byteSize)} GB / ${entry.quantization}\n") +
            AppLanguage.text("ライセンス: ${entry.licenseId} / 配布元: ${entry.sourceUrl.substringAfter("://").substringBefore('/')}", "License: ${entry.licenseId} / Source: ${entry.sourceUrl.substringAfter("://").substringBefore('/')}")

    private fun installButtonFor(modelId: String) = when (modelId) {
        OfficialModelCatalog.LIVE_WHISPER_ID -> binding.buttonInstallLiveModel
        OfficialModelCatalog.FINAL_WHISPER_ID -> binding.buttonInstallFinalModel
        OfficialModelCatalog.SPEAKER_ID -> binding.buttonInstallSpeakerModel
        OfficialModelCatalog.QWEN_SUMMARY_ID -> binding.buttonInstallQwenModel
        else -> error(AppLanguage.text("未対応のモデルです: $modelId", "Unsupported model: $modelId"))
    }

    private fun recommendedInstallLabel(modelId: String): String = when (modelId) {
        OfficialModelCatalog.LIVE_WHISPER_ID -> AppLanguage.text("軽量Whisperを導入", "Install lightweight Whisper")
        OfficialModelCatalog.FINAL_WHISPER_ID -> AppLanguage.text("高精度Whisperを導入", "Install high-accuracy Whisper")
        OfficialModelCatalog.SPEAKER_ID -> AppLanguage.text("話者分離モデルを導入", "Install speaker separation model")
        OfficialModelCatalog.QWEN_SUMMARY_ID -> AppLanguage.text("Qwen3-4Bを導入", "Install Qwen3-4B")
        else -> AppLanguage.text("導入", "Install")
    }

    private fun deleteButtonFor(modelId: String) = when (modelId) {
        OfficialModelCatalog.LIVE_WHISPER_ID -> binding.buttonDeleteLiveModel
        OfficialModelCatalog.FINAL_WHISPER_ID -> binding.buttonDeleteFinalModel
        OfficialModelCatalog.SPEAKER_ID -> binding.buttonDeleteSpeakerModel
        OfficialModelCatalog.QWEN_SUMMARY_ID -> binding.buttonDeleteQwenModel
        else -> error(AppLanguage.text("未対応のモデルです: $modelId", "Unsupported model: $modelId"))
    }

    private fun formatGb(bytes: Long): String = String.format(Locale.JAPAN, "%.1f", bytes / 1_000_000_000.0)

    private fun showExportWarning() {
        if (RecordingStore(this).latest() == null) {
            Toast.makeText(this, AppLanguage.text("書き出せる議事録がありません", "No minutes to export"), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("暗号化されない書き出しです", "This export is not encrypted"))
            .setMessage(
                AppLanguage.text("保存・共有した議事録は、このアプリの暗号化と削除の対象外になります。", "Saved or shared minutes are outside this app's encryption and deletion controls. ") +
                    AppLanguage.text("保存先や共有先での管理・削除は、そのサービスの設定に従います。", "Manage and delete them using the destination service's settings.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("形式を選ぶ", "Choose format")) { _, _ -> showExportOptions() }
            .show()
    }

    private fun showExportOptions() {
        val labels = arrayOf(
            AppLanguage.text("Markdownを保存", "Save Markdown"),
            AppLanguage.text("PDFを保存", "Save PDF"),
            AppLanguage.text("Markdownを共有", "Share Markdown"),
            AppLanguage.text("PDFを共有", "Share PDF")
        )
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("議事録を書き出す", "Export minutes"))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> prepareExport(MeetingExportFormat.MARKDOWN, share = false)
                    1 -> prepareExport(MeetingExportFormat.PDF, share = false)
                    2 -> prepareExport(MeetingExportFormat.MARKDOWN, share = true)
                    3 -> prepareExport(MeetingExportFormat.PDF, share = true)
                }
            }
            .show()
    }

    private fun prepareExport(format: MeetingExportFormat, share: Boolean) {
        val reference = RecordingStore(this).latest()
        if (reference == null) {
            Toast.makeText(this, AppLanguage.text("書き出せる議事録がありません", "No minutes to export"), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    MeetingExportService(this@ProviderSettingsActivity).buildDocument(reference.meetingId)
                }
            }
            outcome.onSuccess { document ->
                if (document.transcript.isBlank() && document.summary == null) {
                    Toast.makeText(
                        this@ProviderSettingsActivity,
                        AppLanguage.text("確定処理後の議事録がまだありません", "No processed minutes yet"),
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (share) {
                    shareDocument(document, format)
                } else {
                    pendingExportDocument = document
                    pendingExportFormat = format
                    val filename = defaultExportFilename(format)
                    when (format) {
                        MeetingExportFormat.MARKDOWN -> createMarkdownDocument.launch(filename)
                        MeetingExportFormat.PDF -> createPdfDocument.launch(filename)
                    }
                }
            }.onFailure {
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("議事録を読み込めませんでした", "Could not load minutes"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleExportDestination(uri: Uri?, format: MeetingExportFormat) {
        val document = pendingExportDocument
        val expectedFormat = pendingExportFormat
        pendingExportDocument = null
        pendingExportFormat = null
        if (uri == null || document == null || expectedFormat != format) return
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        when (format) {
                            MeetingExportFormat.MARKDOWN -> document.writeMarkdown(output)
                            MeetingExportFormat.PDF -> AndroidMeetingPdfWriter.write(document, output)
                        }
                    } ?: error("Could not open export destination")
                }
            }
            outcome.onSuccess {
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("議事録を保存しました", "Minutes saved"),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("議事録を保存できませんでした", "Could not save minutes"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareDocument(document: MeetingExportDocument, format: MeetingExportFormat) {
        val uri = MeetingExportStreamRegistry.prepare(this, document, format)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = MeetingExportStreamProvider.shareClip(this@ProviderSettingsActivity, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, AppLanguage.text("議事録を共有", "Share minutes")))
    }

    private fun showBackupMeetingPicker() {
        binding.buttonCreateEncryptedBackup.isEnabled = false
        Toast.makeText(this, AppLanguage.text("会議一覧を読み込んでいます…", "Loading meetings…"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { loadBackupMeetingOptions() }
            }
            binding.buttonCreateEncryptedBackup.isEnabled = true
            outcome.onSuccess { options ->
                if (options.isEmpty()) {
                    Toast.makeText(
                        this@ProviderSettingsActivity,
                        AppLanguage.text("バックアップできる会議がありません", "No meetings to back up"),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this@ProviderSettingsActivity)
                        .setTitle(AppLanguage.text("バックアップする会議を選択", "Choose a meeting to back up"))
                        .setItems(options.map(BackupMeetingOption::label).toTypedArray()) { _, index ->
                            showCreateBackupDialog(options[index])
                        }
                        .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
                        .show()
                }
            }.onFailure {
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("会議一覧を読み込めませんでした", "Could not load meeting list"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadBackupMeetingOptions(): List<BackupMeetingOption> {
        val meetings = EncryptedMeetingDatabase(this).use { database ->
            database.listMeetingIds().mapNotNull { meetingId ->
                val metadata = database.readMeeting(meetingId) ?: return@mapNotNull null
                val title = MeetingExportDocumentBuilder
                    .build(metadata, database.listCurrentRevisions(meetingId))
                    .title
                    .replace(Regex("[\\r\\n]+"), " ")
                    .trim()
                    .take(80)
                    .takeIf { it.isNotBlank() }
                    ?: AppLanguage.text("議事録", "Minutes")
                metadata to title
            }
        }
        val recordingStore = RecordingStore(this)
        return meetings
            .sortedByDescending { (metadata, _) -> metadata.createdAtEpochMs }
            .mapNotNull { (metadata, title) ->
                val reference = recordingStore.find(metadata.meetingId) ?: return@mapNotNull null
                val created = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT
                ).format(Date(metadata.createdAtEpochMs))
                val duration = metadata.durationMs?.let(::backupDurationLabel) ?: AppLanguage.text("時間不明", "Unknown duration")
                val audio = when (metadata.audioState) {
                    AudioState.RECORDING -> AppLanguage.text("録音未完了", "Recording incomplete")
                    AudioState.FINALIZED -> AppLanguage.text("音声あり", "Audio available")
                    AudioState.DELETED -> AppLanguage.text("音声削除済み", "Audio deleted")
                }
                BackupMeetingOption(reference, "$title\n$created ・ $duration ・ $audio")
            }
    }

    private fun showCreateBackupDialog(option: BackupMeetingOption) {
        val reference = option.reference
        val passphrase = passwordField(AppLanguage.text("パスフレーズ（12文字以上）", "Passphrase (at least 12 characters)"))
        val confirmation = passwordField(AppLanguage.text("パスフレーズをもう一度入力", "Enter passphrase again"))
        val recovery = CheckBox(this).apply { text = AppLanguage.text("一度だけ表示する回復キーも作る", "Also generate a recovery key shown only once") }
        val content = dialogColumn().apply {
            addView(TextView(context).apply {
                text = AppLanguage.text("選択した会議:\n${option.label}\n\n", "Selected meeting:\n${option.label}\n\n") +
                    AppLanguage.text("音声と議事録を暗号化して、選択した保存先へ書き出します。", "Audio and minutes will be encrypted and exported to your chosen destination. ") +
                    AppLanguage.text("APIキー・端末鍵は含みません。", "API keys and device keys are excluded.")
            })
            addView(passphrase)
            addView(confirmation)
            addView(recovery)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("暗号化バックアップを作成", "Create encrypted backup"))
            .setView(content)
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("保存先を選ぶ", "Choose destination"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = passphrase.text.toString()
                val second = confirmation.text.toString()
                when {
                    first.length < MIN_BACKUP_PASSPHRASE_LENGTH -> {
                        passphrase.error = AppLanguage.text("12文字以上で入力してください", "Enter at least 12 characters")
                    }
                    first != second -> confirmation.error = AppLanguage.text("入力が一致しません", "Entries do not match")
                    else -> {
                        clearPendingBackup()
                        pendingBackupReference = reference
                        pendingBackupPassphrase = first.toCharArray()
                        pendingBackupRecovery = recovery.isChecked
                        passphrase.text?.clear()
                        confirmation.text?.clear()
                        dialog.dismiss()
                        createBackupDocument.launch(defaultBackupFilename())
                    }
                }
            }
        }
        dialog.show()
    }

    private fun handleBackupDestination(uri: Uri?) {
        val reference = pendingBackupReference
        val passphrase = pendingBackupPassphrase
        val recovery = pendingBackupRecovery
        pendingBackupReference = null
        pendingBackupPassphrase = null
        pendingBackupRecovery = false
        if (uri == null || reference == null || passphrase == null) {
            passphrase?.fill('\u0000')
            return
        }
        Toast.makeText(this, AppLanguage.text("暗号化バックアップを作成しています", "Creating encrypted backup"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        PortableBackupCoordinator(this@ProviderSettingsActivity).create(
                            reference,
                            output,
                            passphrase,
                            recovery
                        )
                    } ?: throw PortableBackupException("Could not open backup destination")
                }
            }
            outcome.onSuccess { result ->
                if (result.recoveryKey == null) {
                    Toast.makeText(
                        this@ProviderSettingsActivity,
                        AppLanguage.text("暗号化バックアップを保存しました", "Encrypted backup saved"),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showOneTimeRecoveryKey(result.recoveryKey)
                }
            }.onFailure { error ->
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    friendlyBackupError(error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showOneTimeRecoveryKey(recoveryKey: String) {
        val qrSize = dp(280).coerceIn(128, 2048)
        val qrBitmap = RecoveryKeyQr.bitmap(recoveryKey, qrSize)
        val qrView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            contentDescription = AppLanguage.text("回復キーのQRコード", "Recovery key QR code")
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val keyView = TextView(this).apply {
            text = recoveryKey
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val content = dialogColumn().apply {
            addView(qrView)
            addView(keyView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("回復キー（表示は今回だけ）", "Recovery key (shown only now)"))
            .setMessage(
                AppLanguage.text("パスフレーズを忘れた場合に必要です。別の安全な場所へ保存してください。", "Needed if you forget your passphrase. Save it in a separate, secure location. ") +
                    AppLanguage.text("このアプリやメーカーは回復キーを保管せず、再発行もできません。", "Neither this app nor its maker stores or can reissue the recovery key.")
            )
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(AppLanguage.text("安全な場所へ保存しました", "Saved in a secure location")) { _, _ ->
                Toast.makeText(this, AppLanguage.text("バックアップを保存しました", "Backup saved"), Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.setOnDismissListener {
            qrView.setImageDrawable(null)
            qrBitmap.eraseColor(Color.WHITE)
            qrBitmap.recycle()
            keyView.text = ""
        }
        dialog.show()
    }

    private fun showRestoreCredentialDialog(uri: Uri) {
        val credential = EditText(this).apply {
            hint = AppLanguage.text("バックアップのパスフレーズ", "Backup passphrase")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(false)
        }
        val useRecovery = CheckBox(this).apply { text = AppLanguage.text("回復キーを使う", "Use recovery key") }
        useRecovery.setOnCheckedChangeListener { _, checked ->
            credential.hint = if (checked) AppLanguage.text("回復キー", "Recovery key") else AppLanguage.text("バックアップのパスフレーズ", "Backup passphrase")
            credential.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        val content = dialogColumn().apply {
            addView(TextView(context).apply {
                text = AppLanguage.text("まずファイル全体の破損と必要容量を確認します。この段階では既存データを変更しません。", "First, the entire file and required storage will be verified. Existing data will not be changed at this stage.")
            })
            addView(useRecovery)
            addView(credential)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("暗号化バックアップを確認", "Check encrypted backup"))
            .setView(content)
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("確認", "Check"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = credential.text.toString()
                if (entered.isBlank()) {
                    credential.error = AppLanguage.text("入力してください", "Enter a value")
                    return@setOnClickListener
                }
                val inspectCredential: BackupCredential
                val restoreCredential: BackupCredential
                if (useRecovery.isChecked) {
                    inspectCredential = BackupCredential.RecoveryKey(entered)
                    restoreCredential = BackupCredential.RecoveryKey(entered)
                } else {
                    val characters = entered.toCharArray()
                    inspectCredential = BackupCredential.Passphrase(characters.clone())
                    restoreCredential = BackupCredential.Passphrase(characters.clone())
                    characters.fill('\u0000')
                }
                credential.text?.clear()
                dialog.dismiss()
                inspectBackup(uri, inspectCredential, restoreCredential)
            }
        }
        dialog.show()
    }

    private fun inspectBackup(
        uri: Uri,
        inspectCredential: BackupCredential,
        restoreCredential: BackupCredential
    ) {
        Toast.makeText(this, AppLanguage.text("バックアップを検証しています", "Verifying backup"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val coordinator = PortableBackupCoordinator(this@ProviderSettingsActivity)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.inspect(
                        openInput = {
                            contentResolver.openInputStream(uri)
                                ?: throw PortableBackupException("Could not open backup")
                        },
                        credential = inspectCredential
                    )
                }
            }
            outcome.onSuccess { preview ->
                showRestoreConfirmation(uri, coordinator, preview, restoreCredential)
            }.onFailure { error ->
                clearCredential(restoreCredential)
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    friendlyBackupError(error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showRestoreConfirmation(
        uri: Uri,
        coordinator: PortableBackupCoordinator,
        preview: BackupPreview,
        restoreCredential: BackupCredential
    ) {
        val created = DateFormat.getDateTimeInstance().format(Date(preview.createdAtEpochMs))
        val audio = if (preview.hasAudio) formatBytes(preview.audioPlaintextBytes) else AppLanguage.text("音声なし", "No audio")
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("このバックアップを復元しますか？", "Restore this backup?"))
            .setMessage(
                AppLanguage.text("作成日時: $created\n", "Created: $created\n") +
                    AppLanguage.text("履歴: ${preview.revisionCount}版\n", "History: ${preview.revisionCount} versions\n") +
                    AppLanguage.text("音声: $audio\n", "Audio: $audio\n") +
                    AppLanguage.text("必要な空き容量の目安: ${formatBytes(preview.requiredFreeBytes)}\n\n", "Estimated free space required: ${formatBytes(preview.requiredFreeBytes)}\n\n") +
                    AppLanguage.text("同じ会議がある場合は上書きせず、別の会議として複製します。APIキーは復元されません。", "If the meeting already exists, a separate copy will be created. API keys are not restored.")
            )
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel")) { _, _ ->
                clearCredential(restoreCredential)
                clearPreview(preview)
            }
            .setPositiveButton(AppLanguage.text("復元", "Restored")) { _, _ ->
                restoreBackup(uri, coordinator, preview, restoreCredential)
            }
            .setOnCancelListener {
                clearCredential(restoreCredential)
                clearPreview(preview)
            }
            .show()
    }

    private fun restoreBackup(
        uri: Uri,
        coordinator: PortableBackupCoordinator,
        preview: BackupPreview,
        credential: BackupCredential
    ) {
        Toast.makeText(this, AppLanguage.text("暗号化したまま復元しています", "Restoring encrypted data"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.restore(
                        openInput = {
                            contentResolver.openInputStream(uri)
                                ?: throw PortableBackupException("Could not reopen backup")
                        },
                        credential = credential,
                        preview = preview
                    )
                }
            }
            outcome.onSuccess { restored ->
                val suffix = if (restored.duplicatedBecauseOfCollision) AppLanguage.text("（既存会議は上書きしていません）", " (Existing meetings were not overwritten)") else ""
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    AppLanguage.text("バックアップを復元しました$suffix", "Backup restored$suffix"),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                clearCredential(credential)
                clearPreview(preview)
                Toast.makeText(
                    this@ProviderSettingsActivity,
                    friendlyBackupError(error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun passwordField(label: String) = EditText(this).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dialogColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun defaultBackupFilename(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date())
        return "gijiroku-$timestamp.gjrbak"
    }

    private fun backupDurationLabel(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0) / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun defaultExportFilename(format: MeetingExportFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date())
        return "gijiroku-$timestamp.${format.extension}"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(
            Locale.JAPAN,
            "%.1f GB",
            bytes / (1024.0 * 1024.0 * 1024.0)
        )
        bytes >= 1024L * 1024L -> String.format(Locale.JAPAN, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.JAPAN, "%.1f KB", bytes / 1024.0)
    }

    private fun friendlyBackupError(error: Throwable): String = when {
        error.message?.contains("free space", ignoreCase = true) == true -> AppLanguage.text("空き容量が不足しています", "Not enough free space")
        error is PortableBackupException -> AppLanguage.text("復号できません。入力内容またはバックアップの破損を確認してください", "Cannot decrypt. Check your credentials or whether the backup is corrupt.")
        else -> AppLanguage.text("バックアップ処理に失敗しました", "Backup operation failed")
    }

    private fun clearPendingBackup() {
        pendingBackupPassphrase?.fill('\u0000')
        pendingBackupReference = null
        pendingBackupPassphrase = null
        pendingBackupRecovery = false
    }

    private fun clearCredential(credential: BackupCredential) {
        if (credential is BackupCredential.Passphrase) credential.characters.fill('\u0000')
    }

    private fun clearPreview(preview: BackupPreview) {
        preview.backupSha256.fill(0)
        preview.manifestSha256.fill(0)
    }

    private data class BackupMeetingOption(
        val reference: RecordingReference,
        val label: String
    )

    private fun saveProviderSettings() {
        if (settingsPage == "templates") {
            val templates = MeetingMinutesTemplates.builtIns
            minutesTemplateSettings.selectedTemplateId = templates[binding.spinnerMinutesTemplate.selectedItemPosition].id
            minutesTemplateSettings.customInstructions = binding.editCustomMinutesInstructions.text.toString()
            Toast.makeText(this, AppLanguage.text("議事録の用途を保存しました", "Minutes template saved"), Toast.LENGTH_SHORT).show()
            return
        }
        val openAiEditable = binding.editOpenAiApiKey.text
        val geminiEditable = binding.editGeminiApiKey.text
        val modelInputs = cloudModelTargets().mapNotNull { target ->
            val raw = target.field.text.toString().trim()
            if (raw.isEmpty()) null else try {
                target to CloudModelNameValidator.normalize(target.provider, raw)
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this, "${target.label}: ${error.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        val missingSelectedModel = cloudModelTargets().firstOrNull { target ->
            target.isProviderSelected() && target.field.text.toString().isBlank()
        }
        if (missingSelectedModel != null) {
            Toast.makeText(this, AppLanguage.text("${missingSelectedModel.label}を入力または選択してください", "Enter or select ${missingSelectedModel.label}"), Toast.LENGTH_LONG).show()
            return
        }
        if (binding.radioLocalQwenSummary.isChecked && !modelStore.hasQwenSummaryModel()) {
            Toast.makeText(this, AppLanguage.text("先に検証済みのQwen3-4Bモデルを導入してください", "Install a verified Qwen3-4B model first"), Toast.LENGTH_LONG).show()
            return
        }
        if (openAiEditable.isNotEmpty()) {
            if (openAiEditable.length < MIN_API_KEY_LENGTH) {
                Toast.makeText(this, AppLanguage.text("OpenAI APIキーが短すぎます", "OpenAI API key is too short"), Toast.LENGTH_SHORT).show()
                return
            }
            val characters = CharArray(openAiEditable.length) { openAiEditable[it] }
            secretStore.save(SecretStore.OPENAI_API_KEY, characters)
            openAiEditable.clear()
            clearClipboard()
        }
        if (geminiEditable.isNotEmpty()) {
            if (geminiEditable.length < MIN_API_KEY_LENGTH) {
                Toast.makeText(this, AppLanguage.text("Gemini APIキーが短すぎます", "Gemini API key is too short"), Toast.LENGTH_SHORT).show()
                return
            }
            val characters = CharArray(geminiEditable.length) { geminiEditable[it] }
            secretStore.save(SecretStore.GEMINI_API_KEY, characters)
            geminiEditable.clear()
            clearClipboard()
        }
        val needsOpenAi = binding.radioOpenAiWhisper.isChecked ||
            binding.radioOpenAiFormatting.isChecked ||
            binding.radioOpenAiSummary.isChecked
        if (needsOpenAi && !secretStore.hasSecret(SecretStore.OPENAI_API_KEY)) {
            Toast.makeText(this, AppLanguage.text("APIキーを入力してください", "Enter an API key"), Toast.LENGTH_SHORT).show()
            return
        }
        val needsGemini = binding.radioGeminiTranscription.isChecked ||
            binding.radioGeminiFormatting.isChecked || binding.radioGeminiSummary.isChecked
        if (needsGemini && !secretStore.hasSecret(SecretStore.GEMINI_API_KEY)) {
            Toast.makeText(this, AppLanguage.text("Gemini APIキーを入力してください", "Enter a Gemini API key"), Toast.LENGTH_SHORT).show()
            return
        }
        modelInputs.forEach { (target, value) ->
            cloudSettings.addAndSelectModel(target.provider, target.stage, value)
        }
        val templates = MeetingMinutesTemplates.builtIns
        val selectedTemplate = templates.getOrElse(binding.spinnerMinutesTemplate.selectedItemPosition) {
            templates.first()
        }
        minutesTemplateSettings.selectedTemplateId = selectedTemplate.id
        minutesTemplateSettings.customInstructions = binding.editCustomMinutesInstructions.text.toString()
        providerPreferences.selectProvider(
            PipelineStage.TRANSCRIPTION,
            when {
                binding.radioOpenAiWhisper.isChecked -> ProviderIds.OPENAI_WHISPER
                binding.radioGeminiTranscription.isChecked -> ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION
                else -> ProviderIds.LOCAL_WHISPER
            }
        )
        providerPreferences.selectProvider(
            PipelineStage.TEXT_FORMATTING,
            when {
                binding.radioOpenAiFormatting.isChecked -> ProviderIds.OPENAI_RESPONSES_FORMATTING
                binding.radioGeminiFormatting.isChecked -> ProviderIds.GEMINI_FLASH_LITE_FORMATTING
                else -> ProviderIds.LOCAL_NOOP_TEXT
            }
        )
        providerPreferences.selectProvider(
            PipelineStage.SUMMARIZATION,
            when {
                binding.radioLocalQwenSummary.isChecked -> ProviderIds.LOCAL_QWEN3_SUMMARY
                binding.radioOpenAiSummary.isChecked -> ProviderIds.OPENAI_RESPONSES_SUMMARY
                binding.radioGeminiSummary.isChecked -> ProviderIds.GEMINI_FLASH_LITE_SUMMARY
                else -> null
            }
        )
        refreshStatus()
        refreshCloudModelFields()
        Toast.makeText(this, AppLanguage.text("AI設定を保存しました", "AI settings saved"), Toast.LENGTH_SHORT).show()
    }

    private fun deleteOpenAiKey() {
        secretStore.delete(SecretStore.OPENAI_API_KEY)
        if (providerPreferences.selectedProvider(PipelineStage.TRANSCRIPTION) == ProviderIds.OPENAI_WHISPER) {
            providerPreferences.selectProvider(PipelineStage.TRANSCRIPTION, ProviderIds.LOCAL_WHISPER)
            binding.radioLocalWhisper.isChecked = true
        }
        if (providerPreferences.selectedProvider(PipelineStage.SUMMARIZATION) == ProviderIds.OPENAI_RESPONSES_SUMMARY) {
            providerPreferences.selectProvider(PipelineStage.SUMMARIZATION, null)
            binding.radioNoSummary.isChecked = true
        }
        if (providerPreferences.selectedProvider(PipelineStage.TEXT_FORMATTING) == ProviderIds.OPENAI_RESPONSES_FORMATTING) {
            providerPreferences.selectProvider(PipelineStage.TEXT_FORMATTING, ProviderIds.LOCAL_NOOP_TEXT)
            binding.radioLocalFormatting.isChecked = true
        }
        binding.editOpenAiApiKey.text?.clear()
        refreshStatus()
        Toast.makeText(this, AppLanguage.text("OpenAI APIキーを削除し、該当工程だけローカルへ変更しました", "OpenAI API key deleted; affected stages switched to local processing"), Toast.LENGTH_SHORT).show()
    }

    private fun deleteGeminiKey() {
        secretStore.delete(SecretStore.GEMINI_API_KEY)
        if (providerPreferences.selectedProvider(PipelineStage.TRANSCRIPTION) == ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION) {
            providerPreferences.selectProvider(PipelineStage.TRANSCRIPTION, ProviderIds.LOCAL_WHISPER)
            binding.radioLocalWhisper.isChecked = true
        }
        if (providerPreferences.selectedProvider(PipelineStage.TEXT_FORMATTING) == ProviderIds.GEMINI_FLASH_LITE_FORMATTING) {
            providerPreferences.selectProvider(PipelineStage.TEXT_FORMATTING, ProviderIds.LOCAL_NOOP_TEXT)
            binding.radioLocalFormatting.isChecked = true
        }
        if (providerPreferences.selectedProvider(PipelineStage.SUMMARIZATION) == ProviderIds.GEMINI_FLASH_LITE_SUMMARY) {
            providerPreferences.selectProvider(PipelineStage.SUMMARIZATION, null)
            binding.radioNoSummary.isChecked = true
        }
        binding.editGeminiApiKey.text?.clear()
        refreshStatus()
        Toast.makeText(this, AppLanguage.text("Gemini APIキーを削除し、該当工程をローカルへ変更しました", "Gemini API key deleted; affected stages switched to local processing"), Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val masked = secretStore.maskedLabel(SecretStore.OPENAI_API_KEY)
        binding.textOpenAiKeyStatus.text = if (masked == null) {
            AppLanguage.text("APIキー: 未設定", "API key: Not configured")
        } else {
            AppLanguage.text("APIキー: 保存済み（$masked）", "API key: Saved ($masked)")
        }
        binding.buttonDeleteOpenAiKey.isEnabled = masked != null
        val geminiMasked = secretStore.maskedLabel(SecretStore.GEMINI_API_KEY)
        binding.textGeminiKeyStatus.text = if (geminiMasked == null) {
            AppLanguage.text("Gemini APIキー: 未設定", "Gemini API key: Not configured")
        } else {
            AppLanguage.text("Gemini APIキー: 保存済み（$geminiMasked）", "Gemini API key: Saved ($geminiMasked)")
        }
        binding.buttonDeleteGeminiKey.isEnabled = geminiMasked != null
        binding.textPipelineDetails.text = currentPipelineDetails()
        refreshCloudModelLabels()
        refreshPipelineModelLabels()
    }

    private fun refreshCloudModelFields() {
        cloudModelTargets().forEach { target ->
            target.field.setText(cloudSettings.selectedModel(target.provider, target.stage).orEmpty())
        }
        refreshCloudModelLabels()
    }

    private fun refreshCloudModelLabels() {
        binding.textGeminiEndpoint.text = buildString {
            appendLine(AppLanguage.text("送信先: ${CloudProviderSettings.GEMINI_BASE_URL}", "Destination: ${CloudProviderSettings.GEMINI_BASE_URL}"))
            appendLine(AppLanguage.text("音声認識: ${cloudSettings.geminiModel(PipelineStage.TRANSCRIPTION) ?: "未選択"}", "Transcription: ${cloudSettings.geminiModel(PipelineStage.TRANSCRIPTION) ?: "Not selected"}"))
            appendLine(AppLanguage.text("テキスト整形: ${cloudSettings.geminiModel(PipelineStage.TEXT_FORMATTING) ?: "未選択"}", "Text formatting: ${cloudSettings.geminiModel(PipelineStage.TEXT_FORMATTING) ?: "Not selected"}"))
            append(AppLanguage.text("要約: ${cloudSettings.geminiModel(PipelineStage.SUMMARIZATION) ?: "未選択"}", "Summary: ${cloudSettings.geminiModel(PipelineStage.SUMMARIZATION) ?: "Not selected"}"))
        }
    }

    private fun showCloudModelCategories() {
        val targets = cloudModelTargets()
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("クラウドモデルの工程", "Cloud model stage"))
            .setItems(targets.map(CloudModelTarget::label).toTypedArray()) { _, index ->
                showRegisteredCloudModels(targets[index])
            }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun showRegisteredCloudModels(target: CloudModelTarget) {
        val models = cloudSettings.models(target.provider, target.stage)
        if (models.isEmpty()) {
            Toast.makeText(this, AppLanguage.text("登録済みモデルがありません。入力欄へモデル名を入力して保存してください", "No registered models. Enter a model name and save."), Toast.LENGTH_LONG).show()
            return
        }
        val selected = cloudSettings.selectedModel(target.provider, target.stage)
        val labels = models.map { if (it == selected) "✓ $it" else it }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(target.label)
            .setItems(labels) { _, index -> showCloudModelActions(target, models[index]) }
            .setNegativeButton(AppLanguage.text("戻る", "Back")) { _, _ -> showCloudModelCategories() }
            .show()
    }

    private fun showCloudModelActions(target: CloudModelTarget, model: String) {
        AlertDialog.Builder(this)
            .setTitle(model)
            .setItems(arrayOf(AppLanguage.text("このモデルを選択", "Select this model"), AppLanguage.text("登録から削除", "Remove from library"))) { _, action ->
                if (action == 0) {
                    cloudSettings.selectModel(target.provider, target.stage, model)
                    target.field.setText(model)
                    refreshStatus()
                    Toast.makeText(this, AppLanguage.text("${target.label}に選択しました", "Selected for ${target.label}"), Toast.LENGTH_SHORT).show()
                } else {
                    confirmCloudModelDeletion(target, model)
                }
            }
            .setNegativeButton(AppLanguage.text("閉じる", "Close"), null)
            .show()
    }

    private fun confirmCloudModelDeletion(target: CloudModelTarget, model: String) {
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text("モデル名を登録から削除", "Remove model name from library"))
            .setMessage(AppLanguage.text("$model を端末内の一覧から削除します。Provider上のモデルやAPIキーは削除されません。", "Remove $model from this device's list. The provider's model and your API key remain."))
            .setNegativeButton(AppLanguage.text("キャンセル", "Cancel"), null)
            .setPositiveButton(AppLanguage.text("削除", "Delete")) { _, _ ->
                cloudSettings.deleteModel(target.provider, target.stage, model)
                refreshCloudModelFields()
                refreshStatus()
                Toast.makeText(this, AppLanguage.text("モデル名を登録から削除しました", "Model name removed from library"), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun cloudModelTargets(): List<CloudModelTarget> = listOf(
        CloudModelTarget(CloudModelProvider.OPENAI, PipelineStage.TRANSCRIPTION, AppLanguage.text("OpenAI・音声認識", "OpenAI · Transcription"), binding.editOpenAiModel) {
            binding.radioOpenAiWhisper.isChecked
        },
        CloudModelTarget(CloudModelProvider.OPENAI, PipelineStage.TEXT_FORMATTING, AppLanguage.text("OpenAI・テキスト整形", "OpenAI · Text formatting"), binding.editOpenAiFormattingModel) {
            binding.radioOpenAiFormatting.isChecked
        },
        CloudModelTarget(CloudModelProvider.OPENAI, PipelineStage.SUMMARIZATION, AppLanguage.text("OpenAI・要約", "OpenAI · Summary"), binding.editOpenAiSummaryModel) {
            binding.radioOpenAiSummary.isChecked
        },
        CloudModelTarget(CloudModelProvider.GEMINI, PipelineStage.TRANSCRIPTION, AppLanguage.text("Gemini・音声認識", "Gemini · Transcription"), binding.editGeminiTranscriptionModel) {
            binding.radioGeminiTranscription.isChecked
        },
        CloudModelTarget(CloudModelProvider.GEMINI, PipelineStage.TEXT_FORMATTING, AppLanguage.text("Gemini・テキスト整形", "Gemini · Text formatting"), binding.editGeminiFormattingModel) {
            binding.radioGeminiFormatting.isChecked
        },
        CloudModelTarget(CloudModelProvider.GEMINI, PipelineStage.SUMMARIZATION, AppLanguage.text("Gemini・要約", "Gemini · Summary"), binding.editGeminiSummaryModel) {
            binding.radioGeminiSummary.isChecked
        }
    )

    private data class CloudModelTarget(
        val provider: CloudModelProvider,
        val stage: PipelineStage,
        val label: String,
        val field: EditText,
        val isProviderSelected: () -> Boolean
    )

    private fun currentPipelineDetails(): String {
        val transcription = providerPreferences.selectedProvider(PipelineStage.TRANSCRIPTION)
        val formatting = providerPreferences.selectedProvider(PipelineStage.TEXT_FORMATTING)
        val summary = providerPreferences.selectedProvider(PipelineStage.SUMMARIZATION)
        val audioEgress = when (transcription) {
            ProviderIds.OPENAI_WHISPER -> AppLanguage.text("音声 → api.openai.com", "Audio → api.openai.com")
            ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION -> AppLanguage.text("音声 → generativelanguage.googleapis.com", "Audio → generativelanguage.googleapis.com")
            else -> AppLanguage.text("音声 → 送信なし", "Audio → Not sent")
        }
        val textDestinations = buildList {
            if (formatting == ProviderIds.OPENAI_RESPONSES_FORMATTING) {
                add(AppLanguage.text("文字起こし → api.openai.com（整形）", "Transcript → api.openai.com (formatting)"))
            }
            if (formatting == ProviderIds.GEMINI_FLASH_LITE_FORMATTING) {
                add(AppLanguage.text("文字起こし → generativelanguage.googleapis.com（整形）", "Transcript → generativelanguage.googleapis.com (formatting)"))
            }
            when (summary) {
                ProviderIds.OPENAI_RESPONSES_SUMMARY -> add(AppLanguage.text("文字起こし → api.openai.com（要約）", "Transcript → api.openai.com (summary)"))
                ProviderIds.GEMINI_FLASH_LITE_SUMMARY -> add(AppLanguage.text("文字起こし → generativelanguage.googleapis.com（要約）", "Transcript → generativelanguage.googleapis.com (summary)"))
            }
        }
        return buildString {
            appendLine(AppLanguage.text("保存済み設定の送信先", "Destinations in saved settings"))
            appendLine(AppLanguage.text("音声の前処理・話者分離は端末内で行います。", "Audio preprocessing and speaker separation run on this device."))
            appendLine(audioEgress)
            if (textDestinations.isEmpty()) append(AppLanguage.text("文字起こし → 送信なし", "Transcript → Not sent"))
            else append(textDestinations.joinToString("\n"))
        }
    }

    private fun clearClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    override fun onStop() {
        binding.editOpenAiApiKey.text?.clear()
        binding.editGeminiApiKey.text?.clear()
        super.onStop()
    }

    companion object {
        private const val MIN_API_KEY_LENGTH = 20
        private const val MIN_BACKUP_PASSPHRASE_LENGTH = 12
        private const val BACKUP_MIME_TYPE = "application/vnd.gijiroku.backup"
        private val MODEL_MIME_TYPES = arrayOf("application/octet-stream", "*/*")
    }
}
