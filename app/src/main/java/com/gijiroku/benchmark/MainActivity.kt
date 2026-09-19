package com.gijiroku.benchmark

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.gijiroku.benchmark.databinding.ActivityMainBinding
import java.io.File

class MainActivity : SecureActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var modelUri: Uri? = null
    private var wavUri: Uri? = null
    private var wavLabel: String = ""

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            modelUri = uri
            binding.textModelPath.text = AppLanguage.text("モデル: ${queryDisplayName(uri)}", "Model: ${queryDisplayName(uri)}")
        }
    }

    private val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            wavUri = uri
            wavLabel = queryDisplayName(uri)
            binding.textWavPath.text = AppLanguage.text("音声: $wavLabel", "Audio: $wavLabel")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonPickModel.setOnClickListener {
            pickModel.launch(arrayOf("application/octet-stream", "*/*"))
        }
        binding.buttonPickWav.setOnClickListener {
            pickWav.launch(arrayOf("audio/x-wav", "audio/wav", "*/*"))
        }
        binding.buttonRun.setOnClickListener { onRunClicked() }
        binding.buttonShare.setOnClickListener { onShareClicked() }
    }

    private fun onRunClicked() {
        val modelUri = this.modelUri
        val wavUri = this.wavUri
        if (modelUri == null || wavUri == null) {
            Toast.makeText(this, AppLanguage.text("モデルと音声ファイルを両方選択してください", "Choose both a model and an audio file"), Toast.LENGTH_SHORT).show()
            return
        }
        val nThreads = binding.editThreads.text.toString().toIntOrNull() ?: 4
        val repeats = binding.editRepeats.text.toString().toIntOrNull() ?: 1

        binding.buttonRun.isEnabled = false
        log(AppLanguage.text("---- ベンチマーク開始 ----", "---- Benchmark started ----"))

        Thread {
            try {
                val modelFile = copyToCache(modelUri, "model.bin")
                val wav = contentResolver.openInputStream(wavUri)!!.use { WavLoader.load(it) }
                log(AppLanguage.text("音声長: %.1f秒".format(wav.durationSec), "Audio duration: %.1f sec".format(wav.durationSec)))

                BenchmarkRunner(applicationContext).run(
                    modelFile = modelFile,
                    wav = wav,
                    audioFileLabel = wavLabel,
                    nThreads = nThreads,
                    repeats = repeats,
                    onLog = { msg -> log(msg) }
                )
            } catch (e: Exception) {
                log(AppLanguage.text("エラー: ${e.message}", "Error: ${e.message}"))
            } finally {
                mainHandler.post { binding.buttonRun.isEnabled = true }
            }
        }.start()
    }

    private fun onShareClicked() {
        val file = CsvExporter(applicationContext).file
        if (!file.exists()) {
            Toast.makeText(this, AppLanguage.text("まだ結果CSVがありません", "No results CSV yet"), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, AppLanguage.text("結果CSVを共有", "Share results CSV")))
    }

    private fun copyToCache(uri: Uri, filename: String): File {
        val out = File(cacheDir, filename)
        contentResolver.openInputStream(uri)!!.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun log(msg: String) {
        mainHandler.post {
            binding.textLog.append(msg + "\n")
        }
    }
}
