/*
 * Copyright 2025 Braille Bridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.braillebridge2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.braillebridge2.auth.AuthConfig
import com.example.braillebridge2.core.TtsHelper
import com.example.braillebridge2.download.ModelDownloadWorker
import com.example.braillebridge2.chat.LlmModelManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.example.braillebridge2.download.KEY_MODEL_URL
import com.example.braillebridge2.download.KEY_MODEL_FILE_NAME
import com.example.braillebridge2.download.KEY_ACCESS_TOKEN
import com.example.braillebridge2.download.KEY_TOTAL_BYTES
import com.example.braillebridge2.download.KEY_RECEIVED_BYTES
import com.example.braillebridge2.download.KEY_DOWNLOAD_RATE
import com.example.braillebridge2.ui.theme.BrailleBridge2Theme
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ModelCheckActivity"
private const val MODEL_FILE_NAME = "gemma-3n-E2B-it-int4.task"

// Hugging Face model URL for Gemma-3n-E2B-it-int4
private const val MODEL_URL = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task"
private const val ESTIMATED_MODEL_SIZE = 3136226711L // From model_allowlist.json

data class ModelCheckState(
    val isChecking: Boolean = true,
    val modelExists: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadSpeed: String = "",
    val downloadEta: String = "",
    val error: String = "",
    val needsAuth: Boolean = false,
    val showAgreementSheet: Boolean = false,
    val isAuthenticating: Boolean = false,
    val isInitializingLlm: Boolean = false
)

class ModelCheckActivity : ComponentActivity() {
    
    private lateinit var authService: AuthorizationService
    private var currentAccessToken: String? = null
    private val modelManager = LlmModelManager()
    private var currentWorkId: String? = null
    private var isCheckingModel = false
    private var isDownloadStarted = false
    
    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleAuthResult(result.data)
    }
    
    // Launcher for opening CustomTabs for license acceptance (like gallery app)
    private val agreementLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "User returned from license agreement page, retrying download")
        retryDownload()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        Log.d(TAG, "ModelCheckActivity onCreate() called")
        
        // Cancel any existing downloads on app start
        Log.d(TAG, "Cancelling all existing downloads on app start")
        WorkManager.getInstance(this).cancelAllWorkByTag("MODEL_DOWNLOAD")
        
        authService = AuthorizationService(this)
        
        setContent {
            BrailleBridge2Theme {
                ModelCheckScreen()
            }
        }
        
        // Start checking for the model immediately
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            checkForModel()
        }, 100) // Small delay to ensure UI is set up
    }
    
    override fun onDestroy() {
        super.onDestroy()
        authService.dispose()
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ModelCheckScreen() {
        var state by remember { mutableStateOf(ModelCheckState()) }
        
        // Set up state listener and start checking for model
        LaunchedEffect(Unit) {
            stateListener = { newState ->
                Log.d("ModelCheckActivity", "State listener called: isChecking=${newState.isChecking}, needsAuth=${newState.needsAuth}, error='${newState.error}'")
                state = newState
            }
            // Start checking for model when the screen loads
            checkForModel()
        }
        
        // Observe download progress if downloading
        LaunchedEffect(state.isDownloading) {
            if (state.isDownloading && currentWorkId != null) {
                observeDownloadProgress(currentWorkId!!) { progress, speed, eta, error ->
                    state = state.copy(
                        downloadProgress = progress,
                        downloadSpeed = speed,
                        downloadEta = eta,
                        error = error
                    )
                    
                    // Check if download completed
                    if (progress >= 100 && error.isEmpty()) {
                        Log.d(TAG, "Download completed successfully! Starting LLM initialization")
                        state = state.copy(isDownloading = false, isInitializingLlm = true)
                        isDownloadStarted = false
                        
                        // Start LLM initialization after download
                        val modelPath = java.io.File(getExternalFilesDir(null), "gemma-3n-E2B-it-int4.task").absolutePath
                        initializeLlm(modelPath)
                    }
                }
            }
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Braille Bridge",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                when {
                    state.isChecking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Checking for Gemma-3n model...",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    state.isInitializingLlm -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Initializing AI model...",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Setting up vision and language capabilities",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    
                    state.modelExists -> {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Model ready! Starting app...",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    state.isDownloading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = state.downloadProgress / 100f,
                                modifier = Modifier.size(64.dp),
                                strokeWidth = 6.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Downloading Gemma-3n Model",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${state.downloadProgress}%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.downloadSpeed.isNotEmpty()) {
                                Text(
                                    text = state.downloadSpeed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.downloadEta.isNotEmpty()) {
                                Text(
                                    text = "ETA: ${state.downloadEta}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    state.needsAuth && !state.isAuthenticating -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Model Download Required",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The Gemma-3n model needs to be downloaded from Hugging Face. This requires authentication and may take some time.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { 
                                    state = state.copy(showAgreementSheet = true)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Accept License & Download")
                            }
                        }
                    }
                    
                    state.isAuthenticating -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Authenticating with Hugging Face...",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    state.error.isNotEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { 
                                    state = ModelCheckState(isChecking = true)
                                    checkForModel()
                                }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
        
        // Modal Bottom Sheet for license agreement (like gallery app)
        if (state.showAgreementSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    state = state.copy(showAgreementSheet = false)
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "License Agreement Required",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "To download the Gemma-3n model, you need to accept Google's license agreement on Hugging Face. This will open in a browser tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            openLicenseAgreement()
                            state = state.copy(showAgreementSheet = false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open License Agreement")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
    
    private var stateListener: ((ModelCheckState) -> Unit)? = null
    
    private fun updateState(newState: ModelCheckState) {
        Log.d(TAG, "updateState called with: isChecking=${newState.isChecking}, needsAuth=${newState.needsAuth}, error='${newState.error}'")
        stateListener?.invoke(newState)
    }
    
    private fun checkForModel() {
        if (isCheckingModel || isDownloadStarted) {
            Log.d(TAG, "Already checking model or download started, skipping")
            return
        }
        
        lifecycleScope.launch {
            try {
                isCheckingModel = true
                updateState(ModelCheckState(isChecking = true))
                
                val modelFile = File(getExternalFilesDir(null), MODEL_FILE_NAME)
                
                if (modelFile.exists() && modelFile.length() > 0) {
                    Log.d(TAG, "Model found locally: ${modelFile.absolutePath}, size: ${modelFile.length()}")
                    
                    // Check if file size matches expected size (within 1% tolerance)
                    val expectedSize = ESTIMATED_MODEL_SIZE
                    val tolerance = expectedSize * 0.01 // 1% tolerance
                    
                    if (modelFile.length() < (expectedSize - tolerance)) {
                        Log.w(TAG, "Model file size ${modelFile.length()} is smaller than expected ${expectedSize}, deleting corrupted file")
                        modelFile.delete()
                        Log.d(TAG, "Deleted corrupted model file, will re-download")
                        checkModelAvailability()
                        return@launch
                    }
                    
                    Log.d(TAG, "Model file size OK, starting LLM initialization")
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        // Update state to show LLM initialization
                        updateState(ModelCheckState(modelExists = true, isInitializingLlm = true))
                        
                        // Start LLM initialization
                        initializeLlm(modelFile.absolutePath)
                    }
                } else {
                    Log.d(TAG, "Model not found locally, checking if download is needed")
                    checkModelAvailability()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for model", e)
                updateState(ModelCheckState(error = "Error checking for model: ${e.message}"))
            }
        }
    }
    
    private suspend fun checkModelAvailability() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Checking model availability at: $MODEL_URL")
                val url = URL(MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                
                // Add timeouts to prevent hanging
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 10000 // 10 seconds
                connection.requestMethod = "HEAD"
                
                // Add User-Agent to avoid blocking
                connection.setRequestProperty("User-Agent", "BrailleBridge-Android/1.0")
                
                // Use hardcoded token if available
                if (AuthConfig.HARDCODED_HF_TOKEN != "REPLACE_WITH_YOUR_HF_TOKEN_HERE" && AuthConfig.HARDCODED_HF_TOKEN.isNotBlank()) {
                    Log.d(TAG, "Using hardcoded HF token: ${AuthConfig.HARDCODED_HF_TOKEN.take(10)}...")
                    connection.setRequestProperty("Authorization", "Bearer ${AuthConfig.HARDCODED_HF_TOKEN}")
                }
                
                Log.d(TAG, "Attempting to connect...")
                connection.connect()
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")
                
                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        Log.d(TAG, "Model is available for download")
                        withContext(Dispatchers.Main) {
                            // Use hardcoded token if available, otherwise no token
                            val token = if (AuthConfig.HARDCODED_HF_TOKEN != "REPLACE_WITH_YOUR_HF_TOKEN_HERE" && AuthConfig.HARDCODED_HF_TOKEN.isNotBlank()) {
                                AuthConfig.HARDCODED_HF_TOKEN
                            } else {
                                null
                            }
                            startDownload(token)
                        }
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                        Log.d(TAG, "Model requires authentication or license acceptance")
                        withContext(Dispatchers.Main) {
                            // Show authentication UI like the gallery app (stop checking, show auth UI)
                            updateState(ModelCheckState(isChecking = false, needsAuth = true))
                        }
                    }
                    else -> {
                        throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                    }
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking model availability", e)
                withContext(Dispatchers.Main) {
                    updateState(ModelCheckState(error = "Network error: ${e.message}. Please check your internet connection and try again."))
                }
            }
        }
    }
    
    private fun startAuthentication() {
        val authRequest = AuthorizationRequest.Builder(
            AuthConfig.authServiceConfig,
            AuthConfig.clientId,
            ResponseTypeValues.CODE,
            AuthConfig.redirectUri.toUri()
        )
            .setScope("read-repos")
            .build()
        
        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        authLauncher.launch(authIntent)
    }
    
    private fun handleAuthResult(data: Intent?) {
        if (data == null) {
            updateState(ModelCheckState(error = "Authentication cancelled"))
            return
        }
        
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        
        when {
            response?.authorizationCode != null -> {
                // Exchange code for token
                authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
                    if (tokenResponse != null && tokenResponse.accessToken != null) {
                        Log.d(TAG, "Authentication successful")
                        currentAccessToken = tokenResponse.accessToken
                        startDownload(currentAccessToken)
                    } else {
                        Log.e(TAG, "Token exchange failed: ${tokenEx?.message}")
                        updateState(ModelCheckState(error = "Authentication failed: ${tokenEx?.message}"))
                    }
                }
            }
            exception != null -> {
                Log.e(TAG, "Authentication failed: ${exception.message}")
                updateState(ModelCheckState(error = "Authentication failed: ${exception.message}"))
            }
            else -> {
                updateState(ModelCheckState(error = "Authentication cancelled"))
            }
        }
    }
    
    private fun startDownload(accessToken: String?) {
        if (isDownloadStarted) {
            Log.d(TAG, "Download already started, skipping")
            return
        }
        
        isDownloadStarted = true
        Log.d(TAG, "Starting download with token: ${if (accessToken != null) "yes" else "no"}")
        
        // Cancel all existing download workers to prevent conflicts
        Log.d(TAG, "Cancelling all existing ModelDownloadWorker instances")
        WorkManager.getInstance(this).cancelAllWorkByTag("MODEL_DOWNLOAD")
        
        val inputData = Data.Builder()
            .putString(KEY_MODEL_URL, MODEL_URL)
            .putString(KEY_MODEL_FILE_NAME, MODEL_FILE_NAME)
            .putLong(KEY_TOTAL_BYTES, ESTIMATED_MODEL_SIZE)
            .apply {
                if (accessToken != null) {
                    putString(KEY_ACCESS_TOKEN, accessToken)
                }
            }
            .build()
        
        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag("MODEL_DOWNLOAD") // Add tag for easier management
            .build()
        
        currentWorkId = downloadRequest.id.toString()
        Log.d(TAG, "Enqueuing download work with ID: $currentWorkId")
        WorkManager.getInstance(this).enqueue(downloadRequest)
        
        updateState(ModelCheckState(isChecking = false, isDownloading = true))
    }
    
    private fun observeDownloadProgress(
        workId: String,
        onProgress: (progress: Int, speed: String, eta: String, error: String) -> Unit
    ) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(
            java.util.UUID.fromString(workId)
        ).observe(this) { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> {
                    val receivedBytes = workInfo.progress.getLong(KEY_RECEIVED_BYTES, 0L)
                    val downloadRate = workInfo.progress.getLong(KEY_DOWNLOAD_RATE, 0L)
                    val remainingMs = workInfo.progress.getLong("remaining_ms", 0L)
                    
                    val progress = if (ESTIMATED_MODEL_SIZE > 0) {
                        ((receivedBytes * 100) / ESTIMATED_MODEL_SIZE).toInt()
                    } else 0
                    
                    val speed = if (downloadRate > 0) {
                        "${downloadRate / 1024} KB/s"
                    } else ""
                    
                    val eta = if (remainingMs > 0) {
                        val seconds = remainingMs / 1000
                        "${seconds / 60}m ${seconds % 60}s"
                    } else ""
                    
                    onProgress(progress, speed, eta, "")
                }
                WorkInfo.State.SUCCEEDED -> {
                    onProgress(100, "", "", "")
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString("error_message") ?: "Download failed"
                    onProgress(0, "", "", error)
                }
                else -> {}
            }
        }
    }
    
    private fun openLicenseAgreement() {
        Log.d(TAG, "Opening Hugging Face license agreement with CustomTabs")
        try {
            // Use CustomTabs like the gallery app does
            val agreementUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview"
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.data = agreementUrl.toUri()
            
            Log.d(TAG, "Launching CustomTabs for: $agreementUrl")
            agreementLauncher.launch(customTabsIntent.intent)
            
            // Show authenticating state
            updateState(ModelCheckState(isAuthenticating = true))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening license agreement with CustomTabs", e)
            // Fallback to regular browser intent
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://huggingface.co/google/gemma-3n-E2B-it-litert-preview"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
                updateState(ModelCheckState(isAuthenticating = true))
                
                // Since we can't track when user returns from regular browser, retry after delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    retryDownload()
                }, 10000) // 10 second delay for manual browser
                
            } catch (e2: Exception) {
                Log.e(TAG, "Error with fallback browser intent", e2)
                updateState(ModelCheckState(error = "Unable to open browser. Please visit https://huggingface.co/google/gemma-3n-E2B-it-litert-preview manually to accept the license."))
            }
        }
    }
    
    private fun retryDownload() {
        Log.d(TAG, "Retrying model download after license acceptance")
        // Reset flags and try again
        isCheckingModel = false
        isDownloadStarted = false
        lifecycleScope.launch {
            try {
                checkModelAvailability()
            } catch (e: Exception) {
                Log.e(TAG, "Error retrying download", e)
                updateState(ModelCheckState(error = "Failed to retry download: ${e.message}"))
                isCheckingModel = false
                isDownloadStarted = false
            }
        }
    }
    
    private fun initializeLlm(modelPath: String) {
        Log.d(TAG, "initializeLlm() called with path: $modelPath")
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                Log.i("LlmInference", "Starting LLM initialization...")
                modelManager.setInitializing(true)
                
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    throw Exception("Model file not found at $modelPath")
                }
                
                // Set the configuration options for the LLM Inference task with optimized settings
                val taskOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(4096) // Match gallery app optimizations
                    .setPreferredBackend(LlmInference.Backend.GPU) // Use GPU for faster loading
                    .setMaxNumImages(1) // Enable single image support for Gemma-3n
                    .build()
                
                // Create an instance of the LLM Inference task
                val llmInference = LlmInference.createFromOptions(this@ModelCheckActivity, taskOptions)
                
                // Create a session for streaming with optimized settings from gallery app
                val llmSession = com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.createFromOptions(
                    llmInference,
                    com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(64) // Match gallery app settings
                        .setTopP(0.95f) // Use gallery app optimized value
                        .setTemperature(1.0f)
                        .setGraphOptions(
                            com.google.mediapipe.tasks.genai.llminference.GraphOptions.builder()
                                .setEnableVisionModality(true) // Enable vision support
                                .build()
                        )
                        .build()
                )
                
                // Set the model instance
                modelManager.setInstance(com.example.braillebridge2.chat.LlmModelInstance(llmInference, llmSession))
                
                // Share the initialized model manager for MainActivity to use
                sharedModelManager = modelManager
                
                Log.i("LlmInference", "LLM Inference and Session initialized with vision support")
                
                // Update UI state on main thread and proceed to main app
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateState(ModelCheckState(modelExists = true, isInitializingLlm = false))
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        proceedToMainApp()
                    }, 1000) // Brief delay to show completion
                }
            } catch (e: Exception) {
                Log.e("LlmInference", "Failed to initialize LLM Inference: ${e.message}")
                modelManager.setError(e.message ?: "Unknown error")
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateState(ModelCheckState(error = "Failed to initialize model: ${e.message}"))
                }
            }
        }
    }
    
    private fun proceedToMainApp() {
        Log.d(TAG, "proceedToMainApp() called - starting MainActivity")
        try {
            val intent = Intent(this, MainActivity::class.java)
            // Pass initialization flag to skip redundant initialization in MainActivity
            intent.putExtra("MODEL_ALREADY_INITIALIZED", true)
            Log.d(TAG, "Intent created, starting MainActivity")
            startActivity(intent)
            Log.d(TAG, "MainActivity started, finishing ModelCheckActivity")
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MainActivity", e)
            updateState(ModelCheckState(error = "Failed to start main app: ${e.message}"))
        }
    }
    
    companion object {
        // Shared model manager instance to avoid re-initialization
        var sharedModelManager: LlmModelManager? = null
    }
}
