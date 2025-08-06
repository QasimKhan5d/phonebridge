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

package com.example.braillebridge2.auth

import androidx.core.net.toUri
import net.openid.appauth.AuthorizationServiceConfiguration

object AuthConfig {
    // For Gemma models, users need to accept license terms on HuggingFace website
    // This is typically done once per user account through the web interface
    // Most users won't need OAuth for downloading after accepting terms
    
    // Hardcoded Hugging Face token - Replace with your actual token from https://huggingface.co/settings/tokens
    // Make sure you have accepted the Gemma license at: https://huggingface.co/google/gemma-3n-E2B-it-litert-preview
    const val HARDCODED_HF_TOKEN = "hf_..."
    
    // Hugging Face Client ID - Only needed if you create a custom HF OAuth app
    const val clientId = "REPLACE_WITH_YOUR_CLIENT_ID_IN_HUGGINGFACE_APP"

    // Registered redirect URI 
    const val redirectUri = "com.example.braillebridge2://oauth/callback"

    // OAuth 2.0 Endpoints (Authorization + Token Exchange)
    private const val authEndpoint = "https://huggingface.co/oauth/authorize"
    private const val tokenEndpoint = "https://huggingface.co/oauth/token"

    // OAuth service configuration
    val authServiceConfig =
        AuthorizationServiceConfiguration(
            authEndpoint.toUri(),
            tokenEndpoint.toUri()
        )
}
