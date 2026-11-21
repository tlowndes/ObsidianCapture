package uk.tomlowndes.capture

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Fold the extra doors (fold-doors-v1): duplicate widgets leave Android's widget picker. One already on a home
 * screen stays and keeps working; it only leaves the picker once it's gone from every screen. "Show every widget"
 * in Settings puts them all back.
 */
object WidgetFold {
    private val FOLDED = listOf(
        NextStepWidgetReceiver::class.java, ReadWidgetReceiver::class.java, CombinedWidgetReceiver::class.java,
        DeckCaptureWidgetReceiver::class.java, ChoresWidgetReceiver::class.java, ShoppingWidgetReceiver::class.java,
    )

    fun apply(ctx: Context, prefs: AppPrefs) = runCatching {
        val pm = ctx.packageManager
        val awm = AppWidgetManager.getInstance(ctx)
        FOLDED.forEach { c ->
            val name = ComponentName(ctx, c)
            val want = if (prefs.allWidgets || awm.getAppWidgetIds(name).isNotEmpty()) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(name) != want) pm.setComponentEnabledSetting(name, want, PackageManager.DONT_KILL_APP)
        }
    }
}
