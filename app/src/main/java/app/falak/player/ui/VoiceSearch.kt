package app.falak.player.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.falak.player.R
import java.util.Locale

/** Starts voice search (the device's speech recognizer); null where there is none. */
val LocalVoiceSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Voice search: said in Arabic or English (a setting, switched from the search box). Either way the
 * search finds names written in the other script too (see CrossScript), so "الجزيرة" said in
 * Arabic finds "Al Jazeera".
 */
object VoiceSearch {
    /** Countries where Arabic is spoken: their own variety is asked for. */
    private val ARAB = setOf("AE", "SA", "EG", "QA", "KW", "BH", "OM", "JO", "LB", "IQ", "SY", "PS", "MA", "DZ", "TN", "LY", "SD", "YE", "MR")

    /** "ar" or "en": the setting, or the device's language when it isn't set. */
    fun language(setting: String, device: Locale = Locale.getDefault()): String = when (setting) {
        "ar", "en" -> setting
        else -> if (device.language == "ar") "ar" else "en"
    }

    /** The language tag for the recognizer ("ar-AE", "en-GB"…), from the device's region where it fits. */
    fun tag(lang: String, device: Locale = Locale.getDefault()): String {
        val region = device.country.uppercase(Locale.ROOT)
        return if (lang == "ar") {
            "ar-" + if (region in ARAB) region else "AE"
        } else {
            if (device.language == "en" && region.isNotEmpty()) "en-$region" else "en-US"
        }
    }

    fun intent(lang: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(lang))
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag(lang))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(
            RecognizerIntent.EXTRA_PROMPT,
            if (lang == "ar") "قل اسم قناة أو فيلم أو مسلسل" else "Say a channel, film or series",
        )
}

/**
 * In the search box: the microphone, and beside it the language it listens for ("ع" / "EN"),
 * which switches between the two.
 */
@Composable
fun VoiceButtons(lang: String, onSpeak: () -> Unit, onLanguage: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSpeak, modifier = Modifier.size(38.dp)) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = if (lang == "ar") "Voice search in Arabic" else "Voice search in English",
                tint = Paper,
                modifier = Modifier.size(20.dp),
            )
        }
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .padding(end = 4.dp)
                .widthIn(min = 30.dp)
                .clip(shape)
                .background(if (focused) Paper else Ink3, shape)
                .border(1.dp, if (focused) Paper else Hairline, shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onLanguage)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (lang == "ar") "ع" else "EN",
                fontFamily = Sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = if (focused) Ink else Muted,
            )
        }
    }
}
