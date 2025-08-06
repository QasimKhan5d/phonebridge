package com.example.braillebridge2.core

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.example.braillebridge2.chat.LlmModelManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
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
        
        val prompt = "Translate this sentence to Urdu: $question. Do not include any other text or explanation. Only respond with the Urdu script."
        Log.i(TAG, "Translating question: $prompt")
        
        try {
            val response = withContext(Dispatchers.Default) {
                val llmSession = modelManager.instance!!.session
                
                // Wait a bit to ensure any previous operations are complete
                kotlinx.coroutines.delay(100)
                
                // Clear session state and add new query
                llmSession.addQueryChunk(prompt)
                llmSession.generateResponse()
            }
            Log.i(TAG, "Question translation result: '$response'")
            onResult(response.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Question translation error: ${e.message}")
            onError("Question translation failed: ${e.message}")
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
                    You are guiding a blind or visually impaired child to explore educational content using mental visualization and spatial reasoning.
                    A teacher provided a main question for the student to answer alongside some diagram details. The student is asking you for a hint to help them answer the question.

                    $languageInstruction

                    Your response must:
                    - Be SHORT: 1-2 sentences (10-15 seconds spoken).
                    - Use SIMPLE, easy vocabulary suitable for young children.
                    - Rely heavily on SPATIAL VOCABULARY (e.g., "above", "next to", "towards the center", "in the middle", "on the left", "on the right", "in the top", "in the bottom").
                    - Describe colors, shapes, and sizes.
                    - Say things like you can move your finger or place it here to locate the object.

                    Context:
                    - Main question: $currentQuestion
                    - Diagram details: $diagramContext

                    The child asks: $userQuestion

                    Respond by helping them *picture* the diagram in their mind.
                """.trimIndent()
                
                Log.d(TAG, "Sending prompt to Gemma: $prompt")
                llmSession.addQueryChunk(prompt)
                
                var accumulatedText = ""
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
     * Generate spatial description response with streaming TTS for image understanding
     */
    fun generateSpatialDescriptionWithStreamingTTS(
        modelManager: LlmModelManager,
        images: List<File>, // List of image files from spatial mode
        language: Language,
        ttsHelper: TtsHelper,
        conversationInitialized: Boolean,
        onStreamingUpdate: (String) -> Unit, // Called with partial responses during streaming
        onComplete: (String) -> Unit, // Called with complete response
        onTTSComplete: () -> Unit, // Called when TTS playback finishes
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
                
                // Initialize conversation with base prompt if first time
                if (!conversationInitialized) {
                    val languageInstruction = if (language == Language.URDU) "Respond in Urdu only." else ""
                    val basePrompt = """
                        You are an AI guide that helps blind or visually-impaired children explore diagrams and pictures with their fingertips.

                        === TASK ===
                        For **each** image you receive:
                        1. Detect every salient object, label, arrow, connector or region.
                        2. Detect the child's finger (the pointing tip) and the single object it touches.
                        3. Infer the *role or function* of that pointed-to object within the context of the diagram (e.g., “heart pumps blood”, “USB port lets data in/out”).
                        4. Determine the 2-3 nearest objects the child could easily trace their finger to next.
                        5. Work out clear spatial relationships using vocabulary a 7-year-old can grasp (above, below, left/right of, at the edge of the paper, on the corner, etc).
                        6. Note visible colors, basic shapes, and relative sizes where helpful.
                        7. Use directive language for nearby objects like, "if you move your finger a little bit to the right, then you can find the XYZ which does ABC"

                        === RESPONSE RULES ===
                        * **Length:** 1-2 short sentences (≈ 10-15 seconds spoken).
                        * **Style:** Simple, friendly language suited to young children. Avoid complex terms or long clauses.
                        * **Focus:** 
                        * Name the object being touched **first**.
                        * State its role/function in 1-2 words if obvious (e.g., “the heart - it pumps blood”).
                        * Describe its location in the diagram using spatial words.
                        * Mention the nearest object(s) the child can reach next, with one spatial cue each.
                        * Do not use vague language like "next to" or "nearby" — always be specific (e.g., "to the left of the circle", "below the red square").

                        $languageInstruction

                        Remember: keep it short, vivid, and spatially precise.
                    """.trimIndent()
                    
                    llmSession.addQueryChunk(basePrompt)
                    Log.d(TAG, "Added base spatial prompt to session")
                }
                
                // Add all images to the session for context
                val bitmaps = mutableListOf<Bitmap>()
                for (imageFile in images) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            bitmaps.add(bitmap)
                            llmSession.addImage(BitmapImageBuilder(bitmap).build())
                            Log.d(TAG, "Added image to session: ${imageFile.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading image ${imageFile.name}: ${e.message}")
                    }
                }
                
                if (bitmaps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onError("No valid images to process")
                    }
                    return@launch
                }
                
                // Add query for the latest image
                val queryPrompt = if (images.size == 1) {
                    "Describe what the child is pointing to in this diagram and its spatial location."
                } else {
                    "Describe what the child is pointing to in this new image, considering the context from previous images."
                }
                
                llmSession.addQueryChunk(queryPrompt)
                Log.d(TAG, "Added spatial query to session with ${images.size} images")
                
                val ttsQueue = mutableListOf<String>()
                var isTTSPlaying = false
                
                // TTS playback function
                fun playNextTTS() {
                    if (ttsQueue.isNotEmpty() && !isCancelled.get()) {
                        isTTSPlaying = true
                        val textToSpeak = ttsQueue.removeAt(0)
                        Log.d(TAG, "Playing spatial TTS: '$textToSpeak'")
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            ttsHelper.speak(textToSpeak) {
                                Log.d(TAG, "Spatial TTS completed for: '${textToSpeak.take(50)}...'")
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
                
                // Generate response with streaming
                Log.d(TAG, "Starting spatial Gemma generation with streaming...")
                val responseBuilder = StringBuilder()
                
                // Start streaming inference
                llmSession.generateResponseAsync { partialResult, done ->
                    if (isCancelled.get()) return@generateResponseAsync
                    
                    // append new token/chunk to builder
                    responseBuilder.append(partialResult)
                    val accumulatedResponse = responseBuilder.toString()
                    
                    // Send streaming update to UI
                    CoroutineScope(Dispatchers.Main).launch {
                        onStreamingUpdate(accumulatedResponse)
                    }
                    
                    Log.d(TAG, "Spatial streaming update: '${partialResult.take(100)}...' (done: $done)")
                    
                    if (done) {
                        val finalResponse = responseBuilder.toString()
                        Log.d(TAG, "Spatial generation complete. Total length: ${finalResponse.length}")
                        Log.d(TAG, "Final response: '${finalResponse.take(200)}...'")
                        
                        if (isCancelled.get()) return@generateResponseAsync
                        
                        // Process final response for TTS
                        val periodChars = if (language == Language.URDU) "۔" else "."
                        val sentences = finalResponse.split(periodChars)
                        val maxSentences = 3 // Keep responses brief
                        
                        for (i in 0 until minOf(sentences.size, maxSentences)) {
                            if (isCancelled.get()) break
                            
                            var sentence = sentences[i].trim()
                            if (sentence.isNotEmpty()) {
                                if (i < sentences.size - 1) {
                                    sentence += periodChars
                                }
                                
                                Log.d(TAG, "Adding spatial sentence to TTS queue: '${sentence.take(50)}...' (${i + 1}/${maxSentences})")
                                ttsQueue.add(sentence)
                            }
                        }
                        // Start playback if not already
                        if (ttsQueue.isEmpty()) {
                            // Fallback: speak entire response if split failed
                            ttsQueue.add(finalResponse)
                        }
                        if (!isTTSPlaying) {
                            playNextTTS()
                        }
                        
                        // Call onComplete immediately with the final response
                        CoroutineScope(Dispatchers.Main).launch {
                            onComplete(finalResponse)
                        }
                        
                        // Wait for TTS to complete, then call onTTSComplete
                        CoroutineScope(Dispatchers.Main).launch {
                            while (ttsQueue.isNotEmpty() || isTTSPlaying) {
                                delay(100)
                                if (isCancelled.get()) break
                            }
                            if (!isCancelled.get()) {
                                Log.d(TAG, "Spatial TTS queue finished")
                                onTTSComplete()
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Spatial processing error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Spatial processing failed: ${e.message}")
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