package com.example.braillebridge2.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap

enum class ChatMessageType {
    INFO,
    TEXT,
    IMAGE,
    AUDIO_CLIP,
    LOADING,
}

enum class ChatSide {
    USER,
    AGENT,
    SYSTEM,
}

/** Base class for a chat message. */
open class ChatMessage(
    open val type: ChatMessageType,
    open val side: ChatSide,
    open val latencyMs: Float = -1f,
) {
    open fun clone(): ChatMessage {
        return ChatMessage(type = type, side = side, latencyMs = latencyMs)
    }
}

/** Chat message for showing loading status. */
class ChatMessageLoading : ChatMessage(type = ChatMessageType.LOADING, side = ChatSide.AGENT)

/** Chat message for info (help). */
class ChatMessageInfo(val content: String) :
    ChatMessage(type = ChatMessageType.INFO, side = ChatSide.SYSTEM)

/** Chat message for plain text. */
open class ChatMessageText(
    val content: String,
    override val side: ChatSide,
    override val latencyMs: Float = 0f,
) : ChatMessage(
    type = ChatMessageType.TEXT,
    side = side,
    latencyMs = latencyMs,
) {
    override fun clone(): ChatMessageText {
        return ChatMessageText(
            content = content,
            side = side,
            latencyMs = latencyMs,
        )
    }
}

/** Chat message for images. */
class ChatMessageImage(
    val bitmap: Bitmap,
    val imageBitMap: ImageBitmap,
    override val side: ChatSide,
    override val latencyMs: Float = 0f,
) : ChatMessage(type = ChatMessageType.IMAGE, side = side, latencyMs = latencyMs) {
    override fun clone(): ChatMessageImage {
        return ChatMessageImage(
            bitmap = bitmap,
            imageBitMap = imageBitMap,
            side = side,
            latencyMs = latencyMs,
        )
    }
}

/** Chat message for audio clip. */
class ChatMessageAudioClip(
    val audioData: ByteArray,
    val sampleRate: Int,
    override val side: ChatSide,
    override val latencyMs: Float = 0f,
) : ChatMessage(type = ChatMessageType.AUDIO_CLIP, side = side, latencyMs = latencyMs) {
    override fun clone(): ChatMessageAudioClip {
        return ChatMessageAudioClip(
            audioData = audioData,
            sampleRate = sampleRate,
            side = side,
            latencyMs = latencyMs,
        )
    }
}