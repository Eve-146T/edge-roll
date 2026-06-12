package edge.roll

import android.app.Application
import edge.roll.core.Haptics
import edge.roll.core.Scores
import edge.roll.core.SoundFx

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scores.init(this)
        Haptics.init(this)
        SoundFx.init(this)
    }
}
