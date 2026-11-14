package cz.natsu.automation.engine

import android.os.Build

/**
 * Android 17 (API 37) "background audio hardening": volume / ringer changes and
 * audio focus from an app without a visible window (or a foreground service
 * with "while in use" capability) are ignored – no exception, nothing happens.
 */
object AudioHardening {
    val active: Boolean get() = Build.VERSION.SDK_INT >= 37

    /** Error for a change the system ignored while the automation ran without a window. */
    fun ignored(what: String) = BlockException(
        "Android nedovolil $what na pozadí. Povol aplikaci zobrazení přes jiné aplikace, " +
                "pak se taková akce spustí v okně a projde.",
        Requirement.Overlay
    )
}
