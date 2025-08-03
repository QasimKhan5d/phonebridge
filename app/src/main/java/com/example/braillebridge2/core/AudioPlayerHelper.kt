package com.example.braillebridge2.core

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

private const val TAG = "AudioPlayerHelper"

/**
 * Helper class to play lesson audio files
 */
class AudioPlayerHelper(private val context: Context) {
    
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    
    /**
     * Play audio file (e.g., lesson description audio)
     */
    fun playAudio(audioFile: File, onComplete: () -> Unit = {}, onError: (String) -> Unit = {}) {
        try {
            // Stop any current playback
            stopAudio()
            
            if (!audioFile.exists()) {
                Log.w(TAG, "Audio file does not exist: ${audioFile.absolutePath}")
                onError("Audio file not found")
                return
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    Log.i(TAG, "Audio playback completed")
                    this@AudioPlayerHelper.isPlaying = false
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    this@AudioPlayerHelper.isPlaying = false
                    onError("Audio playback error")
                    true
                }
                
                prepareAsync()
                setOnPreparedListener { player ->
                    Log.i(TAG, "Starting audio playback: ${audioFile.name}")
                    player.start()
                    this@AudioPlayerHelper.isPlaying = true
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
            onError(e.message ?: "Unknown audio error")
        }
    }
    
    /**
     * Stop current audio playback
     */
    fun stopAudio() {
        try {
            mediaPlayer?.let { player ->
                if (isPlaying) {
                    player.stop()
                    Log.i(TAG, "Audio playback stopped")
                }
                player.reset()
                player.release()
            }
            mediaPlayer = null
            isPlaying = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
    }
    
    /**
     * Check if currently playing audio
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying
    
    /**
     * Release resources
     */
    fun release() {
        stopAudio()
    }
}