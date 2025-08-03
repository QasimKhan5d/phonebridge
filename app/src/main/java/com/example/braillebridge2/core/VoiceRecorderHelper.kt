package com.example.braillebridge2.core

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "VoiceRecorderHelper"

/**
 * Helper class to manage voice recording for homework answers
 */
class VoiceRecorderHelper(private val context: Context) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    
    /**
     * Start recording voice answer
     */
    fun startRecording(lessonIndex: Int, callback: (Boolean, String?) -> Unit) {
        try {
            // Create recordings directory
            val recordingsDir = File(context.getExternalFilesDir(null), "recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }
            
            // Create unique filename for this recording
            val timestamp = System.currentTimeMillis()
            val fileName = "lesson_${lessonIndex}_answer_${timestamp}.m4a"
            currentRecordingFile = File(recordingsDir, fileName)
            
            // Setup MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentRecordingFile?.absolutePath)
                
                try {
                    prepare()
                    start()
                    isRecording = true
                    Log.i(TAG, "Started recording to: ${currentRecordingFile?.absolutePath}")
                    callback(true, null)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to start recording: ${e.message}")
                    callback(false, e.message)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up recording: ${e.message}")
            callback(false, e.message)
        }
    }
    
    /**
     * Stop recording and return the file path
     */
    fun stopRecording(callback: (Boolean, File?, String?) -> Unit) {
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false
                
                val recordedFile = currentRecordingFile
                if (recordedFile?.exists() == true && recordedFile.length() > 0) {
                    Log.i(TAG, "Recording saved: ${recordedFile.absolutePath} (${recordedFile.length()} bytes)")
                    callback(true, recordedFile, null)
                } else {
                    Log.w(TAG, "Recording file is empty or doesn't exist")
                    callback(false, null, "Recording file is empty")
                }
            } else {
                callback(false, null, "No active recording")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
            cleanup()
            callback(false, null, e.message)
        }
    }
    
    /**
     * Cancel current recording and cleanup
     */
    fun cancelRecording() {
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false
                
                // Delete the incomplete recording file
                currentRecordingFile?.delete()
                currentRecordingFile = null
                
                Log.i(TAG, "Recording cancelled and file deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recording: ${e.message}")
            cleanup()
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        try {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            currentRecordingFile = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isCurrentlyRecording(): Boolean = isRecording
    
    /**
     * Release resources when done
     */
    fun release() {
        cleanup()
    }
}