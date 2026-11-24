package com.example.jetinnowakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseMatcherTest {
    @Test
    fun recognizesExpectedTranscriptions() {
        assertTrue(WakePhraseMatcher.matches("OK Jetinno"))
        assertTrue(WakePhraseMatcher.matches("ok jetino"))
        assertTrue(WakePhraseMatcher.matches("ruído antes, o que jetino agora"))
        assertTrue(WakePhraseMatcher.matches("OK Gétino"))
        assertTrue(WakePhraseMatcher.matches("ok jeitinho"))
        assertTrue(WakePhraseMatcher.matches("ok destino"))
    }

    @Test
    fun doesNotTriggerForUnrelatedSpeech() {
        assertFalse(WakePhraseMatcher.matches("quero comprar um snack"))
        assertFalse(WakePhraseMatcher.matches("jetino"))
        assertFalse(WakePhraseMatcher.matches("ok"))
    }
}
