package com.example.braillebridge2.ui

import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import coil.compose.AsyncImage
import com.example.braillebridge2.chat.LlmModelManager
import com.example.braillebridge2.core.*
import com.example.braillebridge2.core.SpeechHelper
import com.example.braillebridge2.viewmodel.MainViewModel

// Function to convert SVG to HTML with proper text wrapping
private fun convertSvgToWrappedHtml(svgContent: String): String {
    try {
        // Extract text content from SVG and convert to HTML with proper wrapping
        val textElements = Regex("""<text[^>]*>([^<]+)</text>""").findAll(svgContent)
        val brailleTexts = textElements.map { it.groupValues[1] }.toList()
        
        if (brailleTexts.isEmpty()) {
            // Fallback: embed original SVG so user can still view it
            val responsiveSvg = svgContent.replace("<svg", "<svg style=\"max-width:100%;height:auto;\"")
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <style>body{margin:0;padding:10px;background:white;}</style>
                </head>
                <body>
                $responsiveSvg
                </body>
                </html>
            """.trimIndent()
        }
        
        // Convert each line to HTML with proper wrapping
        val htmlLines = brailleTexts.mapIndexed { index, text ->
            """<p class="braille-line" data-line="$index">$text</p>"""
        }.joinToString("\n")
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { 
                        margin: 0; 
                        padding: 15px; 
                        background-color: white;
                        font-family: 'Courier New', monospace;
                        line-height: 1.8;
                        font-size: 18px;
                    }
                    .braille-line {
                        margin: 10px 0;
                        word-wrap: break-word;
                        word-break: break-all;
                        white-space: pre-wrap;
                        color: #000;
                        text-decoration: underline;
                        text-decoration-color: red;
                        text-decoration-thickness: 2px;
                    }
                    .braille-line:nth-child(even) {
                        background-color: #f9f9f9;
                        padding: 5px;
                        border-radius: 3px;
                    }
                </style>
            </head>
            <body>
                $htmlLines
            </body>
            </html>
        """.trimIndent()
    } catch (e: Exception) {
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: sans-serif; text-align: center; padding: 20px;">
                <p>Unable to load braille corrections</p>
            </body>
            </html>
        """.trimIndent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    state: AppState.Feedback,
    viewModel: MainViewModel,
    ttsHelper: TtsHelper,
    modelManager: LlmModelManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentItem = state.currentItem
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    
    // Handle mode changes and trigger appropriate actions
    LaunchedEffect(state.mode) {
        when (state.mode) {
            FeedbackMode.VIEWING -> {
                // Speak feedback only once when page first opened
                if (currentItem != null && !currentItem.spokenOnce) {
                    val fullFeedbackText = currentItem.getCurrentFeedback(state.language)
                    val cleanFeedbackText = if (fullFeedbackText.contains("Feedback:")) {
                        fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
                    } else {
                        fullFeedbackText.substringBefore("Braille Text:").trim()
                    }
                    val prefix = LocalizedStrings.getString(LocalizedStrings.StringKey.FEEDBACK_PREFIX, state.language)
                    ttsHelper.speak("$prefix: $cleanFeedbackText") {
                        // Mark as spoken after TTS completes
                        currentItem.spokenOnce = true
                    }
                }
            }
            
            FeedbackMode.AWAITING_COMMAND -> {
                val message = LocalizedStrings.getString(LocalizedStrings.StringKey.FEEDBACK_LISTENING_FOR_COMMAND, state.language)
                ttsHelper.speak(message) {
                    // Start speech recognition after TTS completes
                    SpeechHelper.startSpeechRecognition(
                        context = context,
                        language = state.language,
                        onResult = { recognizedText ->
                            viewModel.handleFeedbackVoiceCommand(recognizedText, ttsHelper, modelManager)
                            speechRecognizer = null
                        },
                        onError = {
                            val errorMessage = LocalizedStrings.getString(LocalizedStrings.StringKey.COMMAND_NOT_HEARD, state.language)
                            ttsHelper.speak(errorMessage)
                            viewModel.handleFeedbackVoiceCommand("", ttsHelper, modelManager) // Return to viewing mode
                            speechRecognizer = null
                        },
                        onStart = { recognizer ->
                            speechRecognizer = recognizer
                        }
                    )
                }
            }
            
            FeedbackMode.ASKING_QUESTION -> {
                val message = LocalizedStrings.getString(LocalizedStrings.StringKey.QUESTION_RECORDING_STARTED, state.language)
                ttsHelper.speak(message) {
                    // Start speech recognition after TTS completes
                    SpeechHelper.startSpeechRecognition(
                        context = context,
                        language = state.language,
                        onResult = { recognizedText ->
                            viewModel.onFeedbackQuestionRecordingResult(recognizedText, modelManager, ttsHelper)
                        },
                        onError = {
                            viewModel.onFeedbackQuestionRecordingError(state, ttsHelper)
                        },
                        onStart = { recognizer ->
                            speechRecognizer = recognizer
                        }
                    )
                }
            }
            
            FeedbackMode.GEMMA_RESPONDING -> {
                // Display responding indicator - handled in UI below
            }
            
            FeedbackMode.TRANSLATING -> {
                // Display translating indicator - handled in UI below
            }
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
                            FeedbackMode.VIEWING -> {
                                viewModel.onFeedbackGesture(GestureType.TAP, ttsHelper)
                            }
                            FeedbackMode.ASKING_QUESTION -> {
                                // Stop question recording (speech recognition)
                                SpeechHelper.stopSpeechRecognition(speechRecognizer)
                                speechRecognizer = null
                                viewModel.onFeedbackGesture(GestureType.TAP, ttsHelper)
                            }
                            FeedbackMode.GEMMA_RESPONDING -> {
                                // Cancel Gemma response
                                viewModel.onFeedbackGesture(GestureType.TAP, ttsHelper)
                            }
                            else -> { /* No action for other modes */ }
                        }
                    },
                    onLongPress = {
                        when (state.mode) {
                            FeedbackMode.VIEWING -> {
                                viewModel.onFeedbackGesture(GestureType.LONG_PRESS, ttsHelper)
                            }
                            FeedbackMode.ASKING_QUESTION -> {
                                // Stop question recording and return to viewing
                                SpeechHelper.stopSpeechRecognition(speechRecognizer)
                                speechRecognizer = null
                                viewModel.onFeedbackGesture(GestureType.TAP, ttsHelper)
                            }
                            FeedbackMode.GEMMA_RESPONDING -> {
                                // Cancel Gemma response
                                viewModel.onFeedbackGesture(GestureType.TAP, ttsHelper)
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
            // Header with feedback indicator and item info
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
                        text = "Feedback ${currentItem?.index ?: 1}",
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
            
            // Feedback text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                if (currentItem != null) {
                    // Extract just the feedback text after "Feedback:"
                    val fullFeedbackText = currentItem.getCurrentFeedback(state.language)
                    val feedbackText = if (fullFeedbackText.contains("Feedback:")) {
                        fullFeedbackText.substringAfter("Feedback:").substringBefore("Braille Text:").trim()
                    } else {
                        fullFeedbackText.substringBefore("Braille Text:").trim()
                    }
                    
                    Text(
                        text = feedbackText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(20.dp),
                        textAlign = TextAlign.Start
                    )
                } else {
                    Text(
                        text = "No feedback available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // Braille rendering - only show if braille SVG exists
            if (currentItem?.brailleSvg != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp), // Same height as homework screen
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    FeedbackBrailleView(
                        feedbackItem = currentItem,
                        language = state.language.name,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            // Status and Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.mode) {
                        FeedbackMode.AWAITING_COMMAND -> MaterialTheme.colorScheme.primaryContainer
                        FeedbackMode.ASKING_QUESTION -> MaterialTheme.colorScheme.tertiaryContainer
                        FeedbackMode.GEMMA_RESPONDING -> MaterialTheme.colorScheme.inversePrimary
                        FeedbackMode.TRANSLATING -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                    contentColor = when (state.mode) {
                        FeedbackMode.AWAITING_COMMAND -> MaterialTheme.colorScheme.onPrimaryContainer
                        FeedbackMode.ASKING_QUESTION -> MaterialTheme.colorScheme.onTertiaryContainer
                        FeedbackMode.GEMMA_RESPONDING -> MaterialTheme.colorScheme.onSurface
                        FeedbackMode.TRANSLATING -> MaterialTheme.colorScheme.onSecondaryContainer
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
                        FeedbackMode.AWAITING_COMMAND -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Listening"
                                )
                                Text(
                                    text = LocalizedStrings.getString(LocalizedStrings.StringKey.FEEDBACK_LISTENING_FOR_COMMAND, state.language),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        FeedbackMode.ASKING_QUESTION -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QuestionAnswer,
                                    contentDescription = "Recording question"
                                )
                                Text(
                                    text = LocalizedStrings.getString(LocalizedStrings.StringKey.QUESTION_RECORDING_STARTED, state.language),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        FeedbackMode.GEMMA_RESPONDING -> {
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
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        FeedbackMode.TRANSLATING -> {
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
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        else -> {
                            // VIEWING mode - show instructions
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TouchApp,
                                        contentDescription = "Touch"
                                    )
                                    Text(
                                        text = "Tap to repeat feedback",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PanTool,
                                        contentDescription = "Hold"
                                    )
                                    Text(
                                        text = "Hold to speak command (switch, repeat, ask)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom braille view for feedback that displays the SVG with red underlines
 */
@Composable
private fun FeedbackBrailleView(
    feedbackItem: FeedbackItem,
    language: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header
            Text(
                text = "Braille with Corrections (${language.lowercase().replaceFirstChar { it.titlecase() }})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // SVG display with WebView for better rendering
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (feedbackItem.brailleSvg != null && feedbackItem.brailleSvg!!.exists()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                setBackgroundColor(android.graphics.Color.WHITE)
                            }
                        },
                        update = { webView ->
                            try {
                                val originalSvgContent = feedbackItem.brailleSvg!!.readText()
                                val html = convertSvgToWrappedHtml(originalSvgContent)
                                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            } catch (e: Exception) {
                                val errorHtml = """
                                    <!DOCTYPE html>
                                    <html>
                                    <body style="font-family: sans-serif; text-align: center; padding: 20px;">
                                        <p>Unable to load braille corrections</p>
                                    </body>
                                    </html>
                                """.trimIndent()
                                webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Placeholder when SVG is not available
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Braille placeholder",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Braille Corrections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}