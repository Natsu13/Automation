package cz.natsu.automation.engine

/**
 * Lets blocks talk to the user while an automation runs (ask for text, pick
 * from a list, show a result). Implemented by the screen that started the run.
 * Each call returns null when the user cancels.
 */
interface RunUi {
    /** [hidden] = a password: the typed text is shown as dots. */
    suspend fun askText(title: String, initial: String, multiline: Boolean, hidden: Boolean = false): String?
    suspend fun choose(title: String, options: List<String>): String?
    suspend fun showText(title: String, text: String): Boolean
    /** Lets the user pick a file of [mime] type; returns its content:// address. */
    suspend fun pickFile(mime: String): String?
    /** Shows images; with [pick] returns the tapped one, otherwise "" after closing. */
    suspend fun showImages(title: String, images: List<String>, pick: Boolean): String?
    /** Speech recognition: returns what the user said. [language] = "cs", "en-GB"…, empty = the phone's. */
    suspend fun dictate(prompt: String, language: String): String? = null
    /** Opens the camera; returns the file:// address of the photo taken. */
    suspend fun takePhoto(): String? = null
    /**
     * Shows the web page at [url] in a dialog; returns the address it was on when the user closed it.
     * [script] (JavaScript) runs in every page once it loads; its call done(value) closes the
     * dialog and [value] is returned instead.
     */
    suspend fun showWeb(title: String, url: String, script: String = "", sheet: Boolean = false): String? = null
    /**
     * Asks where to write: a new file (of [format]: "csv", "json", "txt", or "ask" = offer them all)
     * named [name], or an existing one. Returns its content:// address, which the app may write
     * into from then on.
     */
    suspend fun pickWritable(format: String, name: String): String? = null
    /**
     * The system fingerprint (face) window. With [cipher] the key in the phone's keystore is
     * unlocked for it; [allowDeviceCredential] also offers the screen lock PIN (only without a cipher).
     */
    suspend fun authenticate(
        title: String, subtitle: String, cipher: javax.crypto.Cipher?, allowDeviceCredential: Boolean,
    ): AuthResult = AuthResult.Error(cz.natsu.automation.i18n.tr("ověření jde jen v okně"))

    /**
     * Shows [window] (a page of the automation's own HTML) and waits until it is closed;
     * returns the value the page closed it with ("" when closed by the user).
     */
    suspend fun showHtml(window: HtmlWindow): String? = null

    /** Waits for an NFC tag held to the phone and writes [message] on it; returns the tag's ID. */
    suspend fun writeTag(message: android.nfc.NdefMessage): String? = null
}

sealed interface AuthResult {
    /** Verified; [cipher] can now be used (when one was given). */
    class Ok(val cipher: javax.crypto.Cipher?) : AuthResult
    /** Cancelled by the user (Back, the button under the prompt). */
    data object Cancelled : AuthResult
    class Error(val message: String) : AuthResult
}

/** Thrown when the user cancels a dialog: the run stops quietly, without an error. */
class RunCancelledException : Exception("Zrušeno")
