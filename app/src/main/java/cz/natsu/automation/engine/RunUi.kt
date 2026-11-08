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
}

/** Thrown when the user cancels a dialog: the run stops quietly, without an error. */
class RunCancelledException : Exception("Zrušeno")
