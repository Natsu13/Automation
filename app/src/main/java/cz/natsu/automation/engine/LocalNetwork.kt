package cz.natsu.automation.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android 17+ asks the user before an app may talk to devices on the home network. */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
