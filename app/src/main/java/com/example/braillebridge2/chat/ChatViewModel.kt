package com.example.braillebridge2.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import android.util.Log

private const val TAG = "ChatViewModel"

data class ChatUiState(
    /** Indicates whether the runtime is currently processing a message. */
    val inProgress: Boolean = false,
    
    /** Indicates whether the model is preparing (before outputting any result and after initializing). */
    val preparing: Boolean = false,
    
    /** List of chat messages in conversation order. */
    val messages: List<ChatMessage> = listOf(),
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    
    fun addMessage(message: ChatMessage) {
        val newMessages = _uiState.value.messages.toMutableList()
        newMessages.add(message)
        _uiState.update { _uiState.value.copy(messages = newMessages) }
    }
    
    fun removeLastMessage() {
        val newMessages = _uiState.value.messages.toMutableList()
        if (newMessages.isNotEmpty()) {
            newMessages.removeAt(newMessages.size - 1)
        }
        _uiState.update { _uiState.value.copy(messages = newMessages) }
    }
    
    fun clearAllMessages() {
        _uiState.update { _uiState.value.copy(messages = listOf()) }
    }
    
    fun getLastMessage(): ChatMessage? {
        return _uiState.value.messages.lastOrNull()
    }
    
    fun updateLastTextMessageContentIncrementally(
        partialContent: String,
        latencyMs: Float,
    ) {
        val newMessages = _uiState.value.messages.toMutableList()
        if (newMessages.isNotEmpty()) {
            val lastMessage = newMessages.last()
            if (lastMessage is ChatMessageText) {
                val newContent = "${lastMessage.content}${partialContent}"
                val newLastMessage = ChatMessageText(
                    content = newContent,
                    side = lastMessage.side,
                    latencyMs = latencyMs,
                )
                newMessages.removeAt(newMessages.size - 1)
                newMessages.add(newLastMessage)
            }
        }
        _uiState.update { _uiState.value.copy(messages = newMessages) }
    }
    
    fun setInProgress(inProgress: Boolean) {
        _uiState.update { _uiState.value.copy(inProgress = inProgress) }
    }
    
    fun setPreparing(preparing: Boolean) {
        _uiState.update { _uiState.value.copy(preparing = preparing) }
    }
    
    fun generateResponse(
        modelManager: LlmModelManager,
        input: String,
        images: List<Bitmap> = listOf(),
        onError: () -> Unit,
    ) {
        // Wait for model to be ready using gallery app pattern
        if (!modelManager.isReady()) {
            Log.e(TAG, "Model manager not ready")
            onError()
            return
        }
        
        // Prevent double sends - check if already in progress
        if (_uiState.value.inProgress) {
            Log.w(TAG, "Already generating response, ignoring duplicate request")
            return
        }
        
        viewModelScope.launch(Dispatchers.Default) {
            setInProgress(true)
            setPreparing(true)
            
            // Add loading message
            addMessage(ChatMessageLoading())
            
            // Wait for model instance to be available (gallery app pattern)
            while (modelManager.instance == null) {
                delay(100)
            }
            delay(500) // Additional delay like gallery app
            
            try {
                val llmInstance = modelManager.instance!!
                val llmSession = llmInstance.session
                // Build conversation context from message history (current user message already added)
                val conversationContext = buildConversationContext()
                val fullInput = if (conversationContext.isNotEmpty()) {
                    conversationContext.trimEnd() // Remove trailing newline
                } else {
                    "User: $input" // Fallback for first message
                }
                
                Log.d(TAG, "Sending to LLM: $fullInput")
                
                // Add text query
                if (fullInput.trim().isNotEmpty()) {
                    llmSession.addQueryChunk(fullInput)
                }
                
                // Add images if any
                for (image in images) {
                    llmSession.addImage(BitmapImageBuilder(image).build())
                }
                
                var firstRun = true
                val start = System.currentTimeMillis()
                
                // Start streaming inference
                llmSession.generateResponseAsync { partialResult, done ->
                    if (firstRun) {
                        setPreparing(false)
                        firstRun = false
                        
                        // Remove loading message and add empty agent message
                        removeLastMessage()
                        addMessage(
                            ChatMessageText(
                                content = "",
                                side = ChatSide.AGENT
                            )
                        )
                    }
                    
                    // Update the streaming content
                    val latencyMs = if (done) (System.currentTimeMillis() - start).toFloat() else -1f
                    updateLastTextMessageContentIncrementally(partialResult, latencyMs)
                    
                    if (done) {
                        setInProgress(false)
                        Log.d(TAG, "Response generation completed in ${System.currentTimeMillis() - start}ms")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference: ${e.message}")
                setPreparing(false)
                setInProgress(false)
                removeLastMessage() // Remove loading message
                addMessage(
                    ChatMessageText(
                        content = "Error: ${e.message}",
                        side = ChatSide.AGENT
                    )
                )
                onError()
            }
        }
    }
    
    private fun buildConversationContext(): String {
        val messages = _uiState.value.messages
        val context = StringBuilder()
        
        // Filter out loading messages and only include recent conversation
        val filteredMessages = messages.filter { it !is ChatMessageLoading }
        val recentMessages = filteredMessages.takeLast(6) // Last 6 messages (3 exchanges max)
        
        Log.d(TAG, "Building context from ${recentMessages.size} messages")
        
        for (message in recentMessages) {
            when (message) {
                is ChatMessageText -> {
                    when (message.side) {
                        ChatSide.USER -> {
                            context.append("User: ${message.content}\n")
                            Log.d(TAG, "Added user message: ${message.content}")
                        }
                        ChatSide.AGENT -> {
                            // Only include first 50 chars of assistant response for context
                            val shortResponse = if (message.content.length > 50) {
                                message.content.take(50) + "..."
                            } else {
                                message.content
                            }
                            context.append("Assistant: $shortResponse\n")
                            Log.d(TAG, "Added assistant context: $shortResponse")
                        }
                        else -> {} // Skip system messages
                    }
                }
                is ChatMessageImage -> {
                    if (message.side == ChatSide.USER) {
                        context.append("User: [Image attached]\n")
                        Log.d(TAG, "Added image context")
                    }
                }
                is ChatMessageAudioClip -> {
                    if (message.side == ChatSide.USER) {
                        context.append("User: [Audio message]\n")
                        Log.d(TAG, "Added audio context")
                    }
                }
                else -> {} // Skip loading and other message types
            }
        }
        
        val result = context.toString().trim()
        Log.d(TAG, "Final context length: ${result.length}")
        return result
    }
}