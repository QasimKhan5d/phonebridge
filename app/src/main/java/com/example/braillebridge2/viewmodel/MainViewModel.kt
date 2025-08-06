package com.example.braillebridge2.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import android.content.Intent
import com.example.braillebridge2.core.*
import com.example.braillebridge2.chat.LlmModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

private const val TAG = "MainViewModel"

/**
 * Main ViewModel that manages the application state machine
 */
class MainViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow<AppState>(AppState.Loading)
    val uiState = _uiState.asStateFlow()
    
    private var currentLessonPack: LessonPack? = null
    private var currentFeedbackPack: FeedbackPack? = null
    private var voiceRecorderHelper: VoiceRecorderHelper? = null
    private var photoCaptureHelper: PhotoCaptureHelper? = null
    private var audioPlayerHelper: AudioPlayerHelper? = null
    private var gemmaHelper: GemmaHelper? = null
    
    // Debouncing for recording gestures to prevent double-tap issues
    private var lastRecordingGestureTime = 0L
    private val recordingGestureDebounceMs = 1000L // 1 second debounce
    
    /**
     * Initialize the app by scanning for lesson packs and feedback
     */
    fun initialize(context: Context) {
        viewModelScope.launch {
            Log.i(TAG, "Initializing app...")
            _uiState.value = AppState.Loading
            
            try {
                // Initialize helpers
                voiceRecorderHelper = VoiceRecorderHelper(context)
                photoCaptureHelper = PhotoCaptureHelper(context)
                audioPlayerHelper = AudioPlayerHelper(context)
                        gemmaHelper = GemmaHelper()
                
                // Scan for lesson packs and feedback
                val notification = scanForContent(context)
                _uiState.value = AppState.Home(notification)
                Log.i(TAG, "Initialization complete. Notification: $notification")
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization: ${e.message}")
                _uiState.value = AppState.Home(NotificationType.NONE)
            }
        }
    }
    
    /**
     * Handle tap gesture on home screen
     */
    fun onHomeTap() {
        val currentState = _uiState.value
        if (currentState is AppState.Home) {
            when (currentState.notification) {
                NotificationType.HOMEWORK, NotificationType.BOTH -> {
                    currentLessonPack?.let { pack ->
                        _uiState.value = AppState.Homework(pack = pack)
                        Log.i(TAG, "Opening homework with ${pack.size} items")
                    }
                }
                else -> {
                    Log.w(TAG, "No homework available to open")
                }
            }
        }
    }
    
    /**
     * Handle double tap gesture on home screen
     */
    fun onHomeDoubleTap() {
        val currentState = _uiState.value
        if (currentState is AppState.Home) {
            when (currentState.notification) {
                NotificationType.FEEDBACK, NotificationType.BOTH -> {
                    currentFeedbackPack?.let { pack ->
                        _uiState.value = AppState.Feedback(pack = pack)
                        Log.i(TAG, "Opening feedback with ${pack.size} items")
                    }
                }
                else -> {
                    Log.w(TAG, "No feedback available to open")
                }
            }
        }
    }

    /**
     * Handle home long press - open image understanding mode
     */
    fun onHomeLongPress() {
        Log.i(TAG, "Home long press - opening spatial image understanding")
        _uiState.value = AppState.Spatial()
    }
    
    /**
     * Navigate to next homework item or return home if complete
     */
    fun moveToNextHomeworkItem(ttsHelper: TtsHelper, modelManager: LlmModelManager) {
        val currentState = _uiState.value
        if (currentState is AppState.Homework) {
            if (currentState.hasNextItem) {
                val newIndex = currentState.currentIndex + 1
                val newItem = currentState.pack.getItem(newIndex)
                
                if (newItem != null && currentState.language == Language.URDU) {
                    // In Urdu mode, check if translation exists
                    if (newItem.questionUrdu != null) {
                        // Translation exists, move instantly
                        _uiState.value = currentState.copy(
                            currentIndex = newIndex,
                            mode = HomeworkMode.VIEWING
                        )
                        Log.i(TAG, "Moved to item ${newIndex + 1} (Urdu translation available)")
                    } else {
                        // Show translating spinner and translate
                        _uiState.value = currentState.copy(
                            currentIndex = newIndex,
                            mode = HomeworkMode.TRANSLATING
                        )
                        Log.i(TAG, "Moved to item ${newIndex + 1}, starting translation")
                        
                        val translatingMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.TRANSLATING_FEEDBACK, Language.URDU)
                        ttsHelper.speak(translatingMessage)
                        
                        // Start translation
                        translateQuestionToUrdu(modelManager, newItem, ttsHelper, speakTranslatedQuestion = false)
                    }
                } else {
                    // English mode or no item, move normally
                    _uiState.value = currentState.copy(
                        currentIndex = newIndex,
                        mode = HomeworkMode.VIEWING
                    )
                    Log.i(TAG, "Moved to item ${newIndex + 1}")
                }
            } else {
                // Return to home
                viewModelScope.launch {
                    val notification = scanForContent(null) // Re-scan for new content
                    _uiState.value = AppState.Home(notification)
                    Log.i(TAG, "Homework complete, returned to home")
                }
            }
        }
    }
    
    /**
     * Return to home screen from any state
     */
    fun returnToHome(context: Context) {
        viewModelScope.launch {
            val notification = scanForContent(context)
            _uiState.value = AppState.Home(notification)
            Log.i(TAG, "Returned to home screen")
        }
    }
    
    /**
     * Handle homework gesture interactions
     */
    fun onHomeworkGesture(gestureType: GestureType, ttsHelper: TtsHelper? = null) {
        val currentState = _uiState.value
        if (currentState !is AppState.Homework) return
        
        when (currentState.mode) {
            HomeworkMode.VIEWING -> {
                when (gestureType) {
                    GestureType.TAP -> startVoiceAnswer(currentState)
                    GestureType.DOUBLE_TAP -> startPhotoAnswer(currentState)
                    GestureType.LONG_PRESS -> startVoiceCommand(currentState)
                }
            }
            HomeworkMode.RECORDING_VOICE -> {
                if (gestureType == GestureType.TAP) {
                    // Add debouncing to prevent double-tap issues
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastRecordingGestureTime > recordingGestureDebounceMs) {
                        lastRecordingGestureTime = currentTime
                        Log.d(TAG, "Processing tap to stop recording")
                        stopVoiceAnswer(currentState, ttsHelper)
                    } else {
                        Log.d(TAG, "Ignoring rapid tap gesture to prevent double-stop (${currentTime - lastRecordingGestureTime}ms since last)")
                    }
                }
            }
            HomeworkMode.ASKING_QUESTION -> {
                if (gestureType == GestureType.TAP) {
                    // Speech recognition stopped by UI layer, just return to viewing mode
                    _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                }
            }
            HomeworkMode.GEMMA_RESPONDING -> {
                // Allow cancellation of Gemma response
                cancelGemmaResponse()
            }
            HomeworkMode.TRANSLATING -> {
                // Cannot interact during translation
            }
            HomeworkMode.LISTENING_AUDIO -> {
                if (gestureType == GestureType.TAP) {
                    // Stop audio playback and return to viewing mode
                    Log.d(TAG, "Stopping audio playback due to user tap")
                    audioPlayerHelper?.stopAudio()
                    _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                }
            }
            else -> {
                // Other modes handled elsewhere
            }
        }
    }
    
    /**
     * Start voice answer recording
     */
    private fun startVoiceAnswer(currentState: AppState.Homework) {
        val currentItem = currentState.currentItem
        if (currentItem == null || voiceRecorderHelper == null) {
            Log.w(TAG, "Cannot start voice recording - missing item or helper")
            return
        }
        
        // First update UI to show recording mode (which will trigger "Recording started" TTS)
        _uiState.value = currentState.copy(mode = HomeworkMode.RECORDING_VOICE)
        
        // Wait for TTS to complete before starting recording - no arbitrary delay!
        Log.d(TAG, "Recording mode set, waiting for TTS completion signal from UI")
    }
    
    /**
     * Called by UI when "Recording started" TTS completes - starts actual recording
     */
    fun onRecordingTtsComplete() {
        val currentState = _uiState.value
        if (currentState !is AppState.Homework || currentState.mode != HomeworkMode.RECORDING_VOICE) {
            Log.w(TAG, "TTS completion called but not in RECORDING_VOICE mode: ${currentState}")
            return
        }
        
        val currentItem = currentState.currentItem
        if (currentItem == null || voiceRecorderHelper == null) {
            Log.w(TAG, "Cannot start voice recording - missing item or helper")
            _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
            return
        }
        
        Log.d(TAG, "TTS completed, starting actual voice recording immediately")
        voiceRecorderHelper?.startRecording(currentItem.index) { success, error ->
            if (success) {
                Log.i(TAG, "Started voice answer recording for lesson ${currentItem.index}")
            } else {
                Log.e(TAG, "Failed to start voice recording: $error")
                // Return to viewing mode if recording failed
                val latestState = _uiState.value
                if (latestState is AppState.Homework) {
                    _uiState.value = latestState.copy(mode = HomeworkMode.VIEWING)
                }
            }
        }
    }
    
    /**
     * Stop voice answer recording
     */
    private fun stopVoiceAnswer(currentState: AppState.Homework, ttsHelper: TtsHelper? = null) {
        // Check if we're still in recording mode before attempting to stop
        val state = _uiState.value
        if (state !is AppState.Homework || state.mode != HomeworkMode.RECORDING_VOICE) {
            Log.d(TAG, "Not in recording mode, ignoring stop voice answer request")
            return
        }
        
        voiceRecorderHelper?.stopRecording { success, file, error ->
            if (success && file != null) {
                Log.i(TAG, "Voice answer saved: ${file.absolutePath}")
                // TODO: Store the answer file path in the lesson state or database
                
                Log.d(TAG, "Starting audio playback for confirmation")
                // Play back the recording for confirmation
                audioPlayerHelper?.playAudio(file,
                    onComplete = {
                        Log.d(TAG, "Audio playback completed callback triggered, adding small delay before returning to viewing mode")
                        // Add a small delay to prevent immediate TTS overlap 
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val finalState = _uiState.value
                            if (finalState is AppState.Homework) {
                                Log.d(TAG, "Transitioning from ${finalState.mode} to VIEWING mode after delay")
                                _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                            } else {
                                Log.w(TAG, "Unexpected state during playback completion: $finalState")
                            }
                        }, 200) // Small delay to prevent TTS overlap
                    },
                    onError = { errorMsg ->
                        Log.e(TAG, "Audio playback failed: $errorMsg")
                        // Even if playback fails, return to viewing mode
                        val finalState = _uiState.value
                        if (finalState is AppState.Homework) {
                            _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                        }
                    }
                )
            } else {
                Log.e(TAG, "Failed to save voice recording: $error")
                val latestState = _uiState.value
                if (latestState is AppState.Homework) {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.RECORDING_FAILED, latestState.language)
                    ttsHelper?.speak(message)
                    // Return to viewing mode immediately if recording failed
                    _uiState.value = latestState.copy(mode = HomeworkMode.VIEWING)
                } else {
                    ttsHelper?.speak("Recording failed")
                }
            }
        }
    }
    
    /**
     * Start photo answer capture
     */
    private fun startPhotoAnswer(currentState: AppState.Homework) {
        val currentItem = currentState.currentItem
        if (currentItem == null || photoCaptureHelper == null) {
            Log.w(TAG, "Cannot start photo capture - missing item or helper")
            return
        }
        
        _uiState.value = currentState.copy(mode = HomeworkMode.RECORDING_PHOTO)
        Log.i(TAG, "Started photo answer capture for lesson ${currentItem.index}")
        // Note: The actual camera intent will be handled in the UI layer
    }
    
    /**
     * Get camera intent for photo capture
     */
    fun getCameraIntent(): android.content.Intent? {
        val currentState = _uiState.value
        if (currentState is AppState.Homework) {
            val currentItem = currentState.currentItem
            return if (currentItem != null && photoCaptureHelper != null) {
                photoCaptureHelper?.createCameraIntent(currentItem.index)
            } else null
        }
        return null
    }
    
    /**
     * Handle photo capture result
     */
    fun handlePhotoCaptureResult(resultCode: Int, data: android.content.Intent? = null, modelManager: LlmModelManager? = null, ttsHelper: TtsHelper? = null) {
        val currentState = _uiState.value
        
        when (currentState) {
            is AppState.Homework -> {
                photoCaptureHelper?.handleCameraResult(resultCode, data) { success, file, error ->
                    if (success && file != null) {
                        Log.i(TAG, "Photo answer saved: ${file.absolutePath}")
                        // TODO: Store the answer file path in the lesson state or database
                        val currentState = _uiState.value
                        if (currentState is AppState.Homework) {
                            val message = LocalizedStrings.getString(LocalizedStrings.StringKey.PHOTO_SAVED, currentState.language)
                            ttsHelper?.speak(message) {
                                // Return to viewing mode after TTS completes
                                val finalState = _uiState.value
                                if (finalState is AppState.Homework) {
                                    _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                                }
                            }
                        } else {
                            ttsHelper?.speak("Photo submitted") {
                                // Return to viewing mode after TTS completes
                                val finalState = _uiState.value
                                if (finalState is AppState.Homework) {
                                    _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to save photo: $error")
                        // Return to viewing mode immediately on error
                        _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                    }
                }
            }
            
            is AppState.Spatial -> {
                photoCaptureHelper?.handleCameraResult(resultCode, data) { success, file, error ->
                    if (success && file != null && modelManager != null && ttsHelper != null) {
                        Log.i(TAG, "Spatial photo captured: ${file.absolutePath}")
                        // Process spatial photo with Gemma
                        onSpatialPhotoReady(file, modelManager, ttsHelper)
                    } else {
                        Log.e(TAG, "Failed to capture spatial photo: $error")
                        // Return to awaiting photo state on failure
                        _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_PHOTO)
                        ttsHelper?.speak("Photo capture failed. Please try again.")
                    }
                }
            }
            
            else -> {
                Log.w(TAG, "Photo capture result received in unexpected state: ${currentState::class.simpleName}")
            }
        }
    }
    
    /**
     * Start voice command listening
     */
    private fun startVoiceCommand(currentState: AppState.Homework) {
        _uiState.value = currentState.copy(mode = HomeworkMode.AWAITING_COMMAND)
        Log.i(TAG, "Started voice command listening")
        // Note: The actual speech recognition will be handled in the UI layer
    }
    

    

    
    /**
     * Handle voice command result
     */
    fun handleVoiceCommand(command: String, ttsHelper: TtsHelper, modelManager: LlmModelManager? = null) {
        when (command.lowercase().trim()) {
            "listen" -> {
                // Get the current state fresh for this command
                val currentState = _uiState.value
                if (currentState !is AppState.Homework) return
                val currentItem = currentState.currentItem
                
                Log.i(TAG, "Listen command - Current language: ${currentState.language}")
                if (currentItem != null) {
                    // Set to LISTENING_AUDIO mode to prevent TTS announcements
                    _uiState.value = currentState.copy(mode = HomeworkMode.LISTENING_AUDIO)
                    
                    if (currentState.language == Language.ENGLISH) {
                        // Play English audio if available
                        if (audioPlayerHelper != null && currentItem.audioEn.exists()) {
                            Log.i(TAG, "Playing English audio description")
                            audioPlayerHelper?.playAudio(
                                currentItem.audioEn,
                                onComplete = { 
                                    Log.i(TAG, "Audio description completed, returning to viewing mode")
                                    // Return to viewing mode after audio completes
                                    val finalState = _uiState.value
                                    if (finalState is AppState.Homework) {
                                        _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                                    }
                                },
                                onError = { err ->
                                    Log.e(TAG, "Audio playback error: $err")
                                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.AUDIO_NOT_AVAILABLE, currentState.language)
                                    ttsHelper.speak(message)
                                    // Return to viewing mode on error
                                    val finalState = _uiState.value
                                    if (finalState is AppState.Homework) {
                                        _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                                    }
                                }
                            )
                        } else {
                            val message = LocalizedStrings.getString(LocalizedStrings.StringKey.AUDIO_NOT_AVAILABLE, currentState.language)
                            ttsHelper.speak(message)
                            // Return to viewing mode after TTS
                            _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                        }
                    } else {
                        // Urdu mode: TTS the Urdu script
                        val urduScript = currentItem.scriptUr
                        ttsHelper.speak(urduScript) {
                            // Return to viewing mode after TTS completes
                            Log.i(TAG, "Urdu script TTS completed, returning to viewing mode")
                            val finalState = _uiState.value
                            if (finalState is AppState.Homework) {
                                _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                            }
                        }
                    }
                } else {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.NO_AUDIO_AVAILABLE, currentState.language)
                    ttsHelper.speak(message)
                    // Return to viewing mode after error message
                    _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                }
            }
            "switch" -> {
                // Get current state for switch command
                val currentState = _uiState.value
                if (currentState !is AppState.Homework) return
                val currentItem = currentState.currentItem
                
                if (currentItem == null) {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COULD_NOT_SWITCH_LANGUAGE, currentState.language)
                    ttsHelper.speak(message)
                    return
                }
                
                // Switch language
                val newLanguage = when (currentState.language) {
                    Language.ENGLISH -> Language.URDU
                    Language.URDU -> Language.ENGLISH
                }
                
                // Update TTS language
                ttsHelper.setLanguage(newLanguage)
                
                if (newLanguage == Language.URDU) {
                    // Check if already translated
                    if (currentItem.questionUrdu != null) {
                        // Instant switch - already translated
                        _uiState.value = currentState.copy(language = newLanguage, mode = HomeworkMode.VIEWING)
                        val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, newLanguage)
                        val questionText = currentItem.getCurrentQuestion(newLanguage)
                        ttsHelper.speak("$switchMessage Question ${currentItem.index}. $questionText")
                    } else {
                        // Show translating spinner and translate
                        _uiState.value = currentState.copy(language = newLanguage, mode = HomeworkMode.TRANSLATING)
                        val translatingMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.TRANSLATING_FEEDBACK, newLanguage)
                        ttsHelper.speak(translatingMessage)
                        
                        // Start translation
                        if (modelManager != null) {
                            translateQuestionToUrdu(modelManager, currentItem, ttsHelper, speakTranslatedQuestion = true)
                        }
                    }
                } else {
                    // Switch to English - instant
                    _uiState.value = currentState.copy(language = newLanguage, mode = HomeworkMode.VIEWING)
                    val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_ENGLISH, newLanguage)
                    val questionText = currentItem.getCurrentQuestion(newLanguage)
                    ttsHelper.speak("$switchMessage Question ${currentItem.index}. $questionText")
                }
            }
            "repeat" -> {
                // Get current state for repeat command
                val currentState = _uiState.value
                if (currentState !is AppState.Homework) return
                val currentItem = currentState.currentItem
                
                if (currentItem != null) {
                    Log.i(TAG, "Repeating question")
                    val questionPrefix = LocalizedStrings.getString(LocalizedStrings.StringKey.QUESTION_PREFIX, currentState.language)
                    val currentQuestion = currentItem.getCurrentQuestion(currentState.language)
                    ttsHelper.speak("$questionPrefix ${currentItem.index}. $currentQuestion")
                } else {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.NO_QUESTION_TO_REPEAT, currentState.language)
                    ttsHelper.speak(message)
                }
                // Return to viewing mode for repeat command
                _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
            }
            "ask", "sawal" -> {
                // Start asking question mode
                val currentState = _uiState.value
                if (currentState is AppState.Homework) {
                    _uiState.value = currentState.copy(mode = HomeworkMode.ASKING_QUESTION)
                    Log.i(TAG, "Started asking question mode")
                }
            }
            else -> {
                Log.w(TAG, "Unknown voice command: $command")
                val currentState = _uiState.value
                if (currentState is AppState.Homework) {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_RECOGNIZED, currentState.language)
                    ttsHelper.speak(message)
                    _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                } else {
                    ttsHelper.speak("Command not recognized. Say listen, switch, or repeat.")
                }
            }
        }
    }
    
    /**
     * Scan storage for lesson packs and feedback
     */
    private suspend fun scanForContent(context: Context?): NotificationType = withContext(Dispatchers.IO) {
        try {
            if (context == null) {
                return@withContext NotificationType.NONE
            }
            
            // Scan for real lesson packs from assets
            val lessonPack = LessonPackParser.scanAndCopyLessonPacks(context)
            currentLessonPack = lessonPack
            
            // Scan for feedback packs from assets
            val feedbackPack = FeedbackPackParser.scanAndCopyFeedbackPacks(context)
            currentFeedbackPack = feedbackPack
            
            val hasHomework = lessonPack != null && !lessonPack.isEmpty
            val hasFeedback = feedbackPack != null && !feedbackPack.isEmpty
            
            when {
                hasHomework && hasFeedback -> NotificationType.BOTH
                hasHomework -> NotificationType.HOMEWORK
                hasFeedback -> NotificationType.FEEDBACK
                else -> NotificationType.NONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for content: ${e.message}")
            NotificationType.NONE
        }
    }
    
    /**
     * Translate question to Urdu and cache it
     */
    fun translateQuestionToUrdu(modelManager: LlmModelManager, currentItem: LessonItem, ttsHelper: TtsHelper, speakTranslatedQuestion: Boolean = false) {
        if (currentItem.questionUrdu != null) {
            Log.i(TAG, "Question already translated, skipping")
            return
        }
        
        viewModelScope.launch {
            gemmaHelper?.translateQuestionToUrdu(
                modelManager = modelManager,
                question = currentItem.question,
                onResult = { translatedQuestion ->
                    currentItem.questionUrdu = translatedQuestion
                    Log.i(TAG, "Question translated to Urdu: $translatedQuestion")
                    
                    // Return to viewing mode
                    val currentState = _uiState.value
                    if (currentState is AppState.Homework) {
                        _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                    }
                    
                    if (speakTranslatedQuestion) {
                        val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                        val questionText = currentItem.getCurrentQuestion(Language.URDU)
                        ttsHelper.speak("$switchMessage Question ${currentItem.index}. $questionText")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Translation failed: $error")
                    
                    // Return to viewing mode even on error
                    val currentState = _uiState.value
                    if (currentState is AppState.Homework) {
                        _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
                    }
                    
                    // Fall back to speaking just the switch message
                    val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                    ttsHelper.speak(switchMessage)
                }
            )
        }
    }
    
    /**
     * Suspend version of translateQuestionToUrdu that waits for completion
     */
    suspend fun translateQuestionToUrduSuspend(modelManager: LlmModelManager, currentItem: LessonItem): Boolean {
        if (currentItem.questionUrdu != null) {
            Log.i(TAG, "Question already translated, skipping")
            return true
        }
        
        return suspendCancellableCoroutine { continuation ->
            viewModelScope.launch {
                gemmaHelper?.translateQuestionToUrdu(
                    modelManager = modelManager,
                    question = currentItem.question,
                    onResult = { translatedQuestion ->
                        currentItem.questionUrdu = translatedQuestion
                        Log.i(TAG, "Question translated to Urdu: $translatedQuestion")
                        continuation.resume(true)
                    },
                    onError = { error ->
                        Log.e(TAG, "Translation failed: $error")
                        continuation.resume(false)
                    }
                )
            }
        }
    }
    
    /**
     * Handle question recording and processing
     */
    fun handleQuestionRecording(questionText: String, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState !is AppState.Homework) return
        
        val currentItem = currentState.currentItem ?: return
        
        Log.i(TAG, "Processing user question: $questionText")
        
        // Switch to responding mode
        _uiState.value = currentState.copy(mode = HomeworkMode.GEMMA_RESPONDING)
        
        // Generate response with streaming TTS
        gemmaHelper?.generateResponseWithStreamingTTS(
            modelManager = modelManager,
            userQuestion = questionText,
            lessonItem = currentItem,
            language = currentState.language,
            ttsHelper = ttsHelper,
            onComplete = {
                // Return to viewing mode
                val finalState = _uiState.value
                if (finalState is AppState.Homework) {
                    _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                }
                Log.i(TAG, "Gemma response completed")
            },
            onError = { error ->
                Log.e(TAG, "Gemma response error: $error")
                // Return to viewing mode on error
                val finalState = _uiState.value
                if (finalState is AppState.Homework) {
                    _uiState.value = finalState.copy(mode = HomeworkMode.VIEWING)
                }
            }
        )
    }
    
        /**
     * Handle question recording result (called by UI layer after STT completes)
     */
    fun onQuestionRecordingResult(recognizedText: String, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        Log.i(TAG, "Question STT result: $recognizedText")
        handleQuestionRecording(recognizedText, modelManager, ttsHelper)
    }

    /**
     * Handle question recording error (called by UI layer if STT fails)
     */
    fun onQuestionRecordingError(currentState: AppState.Homework, ttsHelper: TtsHelper) {
        Log.e(TAG, "Question STT error")
        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_HEARD, currentState.language)
        ttsHelper.speak(message)
        _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
    }
    
    /**
     * Cancel Gemma response
     */
    fun cancelGemmaResponse() {
        gemmaHelper?.cancelResponse()
        val currentState = _uiState.value
        if (currentState is AppState.Homework && currentState.mode == HomeworkMode.GEMMA_RESPONDING) {
            _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
            Log.i(TAG, "Cancelled Gemma response")
        }
    }

    // ============ FEEDBACK FUNCTIONALITY ============
    
    /**
     * Handle gestures in feedback screen
     */
    fun onFeedbackGesture(gestureType: GestureType, ttsHelper: TtsHelper? = null) {
        val currentState = _uiState.value
        if (currentState !is AppState.Feedback) return

        when (currentState.mode) {
            FeedbackMode.VIEWING -> {
                when (gestureType) {
                    GestureType.TAP -> {
                        // Repeat feedback
                        val currentItem = currentState.currentItem
                        if (currentItem != null) {
                            // Extract just the clean feedback text (same logic as UI display)
                            val fullFeedbackText = currentItem.getCurrentFeedback(currentState.language)
                            val cleanFeedbackText = if (fullFeedbackText.contains("Feedback:")) {
                                fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
                            } else {
                                fullFeedbackText.substringBefore("Braille Text:").trim()
                            }
                            val prefix = LocalizedStrings.getString(LocalizedStrings.StringKey.FEEDBACK_PREFIX, currentState.language)
                            ttsHelper?.speak("$prefix: $cleanFeedbackText")
                        } else {
                            val message = LocalizedStrings.getString(LocalizedStrings.StringKey.NO_FEEDBACK_TO_REPEAT, currentState.language)
                            ttsHelper?.speak(message)
                        }
                    }
                    GestureType.LONG_PRESS -> {
                        // Start voice command
                        _uiState.value = currentState.copy(mode = FeedbackMode.AWAITING_COMMAND)
                        Log.i(TAG, "Started feedback voice command listening")
                    }
                    else -> { /* Ignore other gestures */ }
                }
            }
            FeedbackMode.ASKING_QUESTION -> {
                if (gestureType == GestureType.TAP) {
                    // Speech recognition stopped by UI layer, just return to viewing mode
                    _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
                }
            }
            else -> {
                // For other modes, return to viewing
                _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
            }
        }
    }

    /**
     * Handle voice commands in feedback screen
     */
    fun handleFeedbackVoiceCommand(command: String, ttsHelper: TtsHelper? = null, modelManager: LlmModelManager? = null) {
        val currentState = _uiState.value
        if (currentState !is AppState.Feedback) return

        val recognizedCommand = command.lowercase().trim()
        Log.i(TAG, "Recognized feedback command: '$recognizedCommand'")

        when {
            recognizedCommand.contains("repeat") || recognizedCommand.contains("دہرائیں") -> {
                // Repeat current feedback
                _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
                val currentItem = currentState.currentItem
                if (currentItem != null) {
                    // Extract just the clean feedback text (same logic as UI display)
                    val fullFeedbackText = currentItem.getCurrentFeedback(currentState.language)
                    val cleanFeedbackText = if (fullFeedbackText.contains("Feedback:")) {
                        fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
                    } else {
                        fullFeedbackText.substringBefore("Braille Text:").trim()
                    }
                    val prefix = LocalizedStrings.getString(LocalizedStrings.StringKey.FEEDBACK_PREFIX, currentState.language)
                    ttsHelper?.speak("$prefix: $cleanFeedbackText")
                } else {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.NO_FEEDBACK_TO_REPEAT, currentState.language)
                    ttsHelper?.speak(message)
                }
            }
            
            recognizedCommand.contains("switch") || recognizedCommand.contains("تبدیل") -> {
                // Switch language
                if (ttsHelper != null && modelManager != null) {
                    switchFeedbackLanguage(ttsHelper, modelManager)
                } else {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COULD_NOT_SWITCH_LANGUAGE, currentState.language)
                    ttsHelper?.speak(message)
                    _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
                }
            }
            
            recognizedCommand.contains("ask") || recognizedCommand.contains("سوال") -> {
                // Ask question about feedback
                _uiState.value = currentState.copy(mode = FeedbackMode.ASKING_QUESTION)
                Log.i(TAG, "Started feedback question recording")
            }
            
            recognizedCommand.isEmpty() -> {
                // Empty command, return to viewing
                _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
            }
            
            else -> {
                // Unrecognized command
                val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_RECOGNIZED, currentState.language)
                ttsHelper?.speak(message)
                _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
            }
        }
    }

    /**
     * Switch language in feedback screen
     */
    private fun switchFeedbackLanguage(ttsHelper: TtsHelper, modelManager: LlmModelManager): Language? {
        val currentState = _uiState.value
        Log.i(TAG, "switchFeedbackLanguage called - Current state: $currentState")
        if (currentState is AppState.Feedback) {
            Log.i(TAG, "Current language before switch: ${currentState.language}")
            val newLanguage = when (currentState.language) {
                Language.ENGLISH -> Language.URDU
                Language.URDU -> Language.ENGLISH
            }
            _uiState.value = currentState.copy(language = newLanguage, mode = FeedbackMode.VIEWING)
            Log.i(TAG, "Switched feedback language to: $newLanguage")

            // Update TTS language
            ttsHelper.setLanguage(newLanguage)

            // If switching to Urdu, translate feedback with proper UI state
            if (newLanguage == Language.URDU) {
                val currentItem = currentState.currentItem
                if (currentItem != null) {
                    // Set translating state and show message
                    _uiState.value = currentState.copy(language = newLanguage, mode = FeedbackMode.TRANSLATING)
                    val translatingMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.TRANSLATING_FEEDBACK, newLanguage)
                    ttsHelper.speak(translatingMessage)
                    
                    // Start translation asynchronously once model is ready
                    viewModelScope.launch {
                        while (!modelManager.isReady()) {
                            delay(200)
                        }
                        translateFeedbackToUrdu(modelManager, currentItem, ttsHelper, speakSwitch = true)
                    }
                }
            } else {
                // English branch – speak confirmation once
                val msg = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_ENGLISH, Language.ENGLISH)
                ttsHelper.speak(msg)
            }

            return newLanguage
        }
        return null
    }

    /**
     * Translate feedback to Urdu and cache it
     */
    private fun translateFeedbackToUrdu(modelManager: LlmModelManager, currentItem: FeedbackItem, ttsHelper: TtsHelper, speakSwitch: Boolean = true) {
        if (currentItem.feedbackUrdu != null) {
            Log.i(TAG, "Feedback already translated, skipping")
            return
        }

        viewModelScope.launch {
            // Extract just the clean feedback text (same logic as UI display and TTS)
            val fullFeedbackText = currentItem.feedbackText
            val cleanFeedbackText = if (fullFeedbackText.contains("Feedback:")) {
                fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
            } else {
                fullFeedbackText.substringBefore("Braille Text:").trim()
            }
            
            gemmaHelper?.translateFeedbackToUrdu(
                modelManager = modelManager,
                feedbackText = cleanFeedbackText,
                onResult = { translatedFeedback ->
                    currentItem.feedbackUrdu = translatedFeedback
                    Log.i(TAG, "Feedback translated to Urdu: $translatedFeedback")
                    
                    // Return to viewing mode
                    val currentState = _uiState.value
                    if (currentState is AppState.Feedback) {
                        _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
                    }
                    
                    if (speakSwitch) {
                        val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                        ttsHelper.speak("$switchMessage $translatedFeedback")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Feedback translation failed: $error")
                    
                    // Return to viewing mode even on error
                    val currentState = _uiState.value
                    if (currentState is AppState.Feedback) {
                        _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
                    }
                    
                    // Fall back to speaking just the switch message
                    val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                    ttsHelper.speak(switchMessage)
                }
            )
        }
    }

    /**
     * Handle feedback question recording result
     */
    fun onFeedbackQuestionRecordingResult(recognizedText: String, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState !is AppState.Feedback) return

        Log.i(TAG, "Feedback question recognized: $recognizedText")
        _uiState.value = currentState.copy(mode = FeedbackMode.GEMMA_RESPONDING)

        val currentItem = currentState.currentItem
        if (currentItem != null) {
            // Get corresponding lesson item for context (if available)
            val lessonItem = currentLessonPack?.items?.find { it.index == currentItem.index }
            
            gemmaHelper?.generateFeedbackUnderstandingWithStreamingTTS(
                modelManager = modelManager,
                userQuestion = recognizedText,
                feedbackItem = currentItem,
                lessonItem = lessonItem,
                language = currentState.language,
                ttsHelper = ttsHelper,
                onComplete = {
                    val updatedState = _uiState.value
                    if (updatedState is AppState.Feedback && updatedState.mode == FeedbackMode.GEMMA_RESPONDING) {
                        _uiState.value = updatedState.copy(mode = FeedbackMode.VIEWING)
                        Log.i(TAG, "Feedback understanding response completed")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Feedback understanding response error: $error")
                    val updatedState = _uiState.value
                    if (updatedState is AppState.Feedback) {
                        _uiState.value = updatedState.copy(mode = FeedbackMode.VIEWING)
                    }
                }
            )
        } else {
            Log.w(TAG, "No current feedback item for question")
            _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
        }
    }

    /**
     * Handle feedback question recording error
     */
    fun onFeedbackQuestionRecordingError(state: AppState.Feedback, ttsHelper: TtsHelper) {
        Log.e(TAG, "Feedback question recording error")
        val errorMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_HEARD, state.language)
        ttsHelper.speak(errorMessage)
        _uiState.value = state.copy(mode = FeedbackMode.VIEWING)
    }

    /**
     * Cancel Gemma response in feedback screen
     */
    fun cancelFeedbackGemmaResponse() {
        gemmaHelper?.cancelResponse()
        val currentState = _uiState.value
        if (currentState is AppState.Feedback && currentState.mode == FeedbackMode.GEMMA_RESPONDING) {
            _uiState.value = currentState.copy(mode = FeedbackMode.VIEWING)
            Log.i(TAG, "Cancelled feedback Gemma response")
        }
    }

    /**
     * Spatial image understanding methods
     */
    fun getSpatialCameraIntent(): Intent? {
        return photoCaptureHelper?.createCameraIntent(-1) // Use -1 for spatial photos
    }

    fun onSpatialTap(ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState is AppState.Spatial) {
            when (currentState.mode) {
                SpatialMode.AWAITING_PHOTO, SpatialMode.AWAITING_COMMAND -> {
                    // Take another photo
                    _uiState.value = currentState.copy(mode = SpatialMode.PROCESSING_PHOTO)
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.TAKE_PHOTO_INSTRUCTION, currentState.language)
                    ttsHelper.speak(message) {
                        val cameraIntent = getSpatialCameraIntent()
                        // Camera intent will be handled by Activity
                    }
                }
                else -> { /* No action for other modes */ }
            }
        }
    }

    fun onSpatialGesture(gestureType: GestureType, ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState is AppState.Spatial) {
            when (gestureType) {
                GestureType.LONG_PRESS -> {
                    if (currentState.mode == SpatialMode.AWAITING_COMMAND || currentState.mode == SpatialMode.AWAITING_PHOTO) {
                        // Start voice command listening (repeat/switch/new photo)
                        // TODO: Implement voice commands for spatial mode
                        Log.i(TAG, "Spatial voice commands not yet implemented")
                    }
                }
                GestureType.TAP -> {
                    if (currentState.mode == SpatialMode.GEMMA_RESPONDING) {
                        // Cancel Gemma response
                        gemmaHelper?.cancelResponse()
                        _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_COMMAND)
                    }
                }
                else -> { /* No action for other gestures */ }
            }
        }
    }

    fun onSpatialPhotoReady(photoFile: File, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState is AppState.Spatial) {
            Log.i(TAG, "Spatial photo captured: ${photoFile.absolutePath}")
            
            // Add photo to history and conversation
            currentState.images.add(photoFile)
            currentState.conversationHistory.add(
                SpatialMessage.ImageMessage(photoFile)
            )
            
            // Check model readiness and handle accordingly
            if (!modelManager.isReady()) {
                if (modelManager.isInitializing) {
                    // Model is loading - show status and wait
                    Log.i(TAG, "Model is loading, waiting for readiness...")
                    currentState.conversationHistory.add(
                        SpatialMessage.StatusMessage("Preparing Gemma3N...")
                    )
                    _uiState.value = currentState.copy(mode = SpatialMode.GEMMA_RESPONDING)
                    
                    // Wait for model to be ready
                    waitForModelAndProcess(photoFile, modelManager, ttsHelper)
                } else if (modelManager.initializationError != null) {
                    // Model failed to load
                    Log.e(TAG, "Model initialization failed: ${modelManager.initializationError}")
                    currentState.conversationHistory.add(
                        SpatialMessage.StatusMessage("Model loading failed. Please restart the app.")
                    )
                    _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_COMMAND)
                    ttsHelper.speak("Model loading failed. Please restart the app.")
                } else {
                    // Model not started - shouldn't happen but handle gracefully
                    Log.w(TAG, "Model not initialized and not loading")
                    currentState.conversationHistory.add(
                        SpatialMessage.StatusMessage("Model not ready. Please try again.")
                    )
                    _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_COMMAND)
                    ttsHelper.speak("Model not ready. Please try again.")
                }
            } else {
                // Model is ready - process immediately
                processSpatialImage(photoFile, modelManager, ttsHelper)
            }
        }
    }
    
    private fun waitForModelAndProcess(photoFile: File, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        viewModelScope.launch {
            try {
                // Wait for model to be ready (with timeout)
                var waitTime = 0
                val maxWaitTime = 30000 // 30 seconds
                
                while (!modelManager.isReady() && waitTime < maxWaitTime) {
                    delay(500)
                    waitTime += 500
                    
                    // Update status periodically (but don't add to conversation history)
                    if (waitTime % 5000 == 0) { // Every 5 seconds
                        Log.d(TAG, "Still waiting for model... (${waitTime/1000}s)")
                    }
                }
                
                val currentState = _uiState.value
                if (currentState is AppState.Spatial) {
                    // Clear all "Preparing Gemma3N" status messages
                    currentState.conversationHistory.removeIf { 
                        it is SpatialMessage.StatusMessage && 
                        it.status.startsWith("Preparing Gemma3N")
                    }
                    
                    if (modelManager.isReady()) {
                        Log.i(TAG, "Model ready after ${waitTime}ms, processing image")
                        _uiState.value = currentState.copy() // Trigger UI update
                        processSpatialImage(photoFile, modelManager, ttsHelper)
                    } else {
                        Log.e(TAG, "Model loading timeout after ${waitTime}ms")
                        currentState.conversationHistory.add(
                            SpatialMessage.StatusMessage("Model loading timeout. Please try again.")
                        )
                        _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_COMMAND)
                        ttsHelper.speak("Model loading took too long. Please try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for model: ${e.message}")
                val currentState = _uiState.value
                if (currentState is AppState.Spatial) {
                    // Clear preparing messages before adding error
                    currentState.conversationHistory.removeIf { 
                        it is SpatialMessage.StatusMessage && 
                        it.status.startsWith("Preparing Gemma3N")
                    }
                    currentState.conversationHistory.add(
                        SpatialMessage.StatusMessage("Error loading model. Please try again.")
                    )
                    _uiState.value = currentState.copy(mode = SpatialMode.AWAITING_COMMAND)
                    ttsHelper.speak("Error loading model. Please try again.")
                }
            }
        }
    }
    
    private fun processSpatialImage(photoFile: File, modelManager: LlmModelManager, ttsHelper: TtsHelper) {
        val currentState = _uiState.value
        if (currentState is AppState.Spatial) {
            // Add status message
            val statusMessage = SpatialMessage.StatusMessage("Understanding image...")
            currentState.conversationHistory.add(statusMessage)
            _uiState.value = currentState.copy(mode = SpatialMode.GEMMA_RESPONDING, isStreaming = false)
            
            Log.i(TAG, "Starting Gemma spatial processing with ${currentState.images.size} images")
            
            var firstTokenReceived = false
            
            gemmaHelper?.generateSpatialDescriptionWithStreamingTTS(
                modelManager = modelManager,
                images = currentState.images.toList(),
                language = currentState.language,
                ttsHelper = ttsHelper,
                conversationInitialized = currentState.conversationInitialized,
                onStreamingUpdate = { partialText ->
                    val newState = _uiState.value
                    if (newState is AppState.Spatial) {
                        var historyForUpdate = newState.conversationHistory
                        // Remove status message on first token
                        if (!firstTokenReceived && partialText.isNotEmpty()) {
                            firstTokenReceived = true
                            // Remove the "Understanding image..." status message
                            val updatedHistory = newState.conversationHistory.toMutableList()
                            updatedHistory.removeIf {
                                it is SpatialMessage.StatusMessage && it.status == "Understanding image..."
                            }
                            historyForUpdate = updatedHistory
                            Log.d(TAG, "First token received, removed status message")
                        }
                        
                        _uiState.value = newState.copy(
                            conversationHistory = historyForUpdate,
                            currentStreamingText = partialText,
                            isStreaming = true
                        )
                    }
                },
                onComplete = { finalText ->
                    Log.i(TAG, "Spatial Gemma processing completed: '${finalText.take(100)}...'")
                    val newState = _uiState.value
                    if (newState is AppState.Spatial) {
                        // Build updated history immutably
                        val updatedHistory = newState.conversationHistory.toMutableList()
                        if (finalText.isNotEmpty()) {
                            updatedHistory.add(
                                SpatialMessage.ResponseMessage(finalText)
                            )
                        }
                        // Keep streaming visible until TTS is done, but mark as completed
                        _uiState.value = newState.copy(
                            conversationHistory = updatedHistory,
                            mode = SpatialMode.AWAITING_COMMAND,
                            conversationInitialized = true,
                            currentStreamingText = finalText, // Keep text visible during TTS
                            isStreaming = false // But mark as not streaming
                        )
                    }
                },
                onTTSComplete = {
                    // Called when TTS finishes - now clear the streaming text
                    Log.i(TAG, "Spatial TTS completed")
                    val newState = _uiState.value
                    if (newState is AppState.Spatial) {
                        _uiState.value = newState.copy(
                            currentStreamingText = ""
                        )
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Spatial Gemma processing failed: $error")
                    val newState = _uiState.value
                    if (newState is AppState.Spatial) {
                        // Remove understanding status and add error
                        newState.conversationHistory.removeIf { 
                            it is SpatialMessage.StatusMessage && it.status == "Understanding image..." 
                        }
                        newState.conversationHistory.add(
                            SpatialMessage.StatusMessage("Failed to understand image: $error")
                        )
                        _uiState.value = newState.copy(
                            mode = SpatialMode.AWAITING_COMMAND,
                            currentStreamingText = "",
                            isStreaming = false
                        )
                        ttsHelper.speak("Sorry, I couldn't process the image. Please try again.")
                    }
                }
            )
        }
    }

    fun goHome() {
        // Clear any spatial conversation state when leaving
        val currentState = _uiState.value
        if (currentState is AppState.Spatial) {
            // TODO: Clean up Gemma session if needed
        }
        
        // Use a simple notification for now - will be updated on next initialization
        _uiState.value = AppState.Home(notification = NotificationType.NONE)
    }

    /**
     * Cleanup resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        voiceRecorderHelper?.release()
        audioPlayerHelper?.release()
        photoCaptureHelper = null
        gemmaHelper?.cancelResponse()
    }

}