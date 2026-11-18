package io.embodit.mrirehearsal.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.mrirehearsal.domain.SeqType

/**
 * Scan noise and cues with the native Android players (design `audio.playback`): no head-locked spatialisation,
 * the noise surrounds the head anyway. SoundPool loops every bed (localiser ≈ 5 s, T1 / T2 / EPI ≈ 10 s, gap ≈ 8 s,
 * motor ≈ 1.2 s) and plays the cues. The scan-room loops are CC0 freesound recordings cut by tools/cut_cc0.py (sources
 * in its docstring); the UI cues tick / call come from tools/make_audio.py.
 *
 * v0.1.1 (versionCode 3): the localiser no longer loops in a MediaPlayer. On the emulator its loop restart put a click
 * into the recording at every seam (a 0.23 step against a median of 0.01) while the same file looped offline and in
 * SoundPool is seamless.
 *
 * Every call comes from the main thread (the ViewModel). [release] frees both players.
 */
class ScanAudio(context: Context) {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private var bedStream = 0
    private var motorStream = 0
    private var bed: SeqType? = null
    private var volume = 0.6f

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
            MriLog.i("AUDIO loaded id=$sampleId status=$status (${loaded.size}/${ids.size})")
        }
        for (name in listOf("loc", "t1", "t2", "epi", "gap", "motor", "tick", "call")) {
            runCatching { app.assets.openFd("audio/$name.wav").use { fd -> ids[name] = pool.load(fd, 1) } }.onFailure { MriLog.e("AUDIO load $name failed: $it") }
        }
    }

    /** Effective volume: the setting, or 0 when muted. Applies to whatever is playing. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (bedStream != 0) pool.setVolume(bedStream, volume, volume)
        if (motorStream != 0) pool.setVolume(motorStream, volume * MOTOR_GAIN, volume * MOTOR_GAIN)
    }

    /** Starts the bed of [type] from its start, stopping the previous one. */
    fun playBed(type: SeqType) {
        stopBed()
        bed = type
        val id = ids[type.name.lowercase()] ?: return
        bedStream = pool.play(id, volume, volume, 1, -1, 1f)
        MriLog.i("AUDIO bed $type stream=$bedStream vol=$volume")
    }

    fun stopBed() {
        if (bedStream != 0) pool.stop(bedStream)
        bedStream = 0
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
        pool.release()
    }

    private companion object {
        /** The table motor as loud as the beds (v0.1.1: at 0.6 it pulled the sounding RMS under -20 dBFS). */
        const val MOTOR_GAIN = 1.0f
    }
}
