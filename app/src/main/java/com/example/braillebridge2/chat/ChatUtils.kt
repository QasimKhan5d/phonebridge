package com.example.braillebridge2.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.*

private const val TAG = "ChatUtils"

fun startSpeechRecognition(
    context: Context,
    onResult: (String) -> Unit,
    onError: () -> Unit,
    onStart: (SpeechRecognizer) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        Log.e(TAG, "Speech recognition not available")
        onError()
        return
    }
    
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
    
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    
    try {
        speechRecognizer.startListening(intent)
        onStart(speechRecognizer)
    } catch (e: Exception) {
        Log.e(TAG, "Error starting speech recognition: ${e.message}")
        onError()
    }
}

suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                
                // Check if we need to rotate the image based on EXIF data
                val rotatedBitmap = rotateImageIfRequired(context, uri, bitmap)
                
                // Scale down if the image is too large
                val maxSize = 1024
                if (rotatedBitmap.width > maxSize || rotatedBitmap.height > maxSize) {
                    val ratio = minOf(
                        maxSize.toFloat() / rotatedBitmap.width,
                        maxSize.toFloat() / rotatedBitmap.height
                    )
                    val newWidth = (rotatedBitmap.width * ratio).toInt()
                    val newHeight = (rotatedBitmap.height * ratio).toInt()
                    
                    val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, newWidth, newHeight, true)
                    if (scaledBitmap != rotatedBitmap) {
                        rotatedBitmap.recycle()
                    }
                    scaledBitmap
                } else {
                    rotatedBitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: ${e.message}")
            null
        }
    }
}

private fun rotateImageIfRequired(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        inputStream?.use { stream ->
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        } ?: bitmap
    } catch (e: Exception) {
        Log.e(TAG, "Error rotating image: ${e.message}")
        bitmap
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply {
        postRotate(degrees)
    }
    
    val rotatedBitmap = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
    
    if (rotatedBitmap != bitmap) {
        bitmap.recycle()
    }
    
    return rotatedBitmap
}