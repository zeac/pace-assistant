package me.krasilnikov.paceassistant

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
