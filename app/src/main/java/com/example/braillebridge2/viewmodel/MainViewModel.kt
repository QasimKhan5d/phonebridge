package com.example.braillebridge2.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.braillebridge2.core.*
import com.example.braillebridge2.chat.LlmModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MainViewModel"

/**
 * Main ViewModel that manages the application state machine
 */
class MainViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow<AppState>(AppState.Loading)
    val uiState = _uiState.asStateFlow()
    
    private var currentLessonPack: LessonPack? = null
    private var voiceRecorderHelper: VoiceRecorderHelper? = null
    private var photoCaptureHelper: PhotoCaptureHelper? = null
    private var audioPlayerHelper: AudioPlayerHelper? = null
    private var gemmaHelper: GemmaHelper? = null
    
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
                    // TODO: Implement feedback functionality
                    Log.i(TAG, "Feedback functionality not yet implemented")
                }
                else -> {
                    Log.w(TAG, "No feedback available to open")
                }
            }
        }
    }
    
    /**
     * Navigate to next homework item or return home if complete
     */
    fun moveToNextHomeworkItem() {
        val currentState = _uiState.value
        if (currentState is AppState.Homework) {
            if (currentState.hasNextItem) {
                _uiState.value = currentState.copy(
                    currentIndex = currentState.currentIndex + 1,
                    mode = HomeworkMode.VIEWING
                )
                Log.i(TAG, "Moved to item ${currentState.currentIndex + 1}")
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
                    stopVoiceAnswer(currentState, ttsHelper)
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
        
        voiceRecorderHelper?.startRecording(currentItem.index) { success, error ->
            if (success) {
                _uiState.value = currentState.copy(mode = HomeworkMode.RECORDING_VOICE)
                Log.i(TAG, "Started voice answer recording for lesson ${currentItem.index}")
            } else {
                Log.e(TAG, "Failed to start voice recording: $error")
                // Stay in viewing mode if recording failed
            }
        }
    }
    
    /**
     * Stop voice answer recording
     */
    private fun stopVoiceAnswer(currentState: AppState.Homework, ttsHelper: TtsHelper? = null) {
        voiceRecorderHelper?.stopRecording { success, file, error ->
            if (success && file != null) {
                Log.i(TAG, "Voice answer saved: ${file.absolutePath}")
                // TODO: Store the answer file path in the lesson state or database
                // Play back the recording for confirmation
                audioPlayerHelper?.playAudio(file)
            } else {
                Log.e(TAG, "Failed to save voice recording: $error")
                val currentState = _uiState.value
                if (currentState is AppState.Homework) {
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.RECORDING_FAILED, currentState.language)
                    ttsHelper?.speak(message)
                } else {
                    ttsHelper?.speak("Recording failed")
                }
            }
            // Return to viewing mode regardless of success/failure
            _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
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
    fun handlePhotoCaptureResult(resultCode: Int, ttsHelper: TtsHelper? = null) {
        val currentState = _uiState.value
        if (currentState is AppState.Homework) {
            photoCaptureHelper?.handleCameraResult(resultCode) { success, file, error ->
                if (success && file != null) {
                    Log.i(TAG, "Photo answer saved: ${file.absolutePath}")
                    // TODO: Store the answer file path in the lesson state or database
                    val currentState = _uiState.value
                    if (currentState is AppState.Homework) {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.PHOTO_SAVED, currentState.language)
                        ttsHelper?.speak(message)
                    } else {
                        ttsHelper?.speak("Photo saved")
                    }
                } else {
                    Log.e(TAG, "Failed to save photo: $error")
                }
                // Return to viewing mode regardless of success/failure
                _uiState.value = currentState.copy(mode = HomeworkMode.VIEWING)
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
     * Switch language and update TTS
     */
    fun switchLanguage(ttsHelper: TtsHelper? = null, modelManager: LlmModelManager? = null): Language? {
        val currentState = _uiState.value
        Log.i(TAG, "switchLanguage called - Current state: $currentState")
        if (currentState is AppState.Homework) {
            Log.i(TAG, "Current language before switch: ${currentState.language}")
            val newLanguage = when (currentState.language) {
                Language.ENGLISH -> Language.URDU
                Language.URDU -> Language.ENGLISH
            }
            _uiState.value = currentState.copy(language = newLanguage, mode = HomeworkMode.VIEWING)
            Log.i(TAG, "Switched language to: $newLanguage")
            Log.i(TAG, "New state after switch: ${_uiState.value}")
            
            // Update TTS language
            ttsHelper?.setLanguage(newLanguage)
            
            // If switching to Urdu, translate question synchronously so it's ready for "repeat"
            if (newLanguage == Language.URDU && modelManager != null && ttsHelper != null) {
                val currentItem = currentState.currentItem
                if (currentItem != null) {
                    kotlinx.coroutines.runBlocking {
                        translateQuestionToUrdu(modelManager, currentItem, ttsHelper, speakSwitch = false)
                    }
                    // speak confirmation + Urdu script once
                    ttsHelper.speak(currentItem.scriptUr)
                }
            } else {
                // English branch – speak confirmation once
                val msg = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_ENGLISH, Language.ENGLISH)
                ttsHelper?.speak(msg)
            }
            
            return newLanguage
        }
        return null
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
                    if (currentState.language == Language.ENGLISH) {
                        // Play English audio if available
                        if (audioPlayerHelper != null && currentItem.audioEn.exists()) {
                            Log.i(TAG, "Playing English audio description")
                            audioPlayerHelper?.playAudio(
                                currentItem.audioEn,
                                onComplete = { Log.i(TAG, "Audio description completed") },
                                onError = { err ->
                                    Log.e(TAG, "Audio playback error: $err")
                                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.AUDIO_NOT_AVAILABLE, currentState.language)
                                    ttsHelper.speak(message)
                                }
                            )
                        } else {
                            val message = LocalizedStrings.getString(LocalizedStrings.StringKey.AUDIO_NOT_AVAILABLE, currentState.language)
                            ttsHelper.speak(message)
                        }
                    } else {
                        // Urdu mode: TTS the Urdu script
                        val urduScript = currentItem.scriptUr
                        ttsHelper.speak(urduScript)
                    }
                } else {
                    val currentState = _uiState.value
                    if (currentState is AppState.Homework) {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.NO_AUDIO_AVAILABLE, currentState.language)
                        ttsHelper.speak(message)
                    } else {
                        ttsHelper.speak("No audio available")
                    }
                }
                // Return to viewing mode for listen command
                val currentStateForReturn = _uiState.value
                if (currentStateForReturn is AppState.Homework) {
                    _uiState.value = currentStateForReturn.copy(mode = HomeworkMode.VIEWING)
                }
            }
            "switch" -> {
                // Get current state for switch command
                val currentState = _uiState.value
                if (currentState !is AppState.Homework) return
                val currentItem = currentState.currentItem
                
                val newLanguage = switchLanguage(ttsHelper, modelManager)
                
                if (currentItem != null && newLanguage != null) {
                    val newLangName = when (newLanguage) {
                        Language.ENGLISH -> "English"
                        Language.URDU -> "Urdu"
                    }
                    
                    // TTS the script in the new language
                    val scriptText = currentItem.getCurrentScript(newLanguage)
                    val switchMessage = when (newLanguage) {
                        Language.ENGLISH -> LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_ENGLISH, newLanguage)
                        Language.URDU -> LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, newLanguage)
                    }
                    ttsHelper.speak("$switchMessage $scriptText")
                } else if (newLanguage != null) {
                    val switchMessage = when (newLanguage) {
                        Language.ENGLISH -> LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_ENGLISH, newLanguage)
                        Language.URDU -> LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, newLanguage)
                    }
                    ttsHelper.speak(switchMessage)
                } else {
                    val currentState = _uiState.value
                    val language = if (currentState is AppState.Homework) currentState.language else Language.ENGLISH
                    val message = LocalizedStrings.getString(LocalizedStrings.StringKey.COULD_NOT_SWITCH_LANGUAGE, language)
                    ttsHelper.speak(message)
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
            
            if (lessonPack != null && !lessonPack.isEmpty) {
                NotificationType.HOMEWORK
            } else {
                NotificationType.NONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for content: ${e.message}")
            NotificationType.NONE
        }
    }
    
    /**
     * Translate question to Urdu and cache it
     */
    fun translateQuestionToUrdu(modelManager: LlmModelManager, currentItem: LessonItem, ttsHelper: TtsHelper, speakSwitch: Boolean = true) {
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
                    if (speakSwitch) {
                        val scriptText = currentItem.getCurrentScript(Language.URDU)
                        val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                        ttsHelper.speak("$switchMessage $scriptText")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Translation failed: $error")
                    // Fall back to speaking just the switch message
                    val switchMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.SWITCHED_TO_URDU, Language.URDU)
                    ttsHelper.speak(switchMessage)
                }
            )
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