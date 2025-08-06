package com.example.braillebridge2.core

/**
 * Localized strings for the app in both English and Urdu
 */
object LocalizedStrings {
    
    fun getString(key: StringKey, language: Language): String {
        return when (language) {
            Language.ENGLISH -> englishStrings[key] ?: ""
            Language.URDU -> urduStrings[key] ?: ""
        }
    }
    
    enum class StringKey {
        // Voice commands and instructions
        LISTENING_FOR_COMMAND,
        COMMAND_NOT_RECOGNIZED,
        COMMAND_NOT_HEARD,
        
        // Language switching
        SWITCHED_TO_ENGLISH,
        SWITCHED_TO_URDU,
        
        // Audio/recording feedback
        AUDIO_NOT_AVAILABLE,
        NO_AUDIO_AVAILABLE,
        RECORDING_FAILED,
        PHOTO_SAVED,
        
        // Question handling
        QUESTION_PREFIX,
        NO_QUESTION_TO_REPEAT,
        HOMEWORK_INSTRUCTIONS,
        
        // Recording and camera
        RECORDING_STARTED_TAP_TO_STOP,
        CAMERA_OPENED_FOR_PHOTO,
        
        // Ask feature
        QUESTION_RECORDING_STARTED,
        GEMMA_RESPONDING,
        
        // Feedback feature
        FEEDBACK_LISTENING_FOR_COMMAND,
        FEEDBACK_PREFIX,
        NO_FEEDBACK_TO_REPEAT,
        TRANSLATING_FEEDBACK,
        
        // Spatial feature
        TAKE_PHOTO_INSTRUCTION,
        
        // General
        COULD_NOT_SWITCH_LANGUAGE
    }
    
    private val englishStrings = mapOf(
        StringKey.LISTENING_FOR_COMMAND to "Listening for command. Say 'listen', 'switch', 'repeat', or 'ask'.",
        StringKey.COMMAND_NOT_RECOGNIZED to "Command not recognized. Say listen, switch, repeat, or ask.",
        StringKey.COMMAND_NOT_HEARD to "Command not heard. Please try again.",
        
        StringKey.SWITCHED_TO_ENGLISH to "Switched to English.",
        StringKey.SWITCHED_TO_URDU to "Switched to Urdu.",
        
        StringKey.AUDIO_NOT_AVAILABLE to "Audio not available",
        StringKey.NO_AUDIO_AVAILABLE to "No audio available",
        StringKey.RECORDING_FAILED to "Recording failed",
        StringKey.PHOTO_SAVED to "Photo submitted",
        
        StringKey.QUESTION_PREFIX to "Question",
        StringKey.NO_QUESTION_TO_REPEAT to "No question to repeat",
        StringKey.HOMEWORK_INSTRUCTIONS to "When you are ready, tap to answer by voice, double tap to answer by photo, or hold to speak a command.",
        
        StringKey.RECORDING_STARTED_TAP_TO_STOP to "Recording started. Tap again to stop.",
        StringKey.CAMERA_OPENED_FOR_PHOTO to "Camera opened for photo answer.",
        
        StringKey.QUESTION_RECORDING_STARTED to "Ask your question now. Tap to stop recording.",
        StringKey.GEMMA_RESPONDING to "Responding...",
        
        StringKey.FEEDBACK_LISTENING_FOR_COMMAND to "Listening for command. Say 'switch', 'repeat', or 'ask'.",
        StringKey.FEEDBACK_PREFIX to "Feedback",
        StringKey.NO_FEEDBACK_TO_REPEAT to "No feedback to repeat",
        StringKey.TRANSLATING_FEEDBACK to "Translating feedback...",
        
        StringKey.TAKE_PHOTO_INSTRUCTION to "Take a photo of your diagram with your finger pointing to an object",
        
        StringKey.COULD_NOT_SWITCH_LANGUAGE to "Could not switch language"
    )
    
    private val urduStrings = mapOf(
        StringKey.LISTENING_FOR_COMMAND to "کمانڈ سن رہا ہوں۔ 'سنیں'، 'تبدیل کریں'، 'دہرائیں'، یا 'سوال' کہیں۔",
        StringKey.COMMAND_NOT_RECOGNIZED to "کمانڈ سمجھ نہیں آئی۔ سنیں، تبدیل کریں، دہرائیں، یا سوال کہیں۔",
        StringKey.COMMAND_NOT_HEARD to "کمانڈ سنائی نہیں دی۔ دوبارہ کوشش کریں۔",
        
        StringKey.SWITCHED_TO_ENGLISH to "انگریزی میں تبدیل کر دیا گیا۔",
        StringKey.SWITCHED_TO_URDU to "اردو میں تبدیل کر دیا گیا۔",
        
        StringKey.AUDIO_NOT_AVAILABLE to "آڈیو دستیاب نہیں",
        StringKey.NO_AUDIO_AVAILABLE to "کوئی آڈیو دستیاب نہیں",
        StringKey.RECORDING_FAILED to "ریکارڈنگ ناکام",
        StringKey.PHOTO_SAVED to "تصویر جمع کرائی گئی",
        
        StringKey.QUESTION_PREFIX to "سوال",
        StringKey.NO_QUESTION_TO_REPEAT to "دہرانے کے لیے کوئی سوال نہیں",
        StringKey.HOMEWORK_INSTRUCTIONS to "جب آپ تیار ہوں تو آواز سے جواب دینے کے لیے ٹیپ کریں، تصویر سے جواب دینے کے لیے ڈبل ٹیپ کریں، یا کمانڈ بولنے کے لیے دبائیں۔",
        
        StringKey.RECORDING_STARTED_TAP_TO_STOP to "ریکارڈنگ شروع ہو گئی۔ رکنے کے لیے دوبارہ ٹیپ کریں۔",
        StringKey.CAMERA_OPENED_FOR_PHOTO to "تصویر کے جواب کے لیے کیمرا کھل گیا۔",
        
        StringKey.QUESTION_RECORDING_STARTED to "اپنا سوال پوچھیں۔ ریکارڈنگ رکنے کے لیے ٹیپ کریں۔",
        StringKey.GEMMA_RESPONDING to "جواب دے رہا ہوں...",
        
        StringKey.FEEDBACK_LISTENING_FOR_COMMAND to "کمانڈ سن رہا ہوں۔ 'تبدیل کریں'، 'دہرائیں'، یا 'سوال' کہیں۔",
        StringKey.FEEDBACK_PREFIX to "فیڈبیک",
        StringKey.NO_FEEDBACK_TO_REPEAT to "دہرانے کے لیے کوئی فیڈبیک نہیں",
        StringKey.TRANSLATING_FEEDBACK to "فیڈبیک کا ترجمہ کر رہا ہوں...",
        
        StringKey.TAKE_PHOTO_INSTRUCTION to "اپنے آریاگرام کی تصویر لیں جس میں آپ کی انگلی کسی چیز کی طرف اشارہ کر رہی ہو",
        
        StringKey.COULD_NOT_SWITCH_LANGUAGE to "زبان تبدیل نہیں کر سکے"
    )
}