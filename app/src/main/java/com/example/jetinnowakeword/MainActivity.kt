package com.example.jetinnowakeword

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import org.vosk.Model
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detalhes: TextView
    private lateinit var transcricao: TextView
    private lateinit var audioInfo: TextView
    private lateinit var btnEscutar: Button
    private lateinit var btnParar: Button

    private var model: Model? = null
    private var wakeWord: JetinnoWakeWordManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var conversando = false
    private var destroyed = false
    private var userStopped = false
    private var vocabularySummary = ""
    private var deviceSummary = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = findViewById(R.id.txtStatus)
        detalhes = findViewById(R.id.txtDetalhes)
        transcricao = findViewById(R.id.txtTranscricao)
        audioInfo = findViewById(R.id.txtAudioInfo)
        btnEscutar = findViewById(R.id.btnEscutar)
        btnParar = findViewById(R.id.btnParar)

        btnEscutar.isEnabled = false
        btnParar.isEnabled = false

        Log.i(TAG, "Android=${Build.VERSION.SDK_INT}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}")

        btnEscutar.setOnClickListener {
            userStopped = false
            iniciarEscuta()
        }
        btnParar.setOnClickListener {
            userStopped = true
            pararEscuta("Escuta interrompida pelo operador")
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else {
            prepararModelo()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            prepararModelo()
        } else {
            detalhes.text = "Permissão de microfone necessária"
        }
    }

    private fun prepararModelo() {
        detalhes.text = "Preparando modelo offline..."

        Thread({
            try {
                val modelDir = prepareModelFiles()
                val loadedModel = Model(modelDir.absolutePath)
                val vocabulary = VoskVocabulary.inspect(
                    loadedModel,
                    listOf("ok", "jetinno", "jetino", "getino", "jeitinho", "destino")
                )
                vocabularySummary = vocabulary.entries.joinToString(", ") {
                    "${it.key}=${if (it.value) "sim" else "não"}"
                }

                runOnUiThread {
                    if (destroyed) {
                        loadedModel.close()
                        return@runOnUiThread
                    }

                    model = loadedModel
                    wakeWord = createWakeWordManager(loadedModel)
                    btnEscutar.isEnabled = true
                    btnParar.isEnabled = true
                    audioInfo.text = "Vocabulário: $vocabularySummary"
                    iniciarEscuta()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Erro ao carregar modelo Vosk", error)
                runOnUiThread {
                    detalhes.text = "Erro ao carregar modelo: ${error.message}"
                }
            }
        }, "Jetinno-ModelLoader").start()
    }

    private fun createWakeWordManager(loadedModel: Model) =
        JetinnoWakeWordManager(
            context = applicationContext,
            model = loadedModel,
            onDetected = ::ativarElevenLabs,
            onTranscription = { text, isFinal ->
                if (!destroyed) {
                    transcricao.text = when {
                        text.isBlank() -> "Aguardando fala..."
                        isFinal -> "Final: $text"
                        else -> "Parcial: $text"
                    }
                }
            },
            onAudioLevel = { level ->
                if (!destroyed) {
                    val levelText = "RMS %.1f | %.1f dBFS | pico %d".format(
                        level.rms,
                        level.dbFs,
                        level.peak
                    )
                    audioInfo.text = listOf(
                        deviceSummary,
                        levelText,
                        "Vocabulário: $vocabularySummary"
                    ).filter(String::isNotBlank).joinToString("\n")
                }
            },
            onAudioDevice = { description ->
                if (!destroyed) deviceSummary = description
            },
            onError = { error ->
                if (!destroyed) {
                    detalhes.text = error
                    status.text = "ERRO NA ESCUTA"
                    btnEscutar.isEnabled = true
                    btnParar.isEnabled = false
                }
            }
        )

    private fun prepareModelFiles(): File {
        val destination = File(filesDir, MODEL_DIRECTORY)
        val marker = File(destination, MODEL_MARKER)
        if (marker.readTextOrNull() == MODEL_VERSION && isValidV1Model(destination)) {
            Log.i(TAG, "Modelo Vosk já preparado em ${destination.absolutePath}")
            return destination
        }

        val staging = File(filesDir, "$MODEL_DIRECTORY.tmp")
        staging.deleteRecursively()
        copiarAssets(MODEL_ASSET_DIRECTORY, staging)
        check(isValidV1Model(staging)) {
            "Assets do $MODEL_VERSION estão incompletos ou corrompidos"
        }
        File(staging, MODEL_MARKER).writeText(MODEL_VERSION)

        check(destination.deleteRecursively()) {
            "Não foi possível substituir o modelo Vosk antigo"
        }
        check(staging.renameTo(destination)) {
            "Não foi possível concluir a instalação local do modelo Vosk"
        }

        Log.i(TAG, "Modelo Vosk preparado em ${destination.absolutePath}")
        return destination
    }

    private fun isValidV1Model(directory: File): Boolean = REQUIRED_MODEL_FILES.all {
        File(directory, it).let { file -> file.isFile && file.length() > 0L }
    }

    private fun copiarAssets(path: String, destination: File) {
        destination.mkdirs()
        for (name in assets.list(path).orEmpty()) {
            val source = "$path/$name"
            if (assets.list(source).isNullOrEmpty()) {
                assets.open(source).use { input ->
                    File(destination, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                copiarAssets(source, File(destination, name))
            }
        }
    }

    private fun iniciarEscuta() {
        if (conversando || destroyed) return

        val started = wakeWord?.start() == true
        if (started) {
            status.text = "JETINNO — ESCUTANDO"
            detalhes.text = "Diga: OK Jetinno"
            transcricao.text = "Aguardando fala..."
            btnEscutar.isEnabled = false
            btnParar.isEnabled = true
        } else if (wakeWord?.isRunning() != true) {
            detalhes.text = "A escuta ainda está encerrando; tente novamente"
        }
    }

    private fun pararEscuta(message: String) {
        val released = wakeWord?.stop() != false
        status.text = if (released) "JETINNO" else "ERRO AO LIBERAR MICROFONE"
        detalhes.text = if (released) message else "A thread de áudio não encerrou no prazo"
        btnEscutar.isEnabled = released && model != null
        btnParar.isEnabled = !released
    }

    /** Chamado somente depois de AudioRecord.stop() e release(). */
    private fun ativarElevenLabs() {
        if (conversando || destroyed) return

        conversando = true
        status.text = "JETINNO ATIVADO!"
        detalhes.text = "Microfone liberado; iniciando atendimento..."
        btnEscutar.isEnabled = false
        btnParar.isEnabled = false

        // Protótipo: substituir pelo callback real de término da sessão ElevenLabs.
        handler.postDelayed(::finalizarAtendimento, DEMO_CONVERSATION_MS)
    }

    private fun finalizarAtendimento() {
        if (destroyed) return
        conversando = false
        status.text = "JETINNO"
        detalhes.text = "Atendimento finalizado; retomando escuta..."

        handler.postDelayed({
            if (!userStopped) iniciarEscuta()
        }, RESUME_DELAY_MS)
    }

    override fun onStop() {
        if (!isChangingConfigurations && !conversando) wakeWord?.stop()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (model != null && !conversando && !userStopped && !destroyed) iniciarEscuta()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        val released = wakeWord?.stop() != false
        wakeWord = null
        if (released) {
            model?.close()
        } else {
            Log.e(TAG, "Modelo mantido aberto porque a thread de áudio não encerrou")
        }
        model = null
        super.onDestroy()
    }

    private fun File.readTextOrNull(): String? =
        try {
            if (isFile) readText() else null
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val TAG = "JetinnoWakeWord"
        private const val REQUEST_RECORD_AUDIO = 100
        private const val MODEL_ASSET_DIRECTORY = "model"
        private const val MODEL_DIRECTORY = "vosk-model-small-pt-0.3"
        private const val MODEL_VERSION = "vosk-model-small-pt-0.3-v1"
        private const val MODEL_MARKER = ".model-version"
        private const val DEMO_CONVERSATION_MS = 5_000L
        private const val RESUME_DELAY_MS = 500L

        private val REQUIRED_MODEL_FILES = listOf(
            "final.mdl",
            "mfcc.conf",
            "HCLr.fst",
            "Gr.fst",
            "disambig_tid.int",
            "word_boundary.int",
            "ivector/final.ie",
            "ivector/final.dubm",
            "ivector/final.mat"
        )
    }
}
