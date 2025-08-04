package com.example.braillebridge2.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "FeedbackPackParser"

/**
 * Utility class to parse feedback packs from assets or external storage
 */
object FeedbackPackParser {
    
    /**
     * Scan for feedback packs from assets and copy them to external storage for easier access
     */
    suspend fun scanAndCopyFeedbackPacks(context: Context): FeedbackPack? {
        return try {
            val externalDir = File(context.getExternalFilesDir(null), "feedback_packs")
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            
            // Copy assets to external storage if not already copied
            copyAssetsToExternalStorage(context, externalDir)
            
            // Scan the external directory for feedback items
            parseFeedbackPackFromDirectory(externalDir)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning feedback packs: ${e.message}")
            null
        }
    }
    
    /**
     * Copy feedback pack assets to external storage for easier file access
     */
    private fun copyAssetsToExternalStorage(context: Context, externalDir: File) {
        try {
            val assetManager = context.assets
            val feedbackItems = assetManager.list("feedback_packs") ?: return
            
            for (itemFolder in feedbackItems) {
                val itemDir = File(externalDir, itemFolder)
                if (!itemDir.exists()) {
                    itemDir.mkdirs()
                    
                    // Copy all files from this feedback folder
                    val itemFiles = assetManager.list("feedback_packs/$itemFolder") ?: continue
                    for (fileName in itemFiles) {
                        copyAssetFile(
                            context,
                            "feedback_packs/$itemFolder/$fileName",
                            File(itemDir, fileName)
                        )
                    }
                    Log.i(TAG, "Copied feedback item: $itemFolder")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets: ${e.message}")
        }
    }
    
    /**
     * Copy a single asset file to external storage
     */
    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying asset file $assetPath: ${e.message}")
        }
    }
    
    /**
     * Parse feedback pack from a directory containing submission_X_feedback folders
     */
    private fun parseFeedbackPackFromDirectory(feedbackPackDir: File): FeedbackPack? {
        return try {
            val items = mutableListOf<FeedbackItem>()
            
            // Find all submission_X_feedback directories
            val itemDirs = feedbackPackDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("submission_\\d+_feedback"))
            }?.sortedBy { 
                // Extract number from submission_X_feedback folder name
                val match = Regex("submission_(\\d+)_feedback").find(it.name)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } ?: return null
            
            for (itemDir in itemDirs) {
                val feedbackItem = parseItemFromDirectory(itemDir)
                if (feedbackItem != null) {
                    items.add(feedbackItem)
                    Log.i(TAG, "Parsed feedback item ${feedbackItem.index}: ${feedbackItem.feedbackText.take(50)}...")
                }
            }
            
            if (items.isNotEmpty()) {
                FeedbackPack(items)
            } else {
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing feedback pack: ${e.message}")
            null
        }
    }
    
    /**
     * Parse a single feedback item from its directory
     */
    private fun parseItemFromDirectory(itemDir: File): FeedbackItem? {
        return try {
            // Extract index from folder name (submission_X_feedback)
            val match = Regex("submission_(\\d+)_feedback").find(itemDir.name)
            val index = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
            
            // Required file: feedback_submission_X.txt
            val feedbackFile = File(itemDir, "feedback_submission_$index.txt")
            if (!feedbackFile.exists()) {
                Log.w(TAG, "Missing feedback file in $itemDir")
                return null
            }
            
            // Optional file: braille SVG with corrections
            val brailleSvgFile = itemDir.listFiles { file ->
                file.extension.lowercase() == "svg" && file.name.contains("braille")
            }?.firstOrNull()
            
            // Read feedback text content
            val feedbackText = feedbackFile.readText().trim()
            
            FeedbackItem(
                index = index,
                feedbackText = feedbackText,
                brailleSvg = brailleSvgFile
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing feedback item from $itemDir: ${e.message}")
            null
        }
    }
}