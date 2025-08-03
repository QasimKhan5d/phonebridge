package com.example.braillebridge2.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "PhotoCaptureHelper"

/**
 * Helper class to manage photo capture for homework answers
 */
class PhotoCaptureHelper(private val context: Context) {
    
    private var currentPhotoFile: File? = null
    private var currentPhotoUri: Uri? = null
    
    /**
     * Create a camera intent for capturing homework answer photos
     */
    fun createCameraIntent(lessonIndex: Int): Intent? {
        return try {
            // Create photos directory
            val photosDir = File(context.getExternalFilesDir(null), "photos")
            if (!photosDir.exists()) {
                photosDir.mkdirs()
            }
            
            // Create unique filename for this photo
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "lesson_${lessonIndex}_answer_${timestamp}.jpg"
            currentPhotoFile = File(photosDir, fileName)
            
            // Create file provider URI
            currentPhotoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                currentPhotoFile!!
            )
            
            // Create camera intent
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
            }
            
            Log.i(TAG, "Created camera intent for: ${currentPhotoFile?.absolutePath}")
            cameraIntent
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera intent: ${e.message}")
            null
        }
    }
    
    /**
     * Handle the result from camera capture
     */
    fun handleCameraResult(resultCode: Int, callback: (Boolean, File?, String?) -> Unit) {
        try {
            if (resultCode == android.app.Activity.RESULT_OK) {
                val photoFile = currentPhotoFile
                if (photoFile?.exists() == true && photoFile.length() > 0) {
                    Log.i(TAG, "Photo captured: ${photoFile.absolutePath} (${photoFile.length()} bytes)")
                    callback(true, photoFile, null)
                } else {
                    Log.w(TAG, "Photo file is empty or doesn't exist")
                    callback(false, null, "Photo capture failed - file is empty")
                }
            } else {
                Log.w(TAG, "Camera capture cancelled by user")
                callback(false, null, "Photo capture cancelled")
                // Clean up the empty file if it was created
                currentPhotoFile?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling camera result: ${e.message}")
            callback(false, null, e.message)
        } finally {
            // Reset for next capture
            currentPhotoFile = null
            currentPhotoUri = null
        }
    }
    
    /**
     * Cancel current photo capture and cleanup
     */
    fun cancelCapture() {
        try {
            currentPhotoFile?.delete()
            currentPhotoFile = null
            currentPhotoUri = null
            Log.i(TAG, "Photo capture cancelled and file cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling photo capture: ${e.message}")
        }
    }
    
    /**
     * Get the current photo file being captured
     */
    fun getCurrentPhotoFile(): File? = currentPhotoFile
}