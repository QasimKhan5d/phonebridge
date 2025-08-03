package com.example.braillebridge2.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.launch

private const val TAG = "ChatScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modelManager: LlmModelManager,
    onSpeakText: (String) -> Unit,
    isTtsReady: Boolean,
    enableTts: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var currentMessage by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }
    
    // Handle TTS for agent responses
    LaunchedEffect(uiState.messages) {
        if (enableTts && isTtsReady) {
            val lastMessage = uiState.messages.lastOrNull()
            if (lastMessage is ChatMessageText && 
                lastMessage.side == ChatSide.AGENT && 
                lastMessage.latencyMs >= 0) { // Only speak completed messages
                onSpeakText(lastMessage.content)
            }
        }
    }
    
    // Permission launcher for audio recording
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechRecognition(
                context = context,
                onResult = { recognizedText ->
                    currentMessage = recognizedText
                    isRecording = false
                },
                onError = {
                    isRecording = false
                },
                onStart = {
                    speechRecognizer = it
                    isRecording = true
                }
            )
        }
    }
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Load and process image
            scope.launch {
                try {
                    val bitmap = loadBitmapFromUri(context, uri)
                    bitmap?.let { bmp ->

                        
                        val inputText = currentMessage.ifEmpty { "What do you see in this image?" }
                        currentMessage = ""
                        
                        // Add user image message
                        viewModel.addMessage(
                            ChatMessageImage(
                                bitmap = bmp,
                                imageBitMap = bmp.asImageBitmap(),
                                side = ChatSide.USER
                            )
                        )
                        
                        // Generate response with image
                        viewModel.generateResponse(
                            modelManager = modelManager,
                            input = inputText,
                            images = listOf(bmp),
                            onError = {}
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image: ${e.message}")
                }
            }
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }
        }
        
        // Input area
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Image picker button
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !uiState.inProgress,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Add Image",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                // Text input field
                OutlinedTextField(
                    value = currentMessage,
                    onValueChange = { currentMessage = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    enabled = !uiState.inProgress,
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                
                // Voice input button
                IconButton(
                    onClick = {
                        if (isRecording) {
                            speechRecognizer?.stopListening()
                            isRecording = false
                        } else {
                            when (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            )) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    startSpeechRecognition(
                                        context = context,
                                        onResult = { recognizedText ->
                                            currentMessage = recognizedText
                                            isRecording = false
                                        },
                                        onError = {
                                            isRecording = false
                                        },
                                        onStart = {
                                            speechRecognizer = it
                                            isRecording = true
                                        }
                                    )
                                }
                                else -> {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    enabled = !uiState.inProgress,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isRecording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.secondary,
                            CircleShape
                        )
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop Recording" else "Voice Input",
                        tint = MaterialTheme.colorScheme.onSecondary
                    )
                }
                
                // Send button
                IconButton(
                    onClick = {
                        if (currentMessage.isNotBlank() && !uiState.inProgress) {
                            val messageToSend = currentMessage
                            currentMessage = ""
                            

                            
                            // Add user message
                            viewModel.addMessage(
                                ChatMessageText(
                                    content = messageToSend,
                                    side = ChatSide.USER
                                )
                            )
                            
                            // Generate response (handles inProgress state internally)
                            viewModel.generateResponse(
                                modelManager = modelManager,
                                input = messageToSend,
                                onError = {}
                            )
                        }
                    },
                    enabled = !uiState.inProgress && currentMessage.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (currentMessage.isNotBlank() && !uiState.inProgress)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (currentMessage.isNotBlank() && !uiState.inProgress)
                            MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    when (message) {
        is ChatMessageText -> TextMessageBubble(message)
        is ChatMessageImage -> ImageMessageBubble(message)
        is ChatMessageLoading -> LoadingMessageBubble()
        is ChatMessageInfo -> InfoMessageBubble(message)
        is ChatMessageAudioClip -> AudioMessageBubble(message)
    }
}

@Composable
fun TextMessageBubble(message: ChatMessageText) {
    val isUser = message.side == ChatSide.USER
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun ImageMessageBubble(message: ChatMessageImage) {
    val isUser = message.side == ChatSide.USER
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Image(
                bitmap = message.imageBitMap,
                contentDescription = "Shared image",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun LoadingMessageBubble() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Thinking...",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InfoMessageBubble(message: ChatMessageInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = message.content,
            modifier = Modifier.padding(12.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun AudioMessageBubble(message: ChatMessageAudioClip) {
    val isUser = message.side == ChatSide.USER
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Audio message",
                    tint = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Audio message",
                    color = if (isUser) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}