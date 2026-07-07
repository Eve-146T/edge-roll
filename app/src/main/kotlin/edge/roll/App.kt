package edge.roll

import android.app.Application
import edge.roll.core.Haptics
import edge.roll.core.Scores
import edge.roll.core.SoundFx

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scores.init(this)
        // Renderer benchmarks (adb: `setprop debug.edgeroll.bench 1`) skip audio/haptics
        // so the background DSP synth thread doesn't contend for CPU and pollute the
        // frame-time measurement. No effect on normal play.
        if (!boolProp("debug.edgeroll.bench")) {
            Haptics.init(this)
            SoundFx.init(this)
        }
    }

    private fun boolProp(key: String): Boolean = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as String) == "1"
    } catch (e: Throwable) { false }
}
