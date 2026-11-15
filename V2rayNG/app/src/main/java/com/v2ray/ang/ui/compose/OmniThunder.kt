package com.v2ray.ang.ui.compose

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.util.concurrent.ConcurrentHashMap

/** Original thunder envelopes/noise rendered offline. Sonification honors volume and silent mode.
 * One bounded application-level pool; no microphone, network, permission or audio-focus capture.
 */
internal object OmniThunder {
    private var pool: SoundPool? = null
    private var on = 0
    private var off = 0
    private val ready = ConcurrentHashMap.newKeySet<Int>()
    @Synchronized fun preload(context: Context) {
        if (pool != null) return
        val sound = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        sound.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        context.assets.openFd("omni-reference/audio/power-on.wav").use { on = sound.load(it, 1) }
        context.assets.openFd("omni-reference/audio/power-off.wav").use { off = sound.load(it, 1) }
        pool = sound
    }
    @Synchronized fun play(context: Context, turningOn: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = if (turningOn) on else off
        if (id in ready) pool?.play(id, 1f, 1f, 1, 0, 1f)
    }
}
