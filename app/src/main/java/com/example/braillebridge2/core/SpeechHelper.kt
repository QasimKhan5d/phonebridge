package com.example.braillebridge2.core

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.os.Bundle
import java.util.Locale
import android.os.Looper
import android.os.Handler

private const val TAG = "SpeechHelper"

/**
 * Helper class to manage Speech-to-Text functionality
 * Extracted from ChatUtils.kt for reuse
 */
object SpeechHelper {
    
    /**
     * Starts speech recognition with callbacks
     */
    fun startSpeechRecognition(
        context: Context,
        language: Language = Language.ENGLISH,
        onResult: (String) -> Unit,
        onError: () -> Unit,
        onStart: (SpeechRecognizer) -> Unit
    ) {
        val startRecognition: () -> Unit = {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available")
                onError()
            } else {
                val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Voice level changed
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }
            
            override fun onError(error: Int) {
                Log.e(TAG, "Speech recognition error: $error")
                onError()
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    Log.d(TAG, "Speech recognition result: $recognizedText")
                    onResult(recognizedText)
                } else {
                    onError()
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                // Partial results received
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // Event received
            }
        })
        
        // Set locale based on the current language
        val locale = when (language) {
            Language.ENGLISH -> Locale("en", "US")
            Language.URDU -> Locale("ur", "PK")
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        
        Log.d(TAG, "Starting STT with locale: ${locale} for language: $language")
        
        try {
            speechRecognizer.startListening(intent)
            onStart(speechRecognizer)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}")
            onError()
        }
        } // end else block
    }

        // Execute on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startRecognition()
        } else {
            android.os.Handler(Looper.getMainLooper()).post { startRecognition() }
        }
    }
}