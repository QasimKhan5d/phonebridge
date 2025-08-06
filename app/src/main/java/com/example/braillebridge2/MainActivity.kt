package com.example.braillebridge2

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.braillebridge2.chat.ChatScreen
import com.example.braillebridge2.chat.ChatViewModel
import com.example.braillebridge2.chat.LlmModelManager
import com.example.braillebridge2.chat.LlmModelInstance
import com.example.braillebridge2.ui.theme.BrailleBridge2Theme
import com.example.braillebridge2.core.*
import com.example.braillebridge2.ui.HomeScreen
import com.example.braillebridge2.ui.HomeworkScreen
import com.example.braillebridge2.ui.FeedbackScreen
import com.example.braillebridge2.ui.SpatialScreen
import com.example.braillebridge2.viewmodel.MainViewModel
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class MainActivity : ComponentActivity() {
    // Use shared model manager from ModelCheckActivity or create new one as fallback
    private lateinit var modelManager: LlmModelManager
    private lateinit var ttsHelper: TtsHelper
    private lateinit var modelPath: String
    private var isModelLoaded by mutableStateOf(false)
    
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "MainActivity onCreate() called")
        enableEdgeToEdge()
        
        // Use external files directory like the gallery app does
        modelPath = File(getExternalFilesDir(null), "gemma-3n-E2B-it-int4.task").absolutePath
        
        // Initialize TTS Helper
        ttsHelper = TtsHelper(this) {
            // TTS is ready callback
        }
        
        // Use shared model manager from ModelCheckActivity if available, otherwise create new one
        modelManager = ModelCheckActivity.sharedModelManager ?: LlmModelManager()
        
        // Check if model is already initialized from ModelCheckActivity
        if (modelManager.isReady()) {
            Log.i("LlmInference", "Model already initialized from ModelCheckActivity")
            isModelLoaded = true
        } else {
            // Initialize the LLM inference task on background thread using gallery app pattern
            Log.i("LlmInference", "Model not initialized, starting initialization...")
            initializeLlmInferenceAsync()
        }
        
        setContent {
            BrailleBridge2Theme {
                BrailleBridgeApp(
                    ttsHelper = ttsHelper,
                    modelManager = modelManager,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
    
    private fun initializeLlmInferenceAsync() {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Log.i("LlmInference", "Starting LLM initialization on background thread...")
                modelManager.setInitializing(true)
                
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    Log.w("LlmInference", "Model file not found at: $modelPath")
                    modelManager.setError("Model file not found")
                    return@launch
                }
                
                // Set the configuration options for the LLM Inference task with optimized settings
                val taskOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(4096) // Match gallery app optimizations
                    .setPreferredBackend(LlmInference.Backend.GPU) // Use GPU for faster loading
                    .setMaxNumImages(1) // Enable single image support for Gemma-3n
                    .build()
                
                // Create an instance of the LLM Inference task
                val llmInference = LlmInference.createFromOptions(this@MainActivity, taskOptions)
                
                // Create a session for streaming with optimized settings from gallery app
                val llmSession = LlmInferenceSession.createFromOptions(
                    llmInference,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(64) // Match gallery app settings
                        .setTopP(0.95f) // Use gallery app optimized value
                        .setTemperature(1.0f)
                        .setGraphOptions(
                            GraphOptions.builder()
                                .setEnableVisionModality(true) // Enable vision support
                                .build()
                        )
                        .build()
                )
                
                // Set the model instance using gallery app pattern
                modelManager.setInstance(LlmModelInstance(llmInference, llmSession))
                
                // Update UI state on main thread
                runOnUiThread {
                    isModelLoaded = true
                }
                
                Log.i("LlmInference", "LLM Inference and Session initialized with vision support")
            } catch (e: Exception) {
                Log.e("LlmInference", "Failed to initialize LLM Inference: ${e.message}")
                modelManager.setError(e.message ?: "Unknown error")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ttsHelper.shutdown()
        modelManager.cleanup()
    }
}

// Keep the helper functions for image loading

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
fun createImageFile(context: android.content.Context): File {
    val storageDir = context.getExternalFilesDir(null)
    return File.createTempFile(
        "JPEG_${System.currentTimeMillis()}_",
        ".jpg",
        storageDir
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMainScreen(
    modelManager: LlmModelManager,
    isModelLoaded: Boolean,
    modelPath: String,
    onSpeakText: (String) -> Unit,
    isTtsReady: Boolean,
    modifier: Modifier = Modifier
) {
    var enableTts by remember { mutableStateOf(true) }
    val chatViewModel: ChatViewModel = viewModel()
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Model status header
        if (!isModelLoaded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                    Column {
                        Text(
                            text = "Loading Gemma-3n Model...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "This may take a few moments",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            // Status and TTS controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                    Text(
                            text = "✓ Gemma-3n Ready",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isTtsReady) "🔊 TTS Ready" else "⏳ TTS Initializing...",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp
                    )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                            text = "TTS",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.sp
                )
                Switch(
                    checked = enableTts,
                    onCheckedChange = { enableTts = it },
                    enabled = isTtsReady
                )
                    }
                }
            }
        }
        
        // Chat interface
        ChatScreen(
            viewModel = chatViewModel,
            modelManager = modelManager,
            onSpeakText = onSpeakText,
            isTtsReady = isTtsReady,
            enableTts = enableTts,
                modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun BrailleBridgeApp(
    ttsHelper: TtsHelper,
    modelManager: LlmModelManager,
    modifier: Modifier = Modifier
) {
    val mainViewModel: MainViewModel = viewModel()
    val uiState by mainViewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Track TTS readiness state for recomposition
    var isTtsReady by remember { mutableStateOf(ttsHelper.isReady) }
    
    // Check TTS readiness periodically until it's ready
    LaunchedEffect(Unit) {
        while (!isTtsReady) {
            kotlinx.coroutines.delay(100) // Check every 100ms
            isTtsReady = ttsHelper.isReady
        }
    }
    
    // Initialize the app when it starts
    LaunchedEffect(Unit) {
        mainViewModel.initialize(context)
    }
    
    when (val currentState = uiState) {
        is AppState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading Braille Bridge...",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
        
        is AppState.Home -> {
            HomeScreen(
                state = currentState,
                viewModel = mainViewModel,
                ttsHelper = ttsHelper,
                isTtsReady = isTtsReady,
                modifier = modifier
            )
        }
        
        is AppState.Homework -> {
            HomeworkScreen(
                state = currentState,
                viewModel = mainViewModel,
                ttsHelper = ttsHelper,
                modelManager = modelManager,
                modifier = modifier
            )
        }
        
        is AppState.Feedback -> {
            FeedbackScreen(
                state = currentState,
                viewModel = mainViewModel,
                ttsHelper = ttsHelper,
                modelManager = modelManager,
                modifier = modifier
            )
        }
        
        is AppState.Spatial -> {
            SpatialScreen(
                state = currentState,
                viewModel = mainViewModel,
                modelManager = modelManager,
                ttsHelper = ttsHelper,
                modifier = modifier
            )
        }
    }
}