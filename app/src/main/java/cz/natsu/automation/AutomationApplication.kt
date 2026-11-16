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
    }

    val repository: AutomationRepository by lazy {
        AutomationRepository(this, AutomationDatabase.build(this).automationDao())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cz.natsu.automation.i18n.I18n.load(this)

        DynamicColors.applyToActivitiesIfAvailable(this)
        repository.appScope.launch {
            cz.natsu.automation.trigger.MoreEvents.syncNfc(
                this@AutomationApplication,
                cz.natsu.automation.data.EventType.NfcTag in repository.usedEventTypes(), renew = true,
            )
        }
    }
}
