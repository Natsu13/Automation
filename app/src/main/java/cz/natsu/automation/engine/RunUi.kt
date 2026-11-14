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
}

/** Thrown when the user cancels a dialog: the run stops quietly, without an error. */
class RunCancelledException : Exception("Zrušeno")
