package com.example.braillebridge2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.braillebridge2.core.Language
import java.io.File

/**
 * Composable to display Braille content from text files
 */
@Composable
fun BrailleSvgView(
    svgFile: File,
    language: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopStart
    ) {
        // Try to read the corresponding Braille text file instead of SVG
        val brailleTextFile = when (language.uppercase()) {
            "ENGLISH" -> File(svgFile.parentFile, "braille_en.txt")
            "URDU" -> File(svgFile.parentFile, "braille_ur.txt")
            else -> File(svgFile.parentFile, "braille_en.txt")
        }
        
        if (brailleTextFile.exists()) {
            // Display Braille text with proper formatting
            val brailleText = remember(brailleTextFile, language) {
                try {
                    brailleTextFile.readText().trim()
                } catch (e: Exception) {
                    "⠠⠃⠗⠁⠊⠇⠇⠑⠀⠞⠑⠭⠞⠀⠥⠝⠁⠧⠁⠊⠇⠁⠃⠇⠑" // "Braille text unavailable"
                }
            }
            
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // Header
                                            Text(
                            text = "Braille (${language.lowercase().replaceFirstChar { it.titlecase() }})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    
                    // Braille text with scrolling
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = brailleText,
                            fontFamily = FontFamily.Monospace, // Use monospace for better Braille character display
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            color = Color.Black,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        } else {
            // Fallback placeholder
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = "Braille placeholder",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Braille Text\n($language)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}