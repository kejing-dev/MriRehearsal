package io.embodit.mrirehearsal.platform

import android.app.Application
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.mrirehearsal.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
