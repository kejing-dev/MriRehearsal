package io.embodit.mrirehearsal.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.embodit.mrirehearsal.domain.AppLanguage
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.platform.MriGraph
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.scene.MriScene
import io.embodit.mrirehearsal.ui.MriViewModel
import java.util.Locale

/**
 * QA hook: `adb shell am broadcast -a io.embodit.mrirehearsal.DEBUG -p io.embodit.mrirehearsal --es cmd "<command>"`.
 * Only acts in debuggable builds; every command answers on the log tag `MriRehearsal`. The full table is in
 * AGENTS.md. Extras may also be given separately (`--es t 6`); the `key=value` tokens inside `cmd` are enough.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val raw = intent.getStringExtra("cmd") ?: return
        val extras = HashMap<String, String>()
        intent.extras?.let { b -> for (k in b.keySet()) if (k != "cmd") b.get(k)?.let { extras[k.lowercase(Locale.US)] = it.toString() } }
        MriLog.i("CMD recv '$raw' $extras")
        DebugHost.run(raw, extras)
    }
}

/** What the running Stage exposes to the receiver. Broadcasts arrive on the main thread. */
object DebugHost {
    @Volatile var vm: MriViewModel? = null
    @Volatile var scene: MriScene? = null

    fun run(raw: String, extras: Map<String, String>) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val args = HashMap(extras)
        for (t in rest) {
            val i = t.indexOf('=')
            if (i > 0) args[t.substring(0, i).lowercase(Locale.US)] = t.substring(i + 1)
        }
        val word = rest.firstOrNull { !it.contains('=') }
        val vm = vm
        val scene = scene
        if (vm == null || scene == null) {
            MriLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        val head = MriGraph.head
        try {
            when (name) {
                "goto" -> {
                    val screen = Screen.fromTag(word ?: args["s"]) ?: return MriLog.i("CMD goto needs S00..S13")
                    vm.debugGoto(screen, args)
                }
                "posture" -> (word ?: args["p"])?.let { p -> vm.debugPosture(if (p.equals("lie", true)) Posture.LIE else Posture.SIT) }
                "best" -> vm.debugBest(args.mapNotNull { (k, v) -> Level.fromTag(k)?.let { l -> v.toIntOrNull()?.let { l to it.coerceIn(0, 100) } } }.toMap())
                "tour" -> {
                    if (vm.state.value.screen != Screen.S03) vm.debugGoto(Screen.S03, emptyMap())
                    val n = word?.toIntOrNull() ?: 1
                    vm.tourStep(n - 1 - vm.state.value.tourIndex)
                }
                "head" -> {
                    if (word == "0" || word == "reset") {
                        head.offset = V3.ZERO
                        head.offsetDeg = 0f
                        head.offsetPivot = false
                    } else {
                        head.offset = V3(args["dx"]?.toFloatOrNull() ?: 0f, args["dy"]?.toFloatOrNull() ?: 0f, args["dz"]?.toFloatOrNull() ?: 0f)
                        head.offsetDeg = args["deg"]?.toFloatOrNull() ?: 0f
                        head.offsetPivot = args["pivot"] == "1"
                    }
                    MriLog.i("CMD head offset=${head.offset} deg=${head.offsetDeg} pivot=${head.offsetPivot}")
                }
                "lost" -> head.forceLost = on(word ?: args["v"] ?: "1")
                "s" -> word?.toFloatOrNull()?.let { vm.debugSlide(it) }
                "call" -> vm.debugCall()
                "resume" -> vm.resume()
                "finish" -> vm.finishFromPause()
                "result" -> vm.debugResult(args)
                "speed" -> vm.speed = (word?.toFloatOrNull() ?: 1f).coerceIn(0.1f, 50f)
                "freeze" -> vm.debugFreeze(on(word ?: "1"))
                "release" -> vm.debugFreeze(false)
                "lang" -> AppLanguage.fromTag(word)?.let { vm.setLanguage(it) } ?: MriLog.i("CMD lang zh|en")
                "bore" -> word?.toIntOrNull()?.let { cm -> vm.updateSettings { it.copy(boreCm = if (cm >= 65) 70 else 60) } }
                "hint" -> vm.updateSettings { it.copy(motionHint = on(word)) }
                "coil" -> vm.updateSettings { it.copy(headCoil = on(word)) }
                "skip" -> vm.updateSettings { it.copy(skipSlide = on(word)) }
                "mute" -> vm.updateSettings { it.copy(muted = on(word)) }
                "volume" -> word?.toFloatOrNull()?.let { v -> vm.updateSettings { it.copy(volume = v.coerceIn(0f, 1f)) } }
                "captionoffset" -> word?.toFloatOrNull()?.let { vm.captionOffset = it.coerceIn(0.10f, 0.25f) }
                "recenter" -> vm.recenter()
                "testtex" -> vm.debugTestTexture(on(word ?: "1"))
                "hits" -> scene.setRoomHits(on(word ?: "1"))
                "guard" -> { scene.guardOn = on(word ?: "1"); MriLog.i("CMD guard=${scene.guardOn}") }
                "dragcard" -> scene.debugMoveCard(rest.getOrNull(0)?.toFloatOrNull() ?: 0f, rest.getOrNull(1)?.toFloatOrNull() ?: 0f)
                "state" -> MriLog.i(vm.debugStateLine())
                "dump" -> MriLog.i(scene.dumpLine())
                else -> MriLog.i("CMD unknown '$name'")
            }
            MriLog.i("CMD $name done screen=${vm.state.value.screen}")
        } catch (t: Throwable) {
            MriLog.e("CMD $name failed: $t", t)
        }
    }

    private fun on(v: String?): Boolean = v == "1" || v.equals("true", ignoreCase = true) || v.equals("on", ignoreCase = true)
}
