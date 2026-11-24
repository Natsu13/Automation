package cz.natsu.automation

import android.app.Application
import com.google.android.material.color.DynamicColors
import cz.natsu.automation.data.AutomationDatabase
import cz.natsu.automation.data.AutomationRepository
import kotlinx.coroutines.launch

class AutomationApplication : Application() {
    companion object {
        lateinit var instance: AutomationApplication
            private set

        private var started = 0

        /** Some window of the app is on screen (it may open other windows then). */
        val visible: Boolean get() = started > 0
    }

    val repository: AutomationRepository by lazy {
        AutomationRepository(this, AutomationDatabase.build(this).automationDao())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cz.natsu.automation.i18n.I18n.load(this)
        cz.natsu.automation.data.HomeCards.load(this)
        cz.natsu.automation.data.ScheduledRuns.rescheduleAll(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { started++ }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })

        DynamicColors.applyToActivitiesIfAvailable(this)
        repository.appScope.launch {
            cz.natsu.automation.trigger.MoreEvents.syncNfc(
                this@AutomationApplication,
                cz.natsu.automation.data.EventType.NfcTag in repository.usedEventTypes(), renew = true,
            )
        }
    }
}
