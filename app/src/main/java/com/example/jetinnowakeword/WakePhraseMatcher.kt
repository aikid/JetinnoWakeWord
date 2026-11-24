package com.example.jetinnowakeword

import java.text.Normalizer
import java.util.Locale

object WakePhraseMatcher {
    private val acceptedPhrases = listOf(
        "ok jetinno",
        "ok jetino",
        "o que jetino",
        "oque jetino",
        "ok getino",
        // Aproximações observadas no vosk-model-small-pt-0.3, cujo léxico
        // não contém a marca "Jetinno".
        "ok jeitinho",
        "ok destino"
    )

    fun matches(text: String): Boolean {
        val normalized = Normalizer
            .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return acceptedPhrases.any(normalized::contains)
    }
}
