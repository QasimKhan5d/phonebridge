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
    val diagramJson: File,      // diagram.json
    var questionUrdu: String? = null // Cached Urdu translation
) {
    fun getCurrentBrailleSvg(language: Language): File = when (language) {
        Language.ENGLISH -> brailleEnSvg
        Language.URDU -> brailleUrSvg
    }
    
    fun getCurrentScript(language: Language): String = when (language) {
        Language.ENGLISH -> scriptEn
        Language.URDU -> scriptUr
    }
    
    fun getCurrentQuestion(language: Language): String = when (language) {
        Language.ENGLISH -> question
        Language.URDU -> questionUrdu ?: question // Fall back to English if no translation
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
 * Represents a single feedback item from teacher
 */
data class FeedbackItem(
    val index: Int,                    // The X from submission_X_feedback
    val feedbackText: String,          // Contents of feedback_submission_X.txt
    val brailleSvg: File?,             // Optional braille SVG with corrections
    var feedbackUrdu: String? = null,   // Cached Urdu translation
    var conversationInitialized: Boolean = false,  // whether base context added to Gemma session
    var spokenOnce: Boolean = false            // whether feedback spoken initially
) {
    fun getCurrentFeedback(language: Language): String = when (language) {
        Language.ENGLISH -> feedbackText
        Language.URDU -> feedbackUrdu ?: feedbackText // Fall back to English if no translation
    }
}

/**
 * Contains all feedback items 
 */
data class FeedbackPack(
    val items: List<FeedbackItem>
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val size: Int get() = items.size
    
    fun getItem(index: Int): FeedbackItem? = items.getOrNull(index)
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
    AWAITING_COMMAND,  // Listening for voice commands
    ASKING_QUESTION,   // Recording question for Gemma
    GEMMA_RESPONDING   // Gemma is generating response
}

/**
 * Modes during feedback interaction
 */
enum class FeedbackMode {
    VIEWING,           // Default viewing state
    AWAITING_COMMAND,  // Listening for voice commands
    ASKING_QUESTION,   // Recording question for Gemma
    GEMMA_RESPONDING,  // Gemma is generating response
    TRANSLATING        // Translating feedback to another language
}

/**
 * Modes during spatial image understanding interaction
 */
enum class SpatialMode {
    AWAITING_PHOTO,    // Waiting for user to take a photo
    PROCESSING_PHOTO,  // Camera is open/processing photo
    GEMMA_RESPONDING,  // Gemma is generating spatial description
    AWAITING_COMMAND   // Listening for voice commands (repeat/switch/new photo)
}

/**
 * Spatial conversation message types for chat-like interface
 */
sealed class SpatialMessage {
    data class ImageMessage(
        val file: File,
        val timestamp: Long = System.currentTimeMillis()
    ) : SpatialMessage()
    
    data class ResponseMessage(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isComplete: Boolean = true
    ) : SpatialMessage()
    
    data class StatusMessage(
        val status: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SpatialMessage()
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
    
    data class Feedback(
        val pack: FeedbackPack,
        val currentIndex: Int = 0,
        val language: Language = Language.ENGLISH,
        val mode: FeedbackMode = FeedbackMode.VIEWING
    ) : AppState {
        val currentItem: FeedbackItem? get() = pack.getItem(currentIndex)
        val isLastItem: Boolean get() = currentIndex >= pack.size - 1
        val hasNextItem: Boolean get() = currentIndex < pack.size - 1
    }
    
    data class Spatial(
        val images: MutableList<File> = mutableListOf(),
        val language: Language = Language.ENGLISH,
        val mode: SpatialMode = SpatialMode.AWAITING_PHOTO,
        var conversationInitialized: Boolean = false,
        val conversationHistory: MutableList<SpatialMessage> = mutableListOf(),
        val currentStreamingText: String = "",
        val isStreaming: Boolean = false
    ) : AppState {
        val hasImages: Boolean get() = images.isNotEmpty()
        val lastImage: File? get() = images.lastOrNull()
        val hasConversation: Boolean get() = conversationHistory.isNotEmpty()
    }
}