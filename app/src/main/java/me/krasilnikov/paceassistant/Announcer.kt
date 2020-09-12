/*
 * Copyright 2020 Alexey Krasilnikov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.krasilnikov.paceassistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

interface Announcer {
    suspend fun say(hr: Int)
}

class TTSAnnouncer(private val tts: TextToSpeech, audioSessionId: Int) : Announcer {
    private val speakId = UUID.randomUUID().toString()
    private val params =
        Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }

    init {
        tts.setSpeechRate(1.25f)
    }

    override suspend fun say(hr: Int) {
        val text = "$hr"

        Timber.tag("Announcer").i("say: %s", text)

        suspendCancellableCoroutine<Unit> { cont ->
            tts.setOnUtteranceProgressListener(object :
                UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                }

                override fun onDone(utteranceId: String) {
                    if (speakId == utteranceId) cont.resume(Unit)
                }

                override fun onError(utteranceId: String) {
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, speakId)
        }
    }
}

suspend fun createAnnouncer(context: Context, audioSessionId: Int): Announcer {
    val ttsResultChannel = Channel<Int>(Channel.CONFLATED)
    val tts = TextToSpeech(context) { result -> ttsResultChannel.offer(result) }

    if (ttsResultChannel.receive() == TextToSpeech.SUCCESS) {
        return TTSAnnouncer(tts, audioSessionId)
    }

    throw IllegalStateException("Text to speech is not available")
}
