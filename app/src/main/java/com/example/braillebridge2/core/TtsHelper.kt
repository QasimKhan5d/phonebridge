package com.example.braillebridge2.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "TtsHelper"

/**
 * Helper class to manage Text-to-Speech functionality
 */
class TtsHelper(
    private val context: Context,
    private val onTtsReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    
    val isReady: Boolean get() = isTtsInitialized
    
    init {
        textToSpeech = TextToSpeech(context, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            } else {
                isTtsInitialized = true
                Log.i(TAG, "TextToSpeech initialized successfully")
                onTtsReady()
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed")
        }
    }
    
    /**
     * Speaks the provided text
     */
    fun speak(text: String) {
        if (isTtsInitialized) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.i(TAG, "Speaking: ${text.take(50)}...")
        }
    }
    
    /**
     * Sets the language for TTS
     */
    fun setLanguage(language: Language) {
        val locale = when (language) {
            Language.ENGLISH -> Locale.US
            Language.URDU -> Locale("ur", "PK") // Urdu (Pakistan)
        }
        
        textToSpeech?.setLanguage(locale)
    }
    
    /**
     * Stops TTS and releases resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsInitialized = false
    }
}