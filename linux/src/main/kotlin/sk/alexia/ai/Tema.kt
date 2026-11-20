package sk.alexia.ai

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Farby {
    val pozadie = Color(0xFF0B0B12)
    val plocha = Color(0xFF16161F)
    val plocha2 = Color(0xFF20202C)
    val okraj = Color(0xFF2C2C3A)
    val text = Color(0xFFF2F2F7)
    val tlmene = Color(0xFF9A9AB0)
    val fialova = Color(0xFF7C5CFF)
    val ruzova = Color(0xFFFF4DC4)
    val zelena = Color(0xFF3DDC97)
    val cervena = Color(0xFFFF5C7A)
    val prechod = Brush.linearGradient(listOf(fialova, ruzova))
    val prechodJemny = Brush.linearGradient(listOf(fialova.copy(alpha = 0.25f), ruzova.copy(alpha = 0.18f)))
}

@Composable
fun AlexiaTema(obsah: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tmavy = isSystemInDarkTheme()   // appka je vždy tmavá
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Farby.fialova,
            secondary = Farby.ruzova,
            background = Farby.pozadie,
            surface = Farby.plocha,
            surfaceVariant = Farby.plocha2,
            onBackground = Farby.text,
            onSurface = Farby.text,
            onSurfaceVariant = Farby.tlmene,
            outline = Farby.okraj,
            error = Farby.cervena,
        ),
        typography = Typography(),
        content = obsah,
    )
}
