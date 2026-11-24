package com.example.jetinnowakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

class JetinnoWakeWordManager(
    context: Context,
    private val model: Model,
    private val onDetected: () -> Unit,
    private val onTranscription: (text: String, isFinal: Boolean) -> Unit,
    private val onAudioLevel: (AudioLevel) -> Unit,
    private val onAudioDevice: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    data class AudioLevel(
        val rms: Double,
        val dbFs: Double,
        val peak: Int
    )

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var keepRunning = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var recorder: AudioRecord? = null

    fun start(): Boolean = synchronized(stateLock) {
        if (worker?.isAlive == true) return false

        keepRunning = true
        Thread(::recognitionLoop, "Jetinno-WakeWord").also {
            worker = it
            it.start()
        }
        true
    }

    /** Interrompe a leitura e aguarda a thread proprietária liberar o AudioRecord. */
    fun stop(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        val thread: Thread?
        val audio: AudioRecord?

        synchronized(stateLock) {
            keepRunning = false
            thread = worker
            audio = recorder
        }

        if (thread == null || thread === Thread.currentThread()) return true

        try {
            if (audio?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audio.stop()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Falha não fatal ao interromper AudioRecord", error)
        }

        thread.interrupt()
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val released = !thread.isAlive
        if (!released) Log.e(TAG, "A thread de áudio não encerrou em ${timeoutMs} ms")
        return released
    }

    fun isRunning(): Boolean = keepRunning && worker?.isAlive == true

    @SuppressLint("MissingPermission")
    private fun recognitionLoop() {
        var localRecorder: AudioRecord? = null
        var detected = false
        var failure: Throwable? = null

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) {
                "PCM 16 kHz mono não é suportado (getMinBufferSize=$minBuffer)"
            }

            val bufferSizeBytes = maxOf(minBuffer * 2, READ_SAMPLES * 2)
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            localRecorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) {
                "Não foi possível inicializar o microfone; ele pode estar em uso"
            }

            val preferredDevice = findPreferredInputDevice()
            val preferenceAccepted = preferredDevice?.let(localRecorder::setPreferredDevice) ?: false
            recorder = localRecorder

            if (!keepRunning || Thread.currentThread().isInterrupted) return

            Log.i(
                TAG,
                "AudioRecord inicializado: source=VOICE_RECOGNITION, sampleRate=$SAMPLE_RATE, " +
                    "bufferBytes=$bufferSizeBytes, preferred=${preferredDevice.describe()}, " +
                    "accepted=$preferenceAccepted"
            )

            Log.i(TAG, "Gramática de ativação: $WAKE_WORD_GRAMMAR")
            Recognizer(model, SAMPLE_RATE.toFloat(), WAKE_WORD_GRAMMAR).use { recognizer ->
                recognizer.setWords(true)
                recognizer.setPartialWords(true)

                val samples = ShortArray(READ_SAMPLES)
                var lastPartialJson: String? = null
                var lastLevelUpdate = 0L

                if (!keepRunning || Thread.currentThread().isInterrupted) return@use
                localRecorder.startRecording()
                check(localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord não entrou no estado RECORDING"
                }

                val routedDevice = localRecorder.routedDevice
                val deviceDescription = buildString {
                    append("Preferido: ")
                    append(preferredDevice.describe())
                    append(" | Roteado: ")
                    append(routedDevice.describe())
                }
                Log.i(TAG, deviceDescription)
                post { onAudioDevice(deviceDescription) }

                while (keepRunning && !Thread.currentThread().isInterrupted) {
                    val count = localRecorder.read(samples, 0, samples.size)
                    if (count == 0) continue
                    if (count < 0) {
                        if (!keepRunning || Thread.currentThread().isInterrupted) break
                        error("Erro na leitura do microfone: $count")
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLevelUpdate >= LEVEL_UPDATE_INTERVAL_MS) {
                        val level = calculateAudioLevel(samples, count)
                        Log.d(
                            TAG,
                            "Áudio: rms=${format(level.rms)}, " +
                                "dBFS=${format(level.dbFs)}, peak=${level.peak}"
                        )
                        post { onAudioLevel(level) }
                        lastLevelUpdate = now
                    }

                    if (recognizer.acceptWaveForm(samples, count)) {
                        val json = recognizer.result
                        Log.i(TAG, "Transcrição final: $json")
                        val text = extractText(json, "text")
                        post { onTranscription(text, true) }
                        lastPartialJson = null
                        if (WakePhraseMatcher.matches(text)) {
                            detected = true
                            keepRunning = false
                            break
                        }
                    } else {
                        val json = recognizer.partialResult
                        if (json != lastPartialJson) {
                            Log.d(TAG, "Transcrição parcial: $json")
                            val text = extractText(json, "partial")
                            post { onTranscription(text, false) }
                            lastPartialJson = json
                            if (WakePhraseMatcher.matches(text)) {
                                detected = true
                                keepRunning = false
                                break
                            }
                        }
                    }
                }

                if (!detected) {
                    val finalJson = recognizer.finalResult
                    Log.i(TAG, "Transcrição final ao encerrar: $finalJson")
                    val finalText = extractText(finalJson, "text")
                    if (finalText.isNotBlank()) post { onTranscription(finalText, true) }
                }
            }
        } catch (error: Throwable) {
            if (keepRunning) failure = error
        } finally {
            keepRunning = false
            try {
                if (localRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    localRecorder.stop()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Falha não fatal ao parar o gravador", error)
            }
            localRecorder?.release()
            recorder = null

            synchronized(stateLock) {
                if (worker === Thread.currentThread()) worker = null
            }

            Log.i(TAG, "Microfone liberado; detected=$detected")

            // Deliberadamente só após AudioRecord.release().
            when {
                detected -> post { onDetected() }
                failure != null -> {
                    Log.e(TAG, "Falha na escuta", failure)
                    post {
                        onError(failure.message ?: "Erro desconhecido na captura de áudio")
                    }
                }
            }
        }
    }

    private fun findPreferredInputDevice(): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(AudioDeviceInfo::isSource)
        Log.i(TAG, "Entradas de áudio: ${inputs.joinToString { it.describe() }}")
        return inputs.firstOrNull(::isUsbInput)
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun isUsbInput(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun AudioDeviceInfo?.describe(): String {
        if (this == null) return "automático/indisponível"
        return "${productName.ifBlank { "sem nome" }}(id=$id,type=$type)"
    }

    private fun calculateAudioLevel(samples: ShortArray, count: Int): AudioLevel {
        var sumSquares = 0.0
        var peak = 0
        for (index in 0 until count) {
            val value = samples[index].toInt()
            val absolute = if (value == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(value)
            if (absolute > peak) peak = absolute
            sumSquares += value.toDouble() * value.toDouble()
        }

        val rms = sqrt(sumSquares / count)
        val dbFs = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else MIN_DB_FS
        return AudioLevel(rms, dbFs.coerceAtLeast(MIN_DB_FS), peak)
    }

    private fun extractText(json: String, key: String): String =
        try {
            JSONObject(json).optString(key).trim()
        } catch (error: Exception) {
            Log.w(TAG, "JSON inválido retornado pelo Vosk: $json", error)
            ""
        }

    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)

    private inline fun post(crossinline action: () -> Unit) {
        mainHandler.post { action() }
    }

    companion object {
        private const val TAG = "JetinnoWakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = 3_200
        private const val LEVEL_UPDATE_INTERVAL_MS = 500L
        private const val STOP_TIMEOUT_MS = 2_000L
        private const val MIN_DB_FS = -120.0
        private const val WAKE_WORD_GRAMMAR =
            "[\"ok destino\", \"ok jeitinho\", \"[unk]\"]"
    }
}
