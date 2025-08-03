package com.example.braillebridge2.chat

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

/**
 * Data class to hold LLM inference engine and session
 * Based on gallery app pattern for better state management
 */
data class LlmModelInstance(
    val engine: LlmInference,
    var session: LlmInferenceSession,
    val isInitialized: Boolean = true
)

/**
 * Model manager class to handle LLM initialization and state
 * Simplified version of gallery app's Model class
 */
class LlmModelManager {
    @Volatile
    var instance: LlmModelInstance? = null
        private set
    
    @Volatile
    var isInitializing: Boolean = false
        private set
    
    @Volatile
    var initializationError: String? = null
        private set
        
    fun setInstance(instance: LlmModelInstance) {
        this.instance = instance
        this.isInitializing = false
        this.initializationError = null
    }
    
    fun setInitializing(initializing: Boolean) {
        this.isInitializing = initializing
        if (initializing) {
            this.initializationError = null
        }
    }
    
    fun setError(error: String) {
        this.initializationError = error
        this.isInitializing = false
        this.instance = null
    }
    
    fun isReady(): Boolean = instance != null && !isInitializing
    
    fun cleanup() {
        instance?.let {
            try {
                it.session.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
            try {
                it.engine.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        instance = null
        isInitializing = false
        initializationError = null
    }
}