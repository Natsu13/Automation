package cz.natsu.automation.engine

/**
 * Lets blocks talk to the user while an automation runs (ask for text, pick
 * from a list, show a result). Implemented by the screen that started the run.
 * Each call returns null when the user cancels.
 */
interface RunUi {
    suspend fun askText(title: String, initial: String, multiline: Boolean): String?
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
    suspend fun showWeb(title: String, url: String, script: String = ""): String? = null
    /** Waits for an NFC tag held to the phone and writes [message] on it; returns the tag's ID. */
    suspend fun writeTag(message: android.nfc.NdefMessage): String? = null
}

/** Thrown when the user cancels a dialog: the run stops quietly, without an error. */
class RunCancelledException : Exception("Zrušeno")
