package cz.natsu.automation.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.EventType
import kotlinx.coroutines.launch

/** After a reboot or an app update: start the background service, plan time events, fire "Po zapnutí telefonu". */
class EventBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val repository = (app as AutomationApplication).repository
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                repository.appScope.launch {
                    try {
                        BackgroundService.sync(app)
                        val all = repository.getAll()
                        EventAlarms.scheduleAll(app, all)
                        // The system forgets watched places with a reboot.
                        MoreEvents.sync(app, all, afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED)
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                            EventDispatcher.fire(app, EventType.Boot).join()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
