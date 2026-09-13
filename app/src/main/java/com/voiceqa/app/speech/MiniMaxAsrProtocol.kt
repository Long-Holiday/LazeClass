package com.voiceqa.app.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MiniMaxAsrProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val supportedLanguages = setOf(
        "zh", "yue", "en", "ja", "ko", "th", "vi", "id", "ms", "fil",
        "ar", "tr", "fr", "de", "es", "it", "pt", "pl", "ru", "uk"
    )

    data class StreamEvent(
        val index: Int,
        val delta: String,
        val finished: Boolean
    )

    fun languageHeader(locale: String): String? {
        val normalized = locale.trim().lowercase().replace('_', '-')
        val language = normalized.substringBefore('-')
        return language.takeIf { it in supportedLanguages }
    }

    fun parseText(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        return root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    fun parseStreamEvent(line: String): StreamEvent? {
        val payload = line.trim().removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            StreamEvent(
                index = root["index"]?.jsonPrimitive?.intOrNull ?: return null,
                delta = root["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                finished = root["finish"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }.getOrNull()
    }

    fun parseError(body: String): String? = runCatching {
        json.parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate: 16-bit mono
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        return header + pcm
    }
}
