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
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioManager

private const val TAG = "SpeechHelper"

/**
 * Helper class to manage Speech-to-Text functionality
 * Extracted from ChatUtils.kt for reuse
 */
object SpeechHelper {
    
    /**
     * Starts speech recognition with callbacks
     */
    fun stopSpeechRecognition(recognizer: SpeechRecognizer?) {
        recognizer?.let {
            try {
                it.stopListening()
                it.cancel()
            } finally {
                it.destroy()
            }
        }
    }

    fun startSpeechRecognition(
        context: Context,
        language: Language = Language.ENGLISH,
        onResult: (String) -> Unit,
        onError: () -> Unit,
        onStart: (SpeechRecognizer) -> Unit
    ) {
        val startRecognition: () -> Unit = {
            // Check permissions first
            when {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                    Log.e(TAG, "RECORD_AUDIO permission not granted")
                    onError()
                }
                !SpeechRecognizer.isRecognitionAvailable(context) -> {
                    Log.e(TAG, "Speech recognition not available")
                    onError()
                }
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> {
                    Log.e(TAG, "Device does not have microphone")
                    onError()
                }
                else -> {
                    // Ensure audio focus and proper audio routing
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    
                    Log.d(TAG, "Audio mode: ${audioManager.mode}, Is music active: ${audioManager.isMusicActive}")
                    
                    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "✅ Ready for speech - microphone is now active")
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🎤 Beginning of speech - detected user speaking!")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Voice level changed - no logging to reduce noise
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech input detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error ($error)"
                }
                Log.e(TAG, "Speech recognition error: $error - $errorMessage")
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
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            // Enable secure recognition for better performance
            putExtra(RecognizerIntent.EXTRA_SECURE, false)
        }
        
        Log.d(TAG, "Starting STT with locale: ${locale} for language: $language")
        
        // Add a delay to ensure audio system is ready after TTS and request audio focus
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Request audio focus for recording
                val audioFocusRequest = audioManager.requestAudioFocus(
                    { focusChange ->
                        Log.d(TAG, "Audio focus change: $focusChange")
                    },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                
                if (audioFocusRequest == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.d(TAG, "Audio focus granted, starting speech recognition")
                    speechRecognizer.startListening(intent)
                    onStart(speechRecognizer)
                    Log.d(TAG, "Speech recognition started successfully")
                } else {
                    Log.e(TAG, "Failed to get audio focus for speech recognition")
                    onError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition: ${e.message}")
                e.printStackTrace()
                onError()
            }
        }, 500) // Longer delay to ensure TTS has fully released audio resources
                } // end else block
            } // end when
        } // end startRecognition lambda

        // Execute on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startRecognition()
        } else {
            android.os.Handler(Looper.getMainLooper()).post { startRecognition() }
        }
    }
}