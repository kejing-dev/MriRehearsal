package io.embodit.mrirehearsal.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Typography
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.mrirehearsal.domain.AppLanguage
import java.util.Locale

/** Colours of the design package (`palette`): the scene, and the few status accents the panels need. */
object MriColors {
    // scene
    val Wall = Color(0xFFF2EFEA)
    val Floor = Color(0xFFC9CCCF)
    val Gantry = Color(0xFFF7F7F5)
    val GantryBand = Color(0xFF9FC5E8)
    val BoreInner = Color(0xFFE3E4E2)
    val BoreLight = Color(0xFFFFF1D6)
    val BoreLightWarm = Color(0xFFFFC98A)
    val Table = Color(0xFFFAFAFA)
    val Pillow = Color(0xFFBFD7EA)
    val CallBall = Color(0xFF2F6DB5)
    val Coil = Color(0xFFF4F4F2)
    val Speaker = Color(0xFF5A6068)
    val Brand = Color(0xFF2F6DB5)
    val WindowGlass = Color(0xFF1E2A33)
    val WindowWarm = Color(0xFFF6D9A8)
    val TourHighlight = Color(0xFF5AA9E6)

    // ui (status colours; the 16 colour-scheme roles stay PicoTheme's)
    val Accent = Color(0xFF2F6DB5)
    val Still = Color(0xFFFFFFFF)
    val Minor = Color(0xFFE0A030)
    val Major = Color(0xFFD9480F)
    val GradeGood = Color(0xFF2E8B57)
    val GradeRetry = Color(0xFFD9480F)
}

/**
 * PicoTheme over the system (Vibrant) scheme with the design's accent as the interaction role, and Noto Sans /
 * Noto Sans SC in place of the system family. The language is the one chosen in the app.
 */
@Composable
fun MriTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors = remember(system) { system.copy(interaction = MriColors.Accent) }
    PicoTheme(colorScheme = colors, content = content)
}

// ---------------------------------------------------------------------------------------------
// In-app language: resources and typeface resolved in the language picked in settings.
// ---------------------------------------------------------------------------------------------

val LocalAppResources = staticCompositionLocalOf<Resources> { error("AppLocale not provided") }
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ZH }

private fun AppLanguage.locale(): Locale =
    when (this) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.ENGLISH
    }

fun Context.localizedResources(language: AppLanguage): Resources {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(language.locale())
    return createConfigurationContext(configuration).resources
}

/** Provides string resources and the matching Noto family in [language]. Call inside [MriTheme]. */
@Composable
fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val resources = remember(context, language) { context.localizedResources(language) }
    CompositionLocalProvider(LocalAppResources provides resources, LocalAppLanguage provides language) {
        val base = PicoTheme.typography
        val typography = rememberNotoTypography(base, language)
        PicoTheme(typography = typography, content = content)
    }
}

@Composable
@ReadOnlyComposable
fun str(@StringRes id: Int): String = LocalAppResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun str(@StringRes id: Int, vararg args: Any): String = LocalAppResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun strArray(@ArrayRes id: Int): Array<String> = LocalAppResources.current.getStringArray(id)

private const val LATIN_FONT = "NotoSans-VF.ttf"
private const val CHINESE_FONT = "NotoSansSC-VF.ttf"
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun Context.notoFontFamily(language: AppLanguage): FontFamily? {
    val file = if (language == AppLanguage.ZH) CHINESE_FONT else LATIN_FONT
    val bundled = runCatching { assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
    if (file !in bundled) return null
    return FontFamily(
        WEIGHTS.map { weight ->
            Font(
                path = "fonts/$file",
                assetManager = assets,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        }
    )
}

@Composable
private fun rememberNotoTypography(base: Typography, language: AppLanguage): Typography {
    val context = LocalContext.current
    return remember(context, base, language) {
        val family = context.notoFontFamily(language) ?: return@remember base
        fun TextStyle.inFamily(): TextStyle = copy(fontFamily = family)
        base.copy(
            displayLarge = base.displayLarge.inFamily(),
            displayMedium = base.displayMedium.inFamily(),
            displaySmall = base.displaySmall.inFamily(),
            headlineLarge = base.headlineLarge.inFamily(),
            headlineMedium = base.headlineMedium.inFamily(),
            headlineSmall = base.headlineSmall.inFamily(),
            titleLarge = base.titleLarge.inFamily(),
            titleMedium = base.titleMedium.inFamily(),
            titleSmall = base.titleSmall.inFamily(),
            bodyLarge = base.bodyLarge.inFamily(),
            bodyLargeMultiline = base.bodyLargeMultiline.inFamily(),
            bodyMedium = base.bodyMedium.inFamily(),
            bodyMediumMultiline = base.bodyMediumMultiline.inFamily(),
            bodySmall = base.bodySmall.inFamily(),
            bodyTiny = base.bodyTiny.inFamily(),
            labelLarge = base.labelLarge.inFamily(),
            labelMedium = base.labelMedium.inFamily(),
            labelSmall = base.labelSmall.inFamily(),
        )
    }
}
