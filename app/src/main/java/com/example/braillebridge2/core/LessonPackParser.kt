package com.example.braillebridge2.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "LessonPackParser"

/**
 * Utility class to parse lesson packs from assets or external storage
 */
object LessonPackParser {
    
    /**
     * Scan for lesson packs from assets and copy them to external storage for easier access
     */
    suspend fun scanAndCopyLessonPacks(context: Context): LessonPack? {
        return try {
            val externalDir = File(context.getExternalFilesDir(null), "lesson_packs")
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            
            // Copy assets to external storage if not already copied
            copyAssetsToExternalStorage(context, externalDir)
            
            // Scan the external directory for lesson items
            parseLessonPackFromDirectory(externalDir)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning lesson packs: ${e.message}")
            null
        }
    }
    
    /**
     * Copy lesson pack assets to external storage for easier file access
     */
    private fun copyAssetsToExternalStorage(context: Context, externalDir: File) {
        try {
            val assetManager = context.assets
            val lessonItems = assetManager.list("lesson_packs") ?: return
            
            for (itemFolder in lessonItems) {
                val itemDir = File(externalDir, itemFolder)
                if (!itemDir.exists()) {
                    itemDir.mkdirs()
                    
                    // Copy all files from this item folder
                    val itemFiles = assetManager.list("lesson_packs/$itemFolder") ?: continue
                    for (fileName in itemFiles) {
                        copyAssetFile(
                            context,
                            "lesson_packs/$itemFolder/$fileName",
                            File(itemDir, fileName)
                        )
                    }
                    Log.i(TAG, "Copied lesson item: $itemFolder")
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
     * Parse lesson pack from a directory containing item_N folders
     */
    private fun parseLessonPackFromDirectory(lessonPackDir: File): LessonPack? {
        return try {
            val items = mutableListOf<LessonItem>()
            
            // Find all item_N directories
            val itemDirs = lessonPackDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("item_")
            }?.sortedBy { 
                // Extract number from item_N folder name
                it.name.substringAfter("item_").toIntOrNull() ?: 0
            } ?: return null
            
            for ((index, itemDir) in itemDirs.withIndex()) {
                val lessonItem = parseItemFromDirectory(itemDir, index + 1)
                if (lessonItem != null) {
                    items.add(lessonItem)
                    Log.i(TAG, "Parsed lesson item ${index + 1}: ${lessonItem.question}")
                }
            }
            
            if (items.isNotEmpty()) {
                LessonPack(items)
            } else {
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing lesson pack: ${e.message}")
            null
        }
    }
    
    /**
     * Parse a single lesson item from its directory
     */
    private fun parseItemFromDirectory(itemDir: File, index: Int): LessonItem? {
        return try {
            // Required files
            val questionFile = File(itemDir, "question.txt")
            val scriptEnFile = File(itemDir, "script_en.txt")
            val scriptUrFile = File(itemDir, "script_ur.txt")
            val diagramJsonFile = File(itemDir, "diagram.json")
            
            // Find the diagram image (look for .png files)
            val diagramFile = itemDir.listFiles { file ->
                file.extension.lowercase() in listOf("png", "jpg", "jpeg")
            }?.firstOrNull()
            
            // Required SVG files
            val brailleEnSvgFile = File(itemDir, "braille_en.svg")
            val brailleUrSvgFile = File(itemDir, "braille_ur.svg")
            
            // Audio file
            val audioEnFile = File(itemDir, "audio_en.wav")
            
            // Check required files exist
            if (!questionFile.exists() || !scriptEnFile.exists() || !scriptUrFile.exists() || 
                !diagramJsonFile.exists() || diagramFile == null || !diagramFile.exists()) {
                Log.w(TAG, "Missing required files in $itemDir")
                return null
            }
            
            // Read text content
            val question = questionFile.readText().trim()
            val scriptEn = scriptEnFile.readText().trim()
            val scriptUr = scriptUrFile.readText().trim()
            
            LessonItem(
                index = index,
                diagram = diagramFile,
                question = question,
                audioEn = audioEnFile,
                scriptEn = scriptEn,
                scriptUr = scriptUr,
                brailleEnSvg = brailleEnSvgFile,
                brailleUrSvg = brailleUrSvgFile,
                diagramJson = diagramJsonFile
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing item from $itemDir: ${e.message}")
            null
        }
    }
}