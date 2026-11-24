package com.example.jetinnowakeword

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.vosk.Model

object VoskVocabulary {
    private const val TAG = "JetinnoWakeWord"

    private interface NativeVosk : Library {
        fun vosk_model_find_word(model: Pointer, word: String): Int
    }

    private val nativeVosk: NativeVosk by lazy {
        Native.load("vosk", NativeVosk::class.java)
    }

    fun inspect(model: Model, words: List<String>): Map<String, Boolean> =
        words.associateWith { word ->
            try {
                nativeVosk.vosk_model_find_word(model.pointer, word) >= 0
            } catch (error: Throwable) {
                Log.e(TAG, "Não foi possível consultar '$word' no vocabulário", error)
                false
            }
        }.also { report ->
            Log.i(TAG, "Vocabulário do modelo: $report")
        }
}
