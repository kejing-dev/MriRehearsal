package io.embodit.mrirehearsal.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import io.embodit.mrirehearsal.domain.SeqType

/**
 * Scan noise and cues with the native Android players (design `audio.playback`): no head-locked spatialisation,
 * the noise surrounds the head anyway. SoundPool loops the short beds (T1 / T2 / EPI 10 s, gap 8 s, motor 4 s) and
 * plays the cues; MediaPlayer loops the 20 s localiser. All files are pre-rendered by tools/make_audio.py.
 *
 * Every call comes from the main thread (the ViewModel). [release] frees both players.
 */
class ScanAudio(context: Context) {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private var loc: MediaPlayer? = null
    private var bedStream = 0
    private var motorStream = 0
    private var bed: SeqType? = null
    private var volume = 0.6f

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
            MriLog.i("AUDIO loaded id=$sampleId status=$status (${loaded.size}/${ids.size})")
        }
        for (name in listOf("t1", "t2", "epi", "gap", "motor", "tick", "call")) {
            runCatching { app.assets.openFd("audio/$name.wav").use { fd -> ids[name] = pool.load(fd, 1) } }.onFailure { MriLog.e("AUDIO load $name failed: $it") }
        }
        loc =
            runCatching {
                MediaPlayer().apply {
                    app.assets.openFd("audio/loc.wav").use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                    setAudioAttributes(attributes)
                    isLooping = true
                    prepare()
                }
            }.onFailure { MriLog.e("AUDIO loc failed: $it") }.getOrNull()
    }

    /** Effective volume: the setting, or 0 when muted. Applies to whatever is playing. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (bedStream != 0) pool.setVolume(bedStream, volume, volume)
        if (motorStream != 0) pool.setVolume(motorStream, volume * MOTOR_GAIN, volume * MOTOR_GAIN)
        runCatching { loc?.setVolume(volume, volume) }
    }

    /** Starts the bed of [type] (the localiser from its start), stopping the previous one. */
    fun playBed(type: SeqType) {
        stopBed()
        bed = type
        if (type == SeqType.LOC) {
            runCatching {
                loc?.apply {
                    seekTo(0)
                    setVolume(volume, volume)
                    start()
                }
            }
        } else {
            val id = ids[type.name.lowercase()] ?: return
            bedStream = pool.play(id, volume, volume, 1, -1, 1f)
        }
        MriLog.i("AUDIO bed $type stream=$bedStream vol=$volume")
    }

    fun stopBed() {
        if (bedStream != 0) pool.stop(bedStream)
        bedStream = 0
        runCatching { if (loc?.isPlaying == true) loc?.pause() }
        if (bed != null) MriLog.i("AUDIO bed stop")
        bed = null
    }

    fun motor(on: Boolean) {
        if (on && motorStream == 0) {
            val id = ids["motor"] ?: return
            motorStream = pool.play(id, volume * MOTOR_GAIN, volume * MOTOR_GAIN, 0, -1, 1f)
        } else if (!on && motorStream != 0) {
            pool.stop(motorStream)
            motorStream = 0
        }
    }

    fun tick() = cue("tick")

    fun call() = cue("call")

    private fun cue(name: String) {
        val id = ids[name] ?: return
        if (volume > 0f) pool.play(id, volume, volume, 2, 0, 1f)
    }

    fun stopAll() {
        stopBed()
        motor(false)
    }

    fun release() {
        stopAll()
        runCatching { loc?.release() }
        loc = null
        pool.release()
    }

    private companion object {
        const val MOTOR_GAIN = 0.6f
    }
}
