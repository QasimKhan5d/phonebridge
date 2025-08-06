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
     * Create a camera and gallery chooser intent for capturing homework answer photos
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
            
            // Create gallery intent
            val galleryIntent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            
            // Create chooser with both camera and gallery options
            val chooserIntent = Intent.createChooser(galleryIntent, "Select homework answer photo").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
            
            Log.i(TAG, "Created camera/gallery chooser intent for: ${currentPhotoFile?.absolutePath}")
            chooserIntent
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera/gallery intent: ${e.message}")
            null
        }
    }
    
    /**
     * Handle the result from camera capture or gallery selection
     */
    fun handleCameraResult(resultCode: Int, data: Intent?, callback: (Boolean, File?, String?) -> Unit) {
        try {
            if (resultCode == android.app.Activity.RESULT_OK) {
                // Check if this was a gallery selection (has data URI) or camera capture
                val selectedUri = data?.data
                
                if (selectedUri != null) {
                    // This was a gallery selection - copy file to our photos directory
                    Log.i(TAG, "Gallery photo selected: $selectedUri")
                    copyGalleryImageToPhotoFile(selectedUri, callback)
                } else {
                    // This was a camera capture - check our photo file
                    val photoFile = currentPhotoFile
                    if (photoFile?.exists() == true && photoFile.length() > 0) {
                        Log.i(TAG, "Photo captured: ${photoFile.absolutePath} (${photoFile.length()} bytes)")
                        callback(true, photoFile, null)
                    } else {
                        Log.w(TAG, "Photo file is empty or doesn't exist")
                        callback(false, null, "Photo capture failed - file is empty")
                    }
                }
            } else {
                Log.w(TAG, "Photo selection cancelled by user")
                callback(false, null, "Photo selection cancelled")
                // Clean up the empty file if it was created
                currentPhotoFile?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling photo result: ${e.message}")
            callback(false, null, e.message)
        } finally {
            // Reset for next capture (only if camera capture, gallery doesn't use currentPhotoFile)
            if (data?.data == null) {
                currentPhotoFile = null
                currentPhotoUri = null
            }
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
     * Copy selected gallery image to our photos directory
     */
    private fun copyGalleryImageToPhotoFile(selectedUri: Uri, callback: (Boolean, File?, String?) -> Unit) {
        try {
            val photoFile = currentPhotoFile ?: return callback(false, null, "No photo file prepared")
            
            context.contentResolver.openInputStream(selectedUri)?.use { inputStream ->
                photoFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            if (photoFile.exists() && photoFile.length() > 0) {
                Log.i(TAG, "Gallery image copied: ${photoFile.absolutePath} (${photoFile.length()} bytes)")
                callback(true, photoFile, null)
            } else {
                Log.e(TAG, "Failed to copy gallery image")
                callback(false, null, "Failed to copy selected image")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error copying gallery image: ${e.message}")
            callback(false, null, "Error copying selected image: ${e.message}")
        } finally {
            // Reset after gallery selection
            currentPhotoFile = null
            currentPhotoUri = null
        }
    }
    
    /**
     * Get the current photo file being captured
     */
    fun getCurrentPhotoFile(): File? = currentPhotoFile
}