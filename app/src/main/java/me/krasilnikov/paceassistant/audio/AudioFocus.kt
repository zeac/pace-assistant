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

package me.krasilnikov.paceassistant.audio

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

interface AudioFocus {
    suspend fun withFocus(block: suspend () -> Unit)
}

@RequiresApi(Build.VERSION_CODES.O)
class AudioFocus26(context: Context) : AudioFocus {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAcceptsDelayedFocusGain(false)
            .setAudioAttributes(Audio.audioAttributes)
            .build()

    override suspend fun withFocus(block: suspend () -> Unit) {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        block()

        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}

@Suppress("DEPRECATION")
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class AudioFocus8(context: Context) : AudioFocus {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { }

    override suspend fun withFocus(block: suspend () -> Unit) {
        val result = audioManager.requestAudioFocus(
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        block()

        audioManager.abandonAudioFocus(listener)
    }
}
