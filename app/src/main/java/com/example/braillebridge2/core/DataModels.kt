package com.example.braillebridge2.core

import java.io.File

/**
 * Represents a single lesson item from the lesson pack
 */
data class LessonItem(
    val index: Int,
    val diagram: File,          // XXX.png
    val question: String,
    val audioEn: File,          // audio_en.wav
    val scriptEn: String,
    val scriptUr: String,
    val brailleEnSvg: File,     // braille_en.svg
    val brailleUrSvg: File,     // braille_ur.svg
    val diagramJson: File       // diagram.json
) {
    fun getCurrentBrailleSvg(language: Language): File = when (language) {
        Language.ENGLISH -> brailleEnSvg
        Language.URDU -> brailleUrSvg
    }
    
    fun getCurrentScript(language: Language): String = when (language) {
        Language.ENGLISH -> scriptEn
        Language.URDU -> scriptUr
    }
}

/**
 * Contains all lesson items in the homework pack
 */
data class LessonPack(
    val items: List<LessonItem>
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val size: Int get() = items.size
    
    fun getItem(index: Int): LessonItem? = items.getOrNull(index)
}

/**
 * Language options for the app
 */
enum class Language {
    ENGLISH, URDU
}

/**
 * Possible notification types on the home screen
 */
enum class NotificationType {
    NONE, HOMEWORK, FEEDBACK, BOTH
}

/**
 * Modes during homework interaction
 */
enum class HomeworkMode {
    VIEWING,           // Default viewing state
    RECORDING_VOICE,   // Recording voice answer
    RECORDING_PHOTO,   // Taking photo answer
    AWAITING_COMMAND   // Listening for voice command
}

/**
 * Types of gestures supported in homework screen
 */
enum class GestureType {
    TAP,               // Single tap - answer by voice
    DOUBLE_TAP,        // Double tap - answer by photo
    LONG_PRESS         // Hold and release - voice command
}

/**
 * Main application state machine
 */
sealed interface AppState {
    data object Loading : AppState
    
    data class Home(
        val notification: NotificationType = NotificationType.NONE
    ) : AppState
    
    data class Homework(
        val pack: LessonPack,
        val currentIndex: Int = 0,
        val language: Language = Language.ENGLISH,
        val mode: HomeworkMode = HomeworkMode.VIEWING
    ) : AppState {
        val currentItem: LessonItem? get() = pack.getItem(currentIndex)
        val isLastItem: Boolean get() = currentIndex >= pack.size - 1
        val hasNextItem: Boolean get() = currentIndex < pack.size - 1
    }
}