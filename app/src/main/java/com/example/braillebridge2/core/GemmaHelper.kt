package com.example.braillebridge2.core

import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.example.braillebridge2.chat.LlmModelManager
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class for Gemma integration in homework features
 */
class GemmaHelper {
    private val TAG = "GemmaHelper"
    private val isCancelled = AtomicBoolean(false)
    private var currentJob: Job? = null
    
    /**
     * Translate question to Urdu using Gemma
     */
    suspend fun translateQuestionToUrdu(
        modelManager: LlmModelManager,
        question: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!modelManager.isReady()) {
            onError("Model not ready")
            return
        }
        
        try {
            val llmInstance = modelManager.instance!!
            val llmSession = llmInstance.session
            
            val prompt = "Translate this English question to Urdu. Only provide the translation, no additional text: $question"
            
            llmSession.addQueryChunk(prompt)
            
            var result = ""
            llmSession.generateResponseAsync { partialResult, done ->
                result = partialResult
                if (done) {
                    onResult(result.trim())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation error: ${e.message}")
            onError("Translation failed: ${e.message}")
        }
    }
    
    /**
     * Generate response to user question about the image with streaming TTS
     */
    fun generateResponseWithStreamingTTS(
        modelManager: LlmModelManager,
        userQuestion: String,
        lessonItem: LessonItem,
        language: Language,
        ttsHelper: TtsHelper,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!modelManager.isReady()) {
            onError("Model not ready")
            return
        }
        
        isCancelled.set(false)
        
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                val llmInstance = modelManager.instance!!
                val llmSession = llmInstance.session
                
                // Read diagram.json for context
                val diagramContext = try {
                    lessonItem.diagramJson.readText()
                } catch (e: Exception) {
                    "{\"description\": \"No diagram data available\"}"
                }
                
                // Get current question in the appropriate language
                val currentQuestion = lessonItem.getCurrentQuestion(language)
                
                // Build prompt
                val languageInstruction = if (language == Language.URDU) "Respond in Urdu." else ""
                val prompt = """
                    You are guiding a blind child to explore educational content using mental visualization and spatial reasoning.

                    $languageInstruction

                    Your response must:
                    - Be SHORT: 1-2 sentences (10-15 seconds spoken).
                    - Use SIMPLE, easy vocabulary suitable for kids.
                    - Rely heavily on SPATIAL VOCABULARY (e.g., "above", "next to", "towards the center", "in the middle", "on the left", "on the right", "in the top", "in the bottom").
                    - Describe colors, shapes, and sizes.
                    - Say things like you can move your finger or place it here to locate the object.
                    - Refuse to answer the main question directly.

                    Context:
                    - Main question: $currentQuestion
                    - Diagram details: $diagramContext

                    The child asks: $userQuestion

                    Respond with a guiding hint that helps them *picture* the diagram in their mind and think critically.
                """.trimIndent()
                
                Log.d(TAG, "Sending prompt to Gemma: $prompt")
                llmSession.addQueryChunk(prompt)
                
                var accumulatedText = ""
                var lastTTSIndex = 0
                val ttsQueue = mutableListOf<String>()
                var isTTSPlaying = false
                
                // TTS playback function
                fun playNextTTS() {
                    if (ttsQueue.isNotEmpty() && !isCancelled.get()) {
                        isTTSPlaying = true
                        val textToSpeak = ttsQueue.removeAt(0)
                        Log.d(TAG, "Playing TTS: '$textToSpeak'")
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            ttsHelper.speak(textToSpeak) {
                                // TTS completed callback
                                Log.d(TAG, "TTS completed for: '${textToSpeak.take(50)}...'")
                                isTTSPlaying = false
                                if (ttsQueue.isNotEmpty() && !isCancelled.get()) {
                                    playNextTTS()
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "No more TTS to play. Queue empty: ${ttsQueue.isEmpty()}, cancelled: ${isCancelled.get()}")
                        isTTSPlaying = false
                    }
                }
                
                // Use synchronous generation for now due to issues with generateResponseAsync
                Log.d(TAG, "Starting synchronous Gemma generation...")
                val response = llmSession.generateResponse()
                Log.d(TAG, "Gemma generation complete. Response length: ${response.length}")
                Log.d(TAG, "Response preview: '${response.take(200)}...'")
                
                if (isCancelled.get()) return@launch
                
                // Process the complete response for TTS
                accumulatedText = response
                val periodChars = if (language == Language.URDU) "۔" else "."
                
                // Split into sentences and add to TTS queue (limit to keep responses short)
                val sentences = response.split(periodChars)
                val maxSentences = 3 // Limit to 3 sentences maximum for brevity
                
                for (i in 0 until minOf(sentences.size, maxSentences)) {
                    if (isCancelled.get()) break
                    
                    var sentence = sentences[i].trim()
                    if (sentence.isNotEmpty()) {
                        // Add period back except for the last sentence (which might not have one)
                        if (i < sentences.size - 1) {
                            sentence += periodChars
                        }
                        
                        Log.d(TAG, "Adding sentence to TTS queue: '${sentence.take(50)}...' (${i + 1}/${maxSentences})")
                        ttsQueue.add(sentence)
                        
                        // Start TTS if not already playing
                        if (!isTTSPlaying) {
                            playNextTTS()
                        }
                    }
                }
                
                if (sentences.size > maxSentences) {
                    Log.d(TAG, "Truncated response from ${sentences.size} to $maxSentences sentences for brevity")
                }
                
                // Wait for TTS queue to finish, then call onComplete
                withContext(Dispatchers.Main) {
                    while (ttsQueue.isNotEmpty() || isTTSPlaying) {
                        delay(100)
                        if (isCancelled.get()) break
                    }
                    if (!isCancelled.get()) {
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Response generation error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Response generation failed: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Translate feedback text to Urdu using Gemma
     */
    suspend fun translateFeedbackToUrdu(
        modelManager: LlmModelManager,
        feedbackText: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!modelManager.isReady()) {
            onError("Model not ready for translation")
            return
        }

        val prompt = "Translate the following English feedback to Urdu. Only respond with the translated Urdu text and nothing else: \"$feedbackText\""
        Log.i(TAG, "Translating feedback: $prompt")

        try {
            val response = withContext(Dispatchers.Default) {
                val llmSession = modelManager.instance!!.session
                llmSession.addQueryChunk(prompt)
                llmSession.generateResponse()
            }
            onResult(response.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Feedback translation error: ${e.message}")
            onError("Feedback translation failed: ${e.message}")
        }
    }

    /**
     * Generate feedback understanding response with streaming TTS
     */
    fun generateFeedbackUnderstandingWithStreamingTTS(
        modelManager: LlmModelManager,
        userQuestion: String,
        feedbackItem: FeedbackItem,
        lessonItem: LessonItem?, // Optional - for context
        language: Language,
        ttsHelper: TtsHelper,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        isCancelled.set(false)
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            if (!modelManager.isReady()) {
                withContext(Dispatchers.Main) { onError("Model not ready for response") }
                return@launch
            }

            try {
                val llmInstance = modelManager.instance!!
                val llmSession = llmInstance.session

                val diagramContext = try {
                    lessonItem?.diagramJson?.readText() ?: "{\"description\": \"No diagram data available\"}"
                } catch (e: Exception) {
                    "{\"description\": \"No diagram data available\"}"
                }

                val originalQuestion = lessonItem?.getCurrentQuestion(language) ?: "Question not available"
                val fullFeedbackText = feedbackItem.getCurrentFeedback(language)
                // Extract just the clean feedback text (same logic as UI display and TTS)
                val currentFeedback = if (fullFeedbackText.contains("Feedback:")) {
                    fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
                } else {
                    fullFeedbackText.substringBefore("Braille Text:").trim()
                }

                val languageInstruction = if (language == Language.URDU) "Respond in Urdu." else ""
                // Build prompt.
                if (!feedbackItem.conversationInitialized) {
                    val basePrompt = """
                        You are helping a visually impaired child understand teacher feedback.
                        $languageInstruction
                        Give a SHORT answer (1-2 sentences maximum, about 10-15 seconds when spoken).
                        Act as a supportive tutor explaining what the teacher meant and how to improve.
                        DO NOT reveal the homework answer directly - focus on understanding the feedback.
                        
                        Context:
                        - Original question: $originalQuestion
                        - Teacher feedback: $currentFeedback
                        - Diagram data: $diagramContext
                    """.trimIndent()
                    llmSession.addQueryChunk(basePrompt)
                    feedbackItem.conversationInitialized = true
                }
                // Add current user question as new turn
                llmSession.addQueryChunk(userQuestion)
                Log.d(TAG, "Sent user question to Gemma: $userQuestion")

                val ttsQueue = mutableListOf<String>()
                var isTTSPlaying = false

                // TTS playback function
                fun playNextTTS() {
                    if (ttsQueue.isNotEmpty() && !isCancelled.get()) {
                        isTTSPlaying = true
                        val textToSpeak = ttsQueue.removeAt(0)
                        Log.d(TAG, "Playing TTS: '$textToSpeak'")

                        CoroutineScope(Dispatchers.Main).launch {
                            ttsHelper.speak(textToSpeak) {
                                Log.d(TAG, "TTS completed for: '${textToSpeak.take(50)}...'")
                                isTTSPlaying = false
                                if (ttsQueue.isNotEmpty() && !isCancelled.get()) {
                                    playNextTTS()
                                }
                            }
                        }
                    } else {
                        isTTSPlaying = false
                    }
                }

                // Use synchronous generation
                Log.d(TAG, "Starting synchronous Gemma generation for feedback understanding...")
                val response = llmSession.generateResponse()
                Log.d(TAG, "Gemma generation complete. Response length: ${response.length}")

                if (isCancelled.get()) return@launch

                // Process the complete response for TTS
                val periodChars = if (language == Language.URDU) "۔" else "."
                val sentences = response.split(periodChars)
                val maxSentences = 3 // Limit to 3 sentences maximum for brevity

                for (i in 0 until minOf(sentences.size, maxSentences)) {
                    if (isCancelled.get()) break

                    var sentence = sentences[i].trim()
                    if (sentence.isNotEmpty()) {
                        if (i < sentences.size - 1) {
                            sentence += periodChars
                        }

                        Log.d(TAG, "Adding sentence to TTS queue: '${sentence.take(50)}...' (${i + 1}/${maxSentences})")
                        ttsQueue.add(sentence)

                        if (!isTTSPlaying) {
                            playNextTTS()
                        }
                    }
                }

                // Wait for TTS queue to finish
                withContext(Dispatchers.Main) {
                    while (ttsQueue.isNotEmpty() || isTTSPlaying) {
                        delay(100)
                        if (isCancelled.get()) break
                    }
                    if (!isCancelled.get()) {
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Feedback understanding error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Feedback understanding failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Cancel ongoing response generation
     */
    fun cancelResponse() {
        isCancelled.set(true)
        currentJob?.cancel()
        currentJob = null
    }
}