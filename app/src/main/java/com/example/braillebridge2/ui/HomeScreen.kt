package com.example.braillebridge2.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.braillebridge2.core.*
import com.example.braillebridge2.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    state: AppState.Home,
    viewModel: MainViewModel,
    ttsHelper: TtsHelper,
    isTtsReady: Boolean = ttsHelper.isReady,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Announce notifications when screen loads - wait for TTS to be ready
    LaunchedEffect(state.notification, isTtsReady) {
        if (isTtsReady) {
            val notificationMessage = when (state.notification) {
                NotificationType.HOMEWORK -> "You have new homework, tap once to open it."
                NotificationType.FEEDBACK -> "You have new feedback, tap twice to open it."
                NotificationType.BOTH -> "You have new homework and feedback. Tap once for homework, twice for feedback."
                NotificationType.NONE -> "No new notifications."
            }
            val fullMessage = "$notificationMessage Hold to understand a diagram."
            Log.d("HomeScreen", "TTS is ready, speaking: $fullMessage")
            ttsHelper.speak(fullMessage)
        } else {
            Log.d("HomeScreen", "TTS not ready yet, waiting...")
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        viewModel.onHomeTap()
                    },
                    onDoubleTap = {
                        viewModel.onHomeDoubleTap()
                    },
                    onLongPress = {
                        viewModel.onHomeLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Braille Bridge",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                when (state.notification) {
                    NotificationType.HOMEWORK -> {
                        Icon(
                            imageVector = Icons.Default.Assignment,
                            contentDescription = "Homework available",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "New Homework Available",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Tap once to open",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    NotificationType.FEEDBACK -> {
                        Icon(
                            imageVector = Icons.Default.Feedback,
                            contentDescription = "Feedback available",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "New Feedback Available",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Tap twice to open",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    NotificationType.BOTH -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Assignment,
                                contentDescription = "Homework available",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.Feedback,
                                contentDescription = "Feedback available",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = "Homework & Feedback Available",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Tap once for homework\nTap twice for feedback",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    NotificationType.NONE -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "No notifications",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "No New Notifications",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "All caught up!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Spatial Feature Information
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Image Understanding",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Image Understanding",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Hold to understand a diagram",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}