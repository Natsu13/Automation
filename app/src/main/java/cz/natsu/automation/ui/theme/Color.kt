package cz.natsu.automation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue80 = Color(0xFFAAC7FF)
val BlueGrey80 = Color(0xFFBEC6DC)
val Teal80 = Color(0xFF9CCDE8)

val Blue40 = Color(0xFF415F91)
val BlueGrey40 = Color(0xFF565E71)
val Teal40 = Color(0xFF2C638B)

/** Container / content color pair used for block icons and variable chips. */
data class Accent(val container: Color, val content: Color)

enum class AccentHue(
    private val light: Accent,
    private val dark: Accent,
) {
    Blue(Accent(Color(0xFFD7E3FF), Color(0xFF0E3A73)), Accent(Color(0xFF284777), Color(0xFFD7E3FF))),
    Orange(Accent(Color(0xFFFFDCC2), Color(0xFF6A3A00)), Accent(Color(0xFF6A3A00), Color(0xFFFFDCC2))),
    Green(Accent(Color(0xFFC4EED0), Color(0xFF0B5229)), Accent(Color(0xFF1E5133), Color(0xFFC4EED0))),
    Purple(Accent(Color(0xFFEBDDFF), Color(0xFF4A2B83)), Accent(Color(0xFF4A3478), Color(0xFFEBDDFF))),
    Pink(Accent(Color(0xFFFFD8E7), Color(0xFF7A1D4B)), Accent(Color(0xFF713356), Color(0xFFFFD8E7))),
    Teal(Accent(Color(0xFFC2E8FF), Color(0xFF004C69)), Accent(Color(0xFF004C69), Color(0xFFC2E8FF))),
    Red(Accent(Color(0xFFFFDAD6), Color(0xFF8C1D18)), Accent(Color(0xFF8C1D18), Color(0xFFFFDAD6)));

    @Composable
    fun accent(): Accent = if (isSystemInDarkTheme()) dark else light
}
