package me.krasilnikov.paceassistant

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class AudioFocusHelper(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAcceptsDelayedFocusGain(false)
            .setAudioAttributes(Audio.audioAttributes)
            .build()

    suspend fun withFocus(block: suspend () -> Unit) {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        block()

        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
