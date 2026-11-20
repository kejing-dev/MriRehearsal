package io.embodit.mrirehearsal.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.mrirehearsal.mainApp
import io.embodit.mrirehearsal.ui.MriShared

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // D60 补 (QA #5 q5-2): controller B = system BACK. It never exits or sends the scan room to the background: the
        // window closed = open it again, otherwise nothing (logged). Caught at the earliest layer of every window
        // (BackKeys); the OnBackPressedCallback stays as the last fallback.
        BackKeys.handler = { where -> MriShared.onBack(where) }
        BackKeys.traceOn = true
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    val ca = a as? ComponentActivity
                    if (ca == null) {
                        MriLog.w("BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity")
                        return
                    }
                    ca.onBackPressedDispatcher.addCallback(
                        ca,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() {
                                BackKeys.handler("dispatcher:${a.javaClass.simpleName}")
                            }
                        },
                    )
                    MriLog.i("BACK guard on ${a.javaClass.simpleName}")
                }

                override fun onActivityPostCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("created")
                }

                override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")

                override fun onActivityResumed(a: Activity) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("resumed")
                }

                override fun onActivityPostResumed(a: Activity) = BackKeys.sweep("post-resumed")

                override fun onActivityPaused(a: Activity) {}

                override fun onActivityStopped(a: Activity) {}

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}

                override fun onActivityDestroyed(a: Activity) {}
            },
        )
        launch(::mainApp)
    }
}

class LaunchActivity : SpatialLaunchActivity()

/** One log tag for the whole app: `adb logcat -s MriRehearsal`. */
object MriLog {
    private const val TAG = "MriRehearsal"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
    }
}

/** Process-wide platform services. The scene starts and stops the trackers (every start has a stop). */
object MriGraph {
    val head = HeadTracker()
    val controller = ControllerInput()
}
