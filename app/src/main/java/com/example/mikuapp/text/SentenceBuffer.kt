package com.example.mikuapp.text

import com.example.mikuapp.util.MikuLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SentenceBuffer {

    val sentences: Channel<String> = Channel(Channel.UNLIMITED)

    var onSentence: ((String) -> Unit)? = null

    private val pending = StringBuilder()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        pending.append(chunk)
        emitCompleteSentences()
    }

    fun flush() {
        if (pending.isBlank()) {
            pending.setLength(0)
            _partialText.value = ""
            return
        }
        val sentence = clean(pending.toString())
        pending.setLength(0)
        _partialText.value = ""
        if (sentence.isNotEmpty()) {
            MikuLog.d(TAG, "Enviada al TTS (flush): \"$sentence\"")
            sentences.trySend(sentence)
            onSentence?.invoke(sentence)
        }
    }

    fun reset() {
        if (pending.isNotBlank()) {
            MikuLog.d(TAG, "Buffer descartado: \"${pending.toString().take(80)}\"")
        }
        pending.setLength(0)
        _partialText.value = ""
        var dropped = 0
        while (sentences.tryReceive().isSuccess) {
            dropped++
        }
        if (dropped > 0) {
            MikuLog.d(TAG, "Frases en cola descartadas: $dropped")
        }
    }

    fun close() {
        reset()
        sentences.close()
    }

    private fun emitCompleteSentences() {
        var cut = pending.indexOfFirst { it in PUNCTUATION }
        while (cut >= 0) {
            val raw = pending.substring(0, cut + 1)
            pending.delete(0, cut + 1)
            val sentence = clean(raw)
            if (sentence.isNotEmpty()) {
                MikuLog.d(TAG, "Enviada al TTS: \"$sentence\"")
                sentences.trySend(sentence)
                onSentence?.invoke(sentence)
            }
            cut = pending.indexOfFirst { it in PUNCTUATION }
        }
        _partialText.value = clean(pending.toString())
    }

    private fun clean(raw: String): String {
        var text = raw.replace(EMOJI_REGEX, " ")
        text = text.replace(MARKDOWN_REGEX, " ")
        text = text.replace(CONTROL_REGEX, " ")
        text = text.replace(WHITESPACE_REGEX, " ")
        text = text.replace(ORPHAN_PUNCTUATION_REGEX, "$1")
        return text.trim()
    }

    companion object {
        private const val TAG = "Frase"

        private val PUNCTUATION: Set<Char> = setOf('.', ',', '!', '?', ':', '\n')

        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Cn}\\u200B-\\u200F\\uFE0F\\u200D]")
        private val MARKDOWN_REGEX = Regex("[*_`#>~|\\[\\]{}\\\\]")
        private val CONTROL_REGEX = Regex("[\\p{Cc}\\p{Cf}]")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val ORPHAN_PUNCTUATION_REGEX = Regex("\\s+([.,!?;:])")
    }
}
