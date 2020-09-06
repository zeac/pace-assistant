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
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

interface Announcer {
    suspend fun say(hr: Int)
}

class EmbeddedAnnouncer(context: Context, audioSessionId: Int) : Announcer {
    private val audioAttributes = Audio.audioAttributes
    private val one = MediaPlayer.create(
        context,
        R.raw.h1,
        audioAttributes,
        audioSessionId
    )
    private val tens = arrayOf(
        MediaPlayer.create(
            context,
            R.raw.h10,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h20,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h30,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h40,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h50,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h60,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h70,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h80,
            audioAttributes,
            audioSessionId
        ),
        MediaPlayer.create(
            context,
            R.raw.h90,
            audioAttributes,
            audioSessionId
        ),
    )

    private val hundred = MediaPlayer.create(
        context,
        R.raw.h100,
        audioAttributes,
        audioSessionId
    )

    override suspend fun say(hr: Int) {
        val first = (hr / 100) % 10
        val second = (hr / 10) % 10

        Log.e(Worker.TAG, "$hr say with embedded voice: ${first * 100 + second * 10}")

        if (first == 1 && second == 0) {
            playSound(hundred)

        } else if (first == 1) {
            playSound(one)
            playSound(tens[second - 1])

        } else if (first == 0) {
            playSound(tens[second - 1])
        }
    }

    private suspend fun playSound(sound: MediaPlayer) {
        suspendCancellableCoroutine<Unit> { c ->
            sound.setOnSeekCompleteListener { c.resume(Unit) }
            sound.seekTo(0)
        }

        suspendCancellableCoroutine<Unit> { c ->
            sound.setOnCompletionListener { c.resume(Unit) }
            sound.start()
        }
    }
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
        val first = (hr / 100) % 10
        val second = (hr / 10) % 10

        val text = if (first == 1 && second == 0) {
            "100"
        } else if (first == 1) {
            "1 ${second}0"

        } else if (first == 0) {
            "${second}0"
        } else {
            "Something went wrong"
        }

        Log.e(Worker.TAG, "$hr say with tts: $text")

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

    return withContext(Dispatchers.IO) {
        EmbeddedAnnouncer(context, audioSessionId)
    }
}
