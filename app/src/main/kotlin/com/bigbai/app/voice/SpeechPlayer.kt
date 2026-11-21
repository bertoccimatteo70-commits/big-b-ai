package com.bigbai.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Text-to-speech backed by the engine installed on the device.
 *
 * ITAGPT does not bundle a speech model: Android's TTS service is the local,
 * free, already-installed engine, and it works offline when the device has the
 * language data. The class keeps initialization asynchronous because
 * [TextToSpeech] connects to a system service; a sentence requested before the
 * engine is ready is queued once instead of being dropped.
 */
class SpeechPlayer(context: Context) {

    private var ready = false
    private var pending: Pair<String, String>? = null

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        val queued = pending
        pending = null
        if (ready && queued != null) speakNow(queued.first, queued.second)
    }

    /** @return true when the request was accepted (now or once the engine is ready). */
    fun speak(text: String, languageTag: String): Boolean {
        if (text.isBlank()) return false
        if (!ready) {
            pending = text to languageTag
            return true
        }
        return speakNow(text, languageTag)
    }

    private fun speakNow(text: String, languageTag: String): Boolean {
        val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrDefault(Locale.ITALIAN)
        val availability = engine.isLanguageAvailable(locale)
        engine.language = if (
            availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Locale.getDefault()
        } else {
            locale
        }
        engine.stop()
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "itagpt-${System.currentTimeMillis()}") ==
            TextToSpeech.SUCCESS
    }

    fun stop() {
        runCatching { engine.stop() }
    }

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }
}
