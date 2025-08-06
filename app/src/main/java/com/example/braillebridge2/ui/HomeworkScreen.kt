package com.example.braillebridge2.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.braillebridge2.core.*
import com.example.braillebridge2.core.SpeechHelper
import com.example.braillebridge2.chat.LlmModelManager
import com.example.braillebridge2.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkScreen(
    state: AppState.Homework,
    viewModel: MainViewModel,
    ttsHelper: TtsHelper,
    modelManager: LlmModelManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentItem = state.currentItem
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    
    // Camera launcher for photo answers
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handlePhotoCaptureResult(result.resultCode, result.data, null, ttsHelper)
    }
    
    // Permission launcher for camera
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val cameraIntent = viewModel.getCameraIntent()
            if (cameraIntent != null) {
                cameraLauncher.launch(cameraIntent)
            }
        }
    }
    
    // Permission launcher for audio recording
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Audio permission is handled by the voice recorder helper
        }
    }
    
    // Track previous mode to detect transitions from RECORDING_VOICE
    var previousMode by remember { mutableStateOf(state.mode) }
    
    // Detect mode transitions to set flag when returning from voice recording
    LaunchedEffect(state.mode) {
        if (previousMode == HomeworkMode.RECORDING_VOICE && state.mode == HomeworkMode.VIEWING) {
            Log.d("HomeworkScreen", "Detected transition from RECORDING_VOICE to VIEWING - suppressing TTS")
            // Add a short delay, then allow TTS again
            kotlinx.coroutines.delay(500)
            Log.d("HomeworkScreen", "Delay complete, TTS can resume on next state change")
        }
        previousMode = state.mode
    }
    
    // Announce question when screen loads or item changes (only in VIEWING mode)
    LaunchedEffect(state.currentIndex, state.language, state.mode) {
        // Only announce if we're in VIEWING mode AND not transitioning from RECORDING_VOICE
        if (state.mode == HomeworkMode.VIEWING && previousMode != HomeworkMode.RECORDING_VOICE) {
            currentItem?.let { item ->
                val questionText = item.getCurrentQuestion(state.language)
                val instructions = LocalizedStrings.getString(LocalizedStrings.StringKey.HOMEWORK_INSTRUCTIONS, state.language)
                val announcement = "Question ${item.index}. ${questionText}. $instructions"
                Log.i("HomeworkScreen", "About to announce: '$announcement'")
                ttsHelper.speak(announcement)
            }
        } else if (state.mode == HomeworkMode.VIEWING && previousMode == HomeworkMode.RECORDING_VOICE) {
            Log.d("HomeworkScreen", "Suppressing TTS announcement due to voice recording completion")
        }
    }
    
    if (currentItem == null) {
        // Error state
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Question not found",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.returnToHome(context) }
                    ) {
                        Text("Return Home")
                    }
                }
            }
        }
        return
    }
    
    // State for TTS announcements and actions based on mode
    LaunchedEffect(state.mode) {
        when (state.mode) {
            HomeworkMode.RECORDING_VOICE -> {
                // Check audio permission first
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                    PackageManager.PERMISSION_GRANTED -> {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.RECORDING_STARTED_TAP_TO_STOP, state.language)
                        ttsHelper.speak(message) {
                            // TTS completed - notify ViewModel to start actual recording
                            Log.d("HomeworkScreen", "Recording TTS completed, notifying ViewModel")
                            viewModel.onRecordingTtsComplete()
                        }
                    }
                    else -> {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            HomeworkMode.AWAITING_COMMAND -> {
                // Check microphone permission first before starting speech recognition
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                    PackageManager.PERMISSION_GRANTED -> {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.LISTENING_FOR_COMMAND, state.language)
                        ttsHelper.speak(message) {
                            // Add a small delay to ensure TTS audio output has fully finished
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                // Start speech recognition after TTS completes and brief delay
                                SpeechHelper.startSpeechRecognition(
                                    context = context,
                                    language = state.language,
                                    onResult = { recognizedText ->
                                        viewModel.handleVoiceCommand(recognizedText, ttsHelper, modelManager)
                                        speechRecognizer = null
                                    },
                                    onError = {
                                        val errorMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_HEARD, state.language)
                                        ttsHelper.speak(errorMessage)
                                        viewModel.handleVoiceCommand("", ttsHelper, modelManager) // Return to viewing mode
                                        speechRecognizer = null
                                    },
                                    onStart = { recognizer ->
                                        speechRecognizer = recognizer
                                    }
                                )
                            }, 500) // 500ms delay to ensure audio output finishes
                        }
                    }
                    else -> {
                        // Request microphone permission if not granted
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            HomeworkMode.ASKING_QUESTION -> {
                // Check microphone permission first before starting speech recognition
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                    PackageManager.PERMISSION_GRANTED -> {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.QUESTION_RECORDING_STARTED, state.language)
                        ttsHelper.speak(message) {
                            // Add a small delay to ensure TTS audio output has fully finished
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                // Start speech recognition after TTS completes and brief delay
                                SpeechHelper.startSpeechRecognition(
                                    context = context,
                                    language = state.language,
                                    onResult = { recognizedText ->
                                        viewModel.onQuestionRecordingResult(recognizedText, modelManager, ttsHelper)
                                    },
                                    onError = {
                                        viewModel.onQuestionRecordingError(state, ttsHelper)
                                    },
                                    onStart = { recognizer ->
                                        speechRecognizer = recognizer
                                    }
                                )
                            }, 500) // 500ms delay to ensure audio output finishes
                        }
                    }
                    else -> {
                        // Request microphone permission if not granted
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            HomeworkMode.GEMMA_RESPONDING -> {
                // Show responding UI - this will be handled by the UI layout
            }
            HomeworkMode.TRANSLATING -> {
                // Translation in progress - TTS already handled in ViewModel
            }
            HomeworkMode.LISTENING_AUDIO -> {
                // Audio playback in progress - no TTS needed
            }
            HomeworkMode.RECORDING_PHOTO -> {
                // Check camera permission first
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                    PackageManager.PERMISSION_GRANTED -> {
                        val message = LocalizedStrings.getString(LocalizedStrings.StringKey.CAMERA_OPENED_FOR_PHOTO, state.language)
                        ttsHelper.speak(message)
                        val cameraIntent = viewModel.getCameraIntent()
                        if (cameraIntent != null) {
                            cameraLauncher.launch(cameraIntent)
                        }
                    }
                    else -> {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
            else -> { /* No announcement needed */ }
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(state.mode) {
                // Full-screen gesture detection
                detectTapGestures(
                    onTap = {
                        when (state.mode) {
                            HomeworkMode.VIEWING -> {
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.RECORDING_VOICE -> {
                                // Stop voice recording
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.ASKING_QUESTION -> {
                                // Stop question recording (speech recognition)
                                SpeechHelper.stopSpeechRecognition(speechRecognizer)
                                speechRecognizer = null
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.GEMMA_RESPONDING -> {
                                // Cancel Gemma response
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.TRANSLATING -> {
                                // Cannot interact during translation
                            }
                            HomeworkMode.LISTENING_AUDIO -> {
                                // Allow stopping audio playback
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            else -> { /* No action for other modes */ }
                        }
                    },
                    onDoubleTap = {
                        when (state.mode) {
                            HomeworkMode.VIEWING -> {
                                viewModel.onHomeworkGesture(GestureType.DOUBLE_TAP, ttsHelper)
                            }
                            HomeworkMode.RECORDING_VOICE -> {
                                // Stop voice recording - treat double-tap as single tap
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.ASKING_QUESTION -> {
                                // Stop question recording (speech recognition)
                                SpeechHelper.stopSpeechRecognition(speechRecognizer)
                                speechRecognizer = null
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.GEMMA_RESPONDING -> {
                                // Cancel Gemma response
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.TRANSLATING -> {
                                // Cannot interact during translation
                            }
                            HomeworkMode.LISTENING_AUDIO -> {
                                // Allow stopping audio playback
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            else -> { /* No action for other modes */ }
                        }
                    },
                    onLongPress = {
                        when (state.mode) {
                            HomeworkMode.VIEWING -> {
                                viewModel.onHomeworkGesture(GestureType.LONG_PRESS, ttsHelper)
                            }
                            HomeworkMode.RECORDING_VOICE -> {
                                // Stop voice recording
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.ASKING_QUESTION -> {
                                // Stop question recording (speech recognition)
                                SpeechHelper.stopSpeechRecognition(speechRecognizer)
                                speechRecognizer = null
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.GEMMA_RESPONDING -> {
                                // Cancel Gemma response
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            HomeworkMode.TRANSLATING -> {
                                // Cannot interact during translation
                            }
                            HomeworkMode.LISTENING_AUDIO -> {
                                // Allow stopping audio playback
                                viewModel.onHomeworkGesture(GestureType.TAP, ttsHelper)
                            }
                            else -> { /* No action for other modes */ }
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Header with question number and progress
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Question ${currentItem.index}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Text(
                    text = "${state.currentIndex + 1} of ${state.pack.size}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        // Diagram area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (currentItem.diagram.exists()) {
                    AsyncImage(
                        model = currentItem.diagram,
                        contentDescription = "Science diagram",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Placeholder for diagram
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Diagram placeholder",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Science Diagram",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        
        // Question text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text(
                text = currentItem.getCurrentQuestion(state.language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(20.dp),
                textAlign = TextAlign.Start
            )
        }
        
        // Braille rendering
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp), // Increased height for better readability
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            BrailleSvgView(
                svgFile = currentItem.getCurrentBrailleSvg(state.language),
                language = state.language.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Status and Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (state.mode) {
                    HomeworkMode.RECORDING_VOICE -> MaterialTheme.colorScheme.errorContainer
                    HomeworkMode.AWAITING_COMMAND -> MaterialTheme.colorScheme.primaryContainer
                    HomeworkMode.RECORDING_PHOTO -> MaterialTheme.colorScheme.secondaryContainer
                    HomeworkMode.ASKING_QUESTION -> MaterialTheme.colorScheme.tertiaryContainer
                    HomeworkMode.GEMMA_RESPONDING -> MaterialTheme.colorScheme.inversePrimary
                    HomeworkMode.TRANSLATING -> MaterialTheme.colorScheme.tertiaryContainer
                    HomeworkMode.LISTENING_AUDIO -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                contentColor = when (state.mode) {
                    HomeworkMode.RECORDING_VOICE -> MaterialTheme.colorScheme.onErrorContainer
                    HomeworkMode.AWAITING_COMMAND -> MaterialTheme.colorScheme.onPrimaryContainer
                    HomeworkMode.RECORDING_PHOTO -> MaterialTheme.colorScheme.onSecondaryContainer
                    HomeworkMode.ASKING_QUESTION -> MaterialTheme.colorScheme.onTertiaryContainer
                    HomeworkMode.GEMMA_RESPONDING -> MaterialTheme.colorScheme.onSurface
                    HomeworkMode.TRANSLATING -> MaterialTheme.colorScheme.onTertiaryContainer
                    HomeworkMode.LISTENING_AUDIO -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Current mode status
                when (state.mode) {
                    HomeworkMode.RECORDING_VOICE -> {
                        Text(
                            text = "🎤 Recording Voice Answer...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap again to stop recording",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HomeworkMode.AWAITING_COMMAND -> {
                        Text(
                            text = "🎧 Listening for Command...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Say: 'listen', 'switch', 'repeat', or 'ask'",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HomeworkMode.RECORDING_PHOTO -> {
                        Text(
                            text = "📷 Camera Mode Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Take a photo of your answer",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HomeworkMode.ASKING_QUESTION -> {
                        Text(
                            text = "❓ Recording Question...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ask your question about the image. Tap to stop.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HomeworkMode.GEMMA_RESPONDING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = LocalizedStrings.getString(LocalizedStrings.StringKey.GEMMA_RESPONDING, state.language),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    HomeworkMode.TRANSLATING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = LocalizedStrings.getString(LocalizedStrings.StringKey.TRANSLATING_FEEDBACK, state.language),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "Instructions:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Tap anywhere to answer by voice",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "• Double tap anywhere to answer by photo",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "• Hold and release to speak commands",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "  (say: 'listen', 'switch', or 'repeat')",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Navigation buttons (for testing - will be hidden in final version)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { viewModel.returnToHome(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Return Home")
            }
            
            if (state.hasNextItem) {
                Button(
                    onClick = { viewModel.moveToNextHomeworkItem(ttsHelper, modelManager) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next Question")
                }
            }
        }
    }
    }
}