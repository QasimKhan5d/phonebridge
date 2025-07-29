package com.example.braillebridge2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.braillebridge2.ui.theme.BrailleBridge2Theme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private lateinit var modelPath: String
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Use external files directory like the gallery app does
        modelPath = File(getExternalFilesDir(null), "gemma-3n-E2B-it-int4.task").absolutePath
        
        // Initialize TTS
        textToSpeech = TextToSpeech(this, this)
        
        // Initialize the LLM inference task
        initializeLlmInference()
        
        setContent {
            BrailleBridge2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LlmInferenceScreen(
                        llmInference = llmInference,
                        llmSession = llmSession,
                        modelPath = modelPath,
                        onSpeakText = { text -> speakText(text) },
                        isTtsReady = isTtsInitialized,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            } else {
                isTtsInitialized = true
                Log.i("TTS", "TextToSpeech initialized successfully")
            }
        } else {
            Log.e("TTS", "TextToSpeech initialization failed")
        }
    }
    
    private fun speakText(text: String) {
        if (isTtsInitialized && textToSpeech != null) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.i("TTS", "Speaking: ${text.take(50)}...")
        }
    }
    
    private fun initializeLlmInference() {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.w("LlmInference", "Model file not found at: $modelPath")
                return
            }
            
            // Set the configuration options for the LLM Inference task with vision support
            val taskOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTopK(64)
                .setMaxNumImages(1) // Enable single image support for Gemma-3n
                .build()
            
            // Create an instance of the LLM Inference task
            llmInference = LlmInference.createFromOptions(this, taskOptions)
            
            // Create a session for streaming with vision modality enabled
            llmSession = LlmInferenceSession.createFromOptions(
                llmInference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(64)
                    .setTopP(0.8f)
                    .setTemperature(1.0f)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(true) // Enable vision support
                            .build()
                    )
                    .build()
            )
            
            Log.i("LlmInference", "LLM Inference and Session initialized with vision support")
        } catch (e: Exception) {
            Log.e("LlmInference", "Failed to initialize LLM Inference: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        llmSession?.close()
        llmInference?.close()
    }
}

// Helper function to load bitmap from URI
private suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.e("ImagePicker", "Failed to load image: ${e.message}")
            null
        }
    }
}

// Helper function to load bitmap from file with proper rotation
private suspend fun loadBitmapFromFile(file: File): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            
            // Load the bitmap
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            
            // Read EXIF data to get orientation
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            
            // Determine rotation angle based on EXIF orientation
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            
            // Apply rotation if needed
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply {
                    postRotate(rotationDegrees)
                }
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                bitmap.recycle() // Free original bitmap memory
                Log.i("Camera", "Rotated image by $rotationDegrees degrees")
                rotatedBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("Camera", "Failed to load image from file: ${e.message}")
            null
        }
    }
}

// Helper function to create temporary file for camera
private fun createImageFile(context: Context): File {
    val storageDir = context.getExternalFilesDir(null)
    return File.createTempFile(
        "JPEG_${System.currentTimeMillis()}_",
        ".jpg",
        storageDir
    )
}

@Composable
fun LlmInferenceScreen(
    llmInference: LlmInference?,
    llmSession: LlmInferenceSession?,
    modelPath: String,
    onSpeakText: (String) -> Unit,
    isTtsReady: Boolean,
    modifier: Modifier = Modifier
) {
    var inputPrompt by remember { mutableStateOf("Describe what you see in this image.") }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var streamingResponse by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var enableTts by remember { mutableStateOf(true) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var cameraImageFile by remember { mutableStateOf<File?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var lastInputMethod by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val bitmap = loadBitmapFromUri(context, it)
                selectedImage = bitmap
                bitmap?.let { bmp ->
                    Log.i("ImagePicker", "Image selected: ${bmp.width}x${bmp.height}")
                }
            }
        }
    }
    
    // Camera launcher - must be declared first so it can be referenced
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            cameraImageFile?.let { file ->
                scope.launch {
                    val bitmap = loadBitmapFromFile(file)
                    selectedImage = bitmap
                    bitmap?.let { bmp ->
                        Log.i("Camera", "Photo taken: ${bmp.width}x${bmp.height}")
                    }
                }
            }
        } else {
            Log.w("Camera", "Photo capture failed or was cancelled")
        }
    }
    
    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, now launch camera
            try {
                val imageFile = createImageFile(context)
                cameraImageFile = imageFile
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    imageFile
                )
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Log.e("Camera", "Failed to launch camera after permission granted: ${e.message}")
            }
        } else {
            Log.w("Camera", "Camera permission denied")
        }
    }
    
    // Speech recognition launcher
    val speechRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false // Reset listening state
        
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            spokenText?.let { results ->
                if (results.isNotEmpty()) {
                    val recognizedText = results[0]
                    Log.i("Speech", "Recognized text: $recognizedText")
                    
                    // Update the input prompt with recognized text
                    inputPrompt = recognizedText
                    lastInputMethod = "🎤 Voice"
                    
                    // Automatically generate response
                    if (llmInference != null && recognizedText.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = ""
                            
                            try {
                                // Move heavy LLM processing to background thread
                                val result = withContext(Dispatchers.IO) {
                                    if (selectedImage != null) {
                                        // For response with image, we need to use session
                                        llmSession?.addQueryChunk(recognizedText)
                                        selectedImage?.let { bitmap ->
                                            llmSession?.addImage(BitmapImageBuilder(bitmap).build())
                                        }
                                        llmSession?.generateResponse() ?: "Error: Session not available"
                                    } else {
                                        // Text-only response
                                        llmInference.generateResponse(recognizedText)
                                    }
                                }
                                
                                // Back on main thread for UI updates
                                response = result
                                Log.i("LlmInference", "Voice result: $result")
                                
                                // Speak the response if TTS is enabled
                                if (enableTts && isTtsReady) {
                                    onSpeakText(result)
                                }
                            } catch (e: Exception) {
                                response = "Error: ${e.message}"
                                Log.e("LlmInference", "Error generating voice response: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }
        } else {
            Log.w("Speech", "Speech recognition cancelled or failed")
        }
    }
    
    // Microphone permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, launch speech recognition
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            try {
                speechRecognitionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("Speech", "Speech recognition not available: ${e.message}")
                isListening = false
            }
        } else {
            Log.w("Speech", "Microphone permission denied")
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Braille Bridge LLM Inference",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        // Model status
        if (llmInference == null || llmSession == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "LLM Inference not initialized",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Model path: $modelPath",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "✓ LLM Inference Ready (with Vision)",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isTtsReady) "🔊 TTS Ready" else "⏳ TTS Initializing...",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp
                    )
                }
            }
        }
        
        // Input Modalities Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🚀 Multimodal AI Assistant",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "📝 Text • 🖼️ Images • 🎤 Voice • 🔊 Speech",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // TTS Controls
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Text-to-Speech",
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = enableTts,
                    onCheckedChange = { enableTts = it },
                    enabled = isTtsReady
                )
            }
        }
        
        // Image Input Section
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Image Input (Optional)",
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Select from Gallery button
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Select Image")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gallery")
                    }
                    
                    // Take Photo button
                    OutlinedButton(
                        onClick = {
                            // Check camera permission first
                            when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    // Permission already granted, launch camera
                                    try {
                                        val imageFile = createImageFile(context)
                                        cameraImageFile = imageFile
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            imageFile
                                        )
                                        cameraLauncher.launch(uri)
                                    } catch (e: Exception) {
                                        Log.e("Camera", "Failed to launch camera: ${e.message}")
                                    }
                                }
                                else -> {
                                    // Request camera permission
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📸 Camera")
                    }
                }
                
                // Voice Input button
                OutlinedButton(
                    onClick = {
                        // Check microphone permission first
                        when (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            PackageManager.PERMISSION_GRANTED -> {
                                // Permission already granted, launch speech recognition
                                isListening = true
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question...")
                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                }
                                
                                try {
                                    speechRecognitionLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Log.e("Speech", "Speech recognition not available: ${e.message}")
                                    isListening = false
                                }
                            }
                            else -> {
                                // Request microphone permission
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isStreaming && !isListening && llmInference != null
                ) {
                    if (isListening) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text("🎧 Listening...")
                        }
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Voice Input")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🎤 Speak Your Question")
                    }
                }
                
                // Display selected image
                selectedImage?.let { bitmap ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Selected image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { 
                                    selectedImage = null
                                    cameraImageFile = null
                                },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove image",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Input section
        OutlinedTextField(
            value = inputPrompt,
            onValueChange = { inputPrompt = it },
            label = { Text("Enter your prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Generate Response button
            Button(
                onClick = {
                    if (llmInference != null && inputPrompt.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = ""
                            lastInputMethod = if (selectedImage != null) "📝 Text + 🖼️ Image" else "📝 Text"
                            
                            try {
                                // Move heavy LLM processing to background thread
                                val result = withContext(Dispatchers.IO) {
                                    if (selectedImage != null) {
                                        // For single response with image, we need to use session
                                        llmSession?.addQueryChunk(inputPrompt)
                                        selectedImage?.let { bitmap ->
                                            llmSession?.addImage(BitmapImageBuilder(bitmap).build())
                                        }
                                        llmSession?.generateResponse() ?: "Error: Session not available"
                                    } else {
                                        // Text-only response
                                        llmInference.generateResponse(inputPrompt)
                                    }
                                }
                                
                                // Back on main thread for UI updates
                                response = result
                                Log.i("LlmInference", "result: $result")
                                
                                // Speak the response if TTS is enabled
                                if (enableTts && isTtsReady) {
                                    onSpeakText(result)
                                }
                            } catch (e: Exception) {
                                response = "Error: ${e.message}"
                                Log.e("LlmInference", "Error generating response: ${e.message}")
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = !isLoading && !isStreaming && llmInference != null,
                modifier = Modifier.weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Generate Response")
                }
            }
            
            // Stream Response button
            Button(
                onClick = {
                    if (llmSession != null && inputPrompt.isNotBlank()) {
                        scope.launch {
                            isStreaming = true
                            streamingResponse = ""
                            var fullResponse = ""
                            lastInputMethod = if (selectedImage != null) "⚡ Stream + 🖼️ Image" else "⚡ Stream"
                            
                            try {
                                // Move session setup to background thread
                                withContext(Dispatchers.IO) {
                                    // Add the query chunk to the session (text first as recommended)
                                    llmSession.addQueryChunk(inputPrompt)
                                    
                                    // Add image if selected
                                    selectedImage?.let { bitmap ->
                                        llmSession.addImage(BitmapImageBuilder(bitmap).build())
                                        Log.i("LlmInference", "Added image to session: ${bitmap.width}x${bitmap.height}")
                                    }
                                    
                                    // Start streaming with result listener
                                    llmSession.generateResponseAsync { partialResult, done ->
                                        // Update the streaming response on each partial result (this runs on main thread)
                                        scope.launch {
                                            streamingResponse += partialResult
                                            fullResponse += partialResult
                                            Log.i("LlmInference", "partial result: $partialResult, done: $done")
                                            
                                            if (done) {
                                                isStreaming = false
                                                Log.i("LlmInference", "Streaming completed")
                                                
                                                // Speak the complete response when streaming is done
                                                if (enableTts && isTtsReady) {
                                                    onSpeakText(fullResponse)
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                streamingResponse = "Error: ${e.message}"
                                isStreaming = false
                                Log.e("LlmInference", "Error streaming response: ${e.message}")
                            }
                        }
                    }
                },
                enabled = !isLoading && !isStreaming && llmSession != null,
                modifier = Modifier.weight(1f)
            ) {
                if (isStreaming) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Streaming...")
                    }
                } else {
                    Text("Stream Response")
                }
            }
        }
        
        // Response section
        if (response.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Generated Response:",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                if (lastInputMethod.isNotEmpty()) {
                    Text(
                        text = lastInputMethod,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = response,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        
        // Streaming response section
        if (streamingResponse.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Streaming Response:",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                if (lastInputMethod.isNotEmpty()) {
                    Text(
                        text = lastInputMethod,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = streamingResponse,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LlmInferenceScreenPreview() {
    BrailleBridge2Theme {
        LlmInferenceScreen(
            llmInference = null,
            llmSession = null,
            modelPath = "/path/to/model",
            onSpeakText = {},
            isTtsReady = true
        )
    }
}