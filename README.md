# Braille Bridge Android App

The Braille Bridge Android application is an educational tool designed to assist visually impaired students in learning Braille. The app provides a multi-modal learning experience, combining audio, text, and interactive exercises to teach Braille literacy. It leverages the power of the Gemma-3n language model to provide real-time translation and feedback, creating a personalized and engaging learning environment.

## Core Functionality

The app is designed to be fully accessible, with a focus on gesture-based navigation and audio feedback. The main features include:

- **Gesture-Based Navigation**: The app uses simple tap, double-tap, and long-press gestures to navigate between different sections, making it easy for visually impaired users to interact with the app.
- **Lesson Packs**: The app uses "Lesson Packs" that contain a series of exercises to teach Braille. Each lesson includes a question, an audio description, and a Braille representation of the text.
- **Homework and Feedback**: Students can complete homework assignments by submitting voice or photo answers. The app provides detailed feedback on their submissions, helping them to improve their Braille skills.
- **Image Understanding**: The "Spatial Screen" allows students to take a photo of a diagram or image and receive an audio description of its contents, powered by the Gemma-3n model.
- **Bilingual Support**: The app supports both English and Urdu, with real-time translation of lessons and feedback.

## Features

### Home Screen

The home screen provides an overview of the student's current status, including notifications for new homework and feedback. The following gestures can be used on the home screen:

- **Single Tap**: Opens the homework section.
- **Double Tap**: Opens the feedback section.
- **Long Press**: Opens the spatial screen for image understanding.

### Homework Screen

The homework screen presents a series of Braille lessons. Students can interact with the lessons using the following gestures:

- **Single Tap**: Start or stop recording a voice answer.
- **Double Tap**: Take a photo of a Braille answer.
- **Long Press**: Access voice commands, such as "listen," "switch language," or "repeat."

### Feedback Screen

The feedback screen provides detailed feedback on the student's homework submissions. Students can use the following gestures to interact with the feedback:

- **Single Tap**: Repeat the current feedback.
- **Long Press**: Access voice commands, such as "repeat," "switch language," or "ask a question."

### Spatial Screen

The spatial screen allows students to take a photo of a diagram or image and receive an audio description of its contents. A single tap on this screen initiates the photo capture process.

## Building and Running the App

To build and run the app, you will need to have an Android phone with medium specs. Download and install the APK in the releases to start using it! The app initially takes some time to download Gemma 3N and then it spends around a minute to load the model in memory. Also, add your HuggingFace token in `phonebridge/app/src/main/java/com/example/braillebridge2/auth/AuthConfig.kt` 

    const val HARDCODED_HF_TOKEN = "hf_..."

## Dependencies

The app uses the following key libraries and technologies:

-   **Kotlin**: The primary programming language for the app.
-   **Jetpack Compose**: For building the app's user interface.
-   **Google MediaPipe**: For running the Gemma-3n model on-device.
-   **TextToSpeech**: For providing audio feedback to the user.
-   **CameraX**: For capturing photos of Braille answers and diagrams.
