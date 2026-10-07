// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.AllColors
import helium314.keyboard.settings.KeyboardThemePreview
import java.util.EnumMap
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

private val PRESET_PAIRS = listOf(
    0xFF16213E.toInt() to 0xFFEAEAEA.toInt(), // navy / light
    0xFF0F0F0F.toInt() to 0xFF39FF14.toInt(), // black / terminal green
    0xFFF2F2F7.toInt() to 0xFF1C1C1E.toInt(), // light / dark text
    0xFF2D1B4E.toInt() to 0xFFE0BBE4.toInt(), // purple / lavender
    0xFF1B3A2F.toInt() to 0xFFD7F9E9.toInt(), // forest / mint
    0xFF3E2723.toInt() to 0xFFFFD9B3.toInt(), // coffee / cream
)

// color slots of the custom color mode
private const val SLOT_BACKGROUND = 0
private const val SLOT_KEYS = 1
private const val SLOT_FUNCTIONAL = 2
private const val SLOT_ACTION = 3
private const val SLOT_TOOLBAR = 4
private const val SLOT_TEXT = 5

/** "Wähle deine eigene Farbe": keyboard background, letter key background, functional key
 *  background, action (enter/search) key background, top bar background and key text color,
 *  with a live preview that matches the real keyboard. Unset surfaces derive from the
 *  background, so existing two-color setups keep working. */
@Composable
fun TwoColorPickerDialog(
    prefs: android.content.SharedPreferences,
    initialBg: Int?,
    initialText: Int?,
    onDismiss: () -> Unit,
    onApply: (bg: Int?, text: Int?) -> Unit,
) {
    val current = KeyboardTheme.getCustomColors(prefs)
    var colors by remember {
        mutableStateOf(
            if (current.size == 6) current.toList()
            else listOf(
                initialBg ?: PRESET_PAIRS.first().first,
                initialText ?: PRESET_PAIRS.first().second
            ).let { (bg, text) -> deriveFromPair(bg, text) }
        )
    }
    var editingSlot by remember { mutableIntStateOf(SLOT_BACKGROUND) }
    var hue by remember { mutableStateOf(220f) }
    var value by remember { mutableStateOf(0.4f) }

    fun syncHsvFrom(color: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hue = hsv[0]; value = hsv[2]
    }
    fun setTarget(color: Int) {
        val argb = (0xFF000000L or (color.toLong() and 0xFFFFFF)).toInt()
        colors = colors.toMutableList().also { it[editingSlot] = argb }
    }
    fun applyPreset(pBg: Int, pText: Int) {
        colors = deriveFromPair(pBg, pText)
        syncHsvFrom(if (editingSlot == SLOT_TEXT) pText else pBg)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_own_color)) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit {
                    putString(KeyboardTheme.PREF_TWO_COLOR_BACKGROUND, colors[SLOT_BACKGROUND].toString())
                    putString(KeyboardTheme.PREF_TWO_COLOR_TEXT, colors[SLOT_TEXT].toString())
                    putString(KeyboardTheme.PREF_TWO_COLOR_KEYS, colors[SLOT_KEYS].toString())
                    putString(KeyboardTheme.PREF_TWO_COLOR_FUNCTIONAL, colors[SLOT_FUNCTIONAL].toString())
                    putString(KeyboardTheme.PREF_TWO_COLOR_ACTION, colors[SLOT_ACTION].toString())
                    putString(KeyboardTheme.PREF_TWO_COLOR_TOOLBAR, colors[SLOT_TOOLBAR].toString())
                }
                onApply(colors[SLOT_BACKGROUND], colors[SLOT_TEXT])
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit {
                    remove(KeyboardTheme.PREF_TWO_COLOR_BACKGROUND)
                    remove(KeyboardTheme.PREF_TWO_COLOR_TEXT)
                    remove(KeyboardTheme.PREF_TWO_COLOR_KEYS)
                    remove(KeyboardTheme.PREF_TWO_COLOR_FUNCTIONAL)
                    remove(KeyboardTheme.PREF_TWO_COLOR_ACTION)
                    remove(KeyboardTheme.PREF_TWO_COLOR_TOOLBAR)
                }
                onApply(null, null)
                onDismiss()
            }) { Text(stringResource(R.string.two_color_off)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // which color is being edited
                listOf(
                    SLOT_BACKGROUND to R.string.two_color_background_short,
                    SLOT_KEYS to R.string.two_color_keys_short,
                    SLOT_FUNCTIONAL to R.string.two_color_functional_short,
                    SLOT_ACTION to R.string.two_color_action_short,
                    SLOT_TOOLBAR to R.string.two_color_toolbar_short,
                    SLOT_TEXT to R.string.two_color_text_short,
                ).chunked(3).forEach { rowSlots ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        rowSlots.forEach { (slot, labelRes) ->
                            val selected = editingSlot == slot
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) Color(0xFF3D5AFE).copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.15f))
                                    .border(1.dp, if (selected) Color(0xFF3D5AFE) else Color.Gray, RoundedCornerShape(10.dp))
                                    .clickable { editingSlot = slot; syncHsvFrom(colors[slot]) }
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(18.dp).clip(CircleShape)
                                            .background(Color(colors[slot]))
                                            .border(1.dp, Color.Gray, CircleShape)
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(stringResource(labelRes), fontSize = 11.sp, maxLines = 2)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // bar 1: hue
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(List(7) { i -> Color.hsv(i * 60f, 1f, 1f) }))
                        .pointerInput(editingSlot) {
                            fun apply(x: Float, w: Float) {
                                hue = (x / w).coerceIn(0f, 1f) * 360f
                                setTarget(Color.hsv(hue, 1f, value).toArgb())
                            }
                            detectTapGestures { apply(it.x, size.width.toFloat()) }
                            detectDragGestures { change, _ -> apply(change.position.x, size.width.toFloat()) }
                        }
                )
                Spacer(Modifier.height(10.dp))

                // bar 2: brightness (black -> full hue color)
                val hueColor = Color.hsv(hue, 1f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(0f to Color.Black, 1f to hueColor))
                        .pointerInput(editingSlot) {
                            fun apply(x: Float, w: Float) {
                                value = (x / w).coerceIn(0f, 1f)
                                setTarget(Color.hsv(hue, 1f, value).toArgb())
                            }
                            detectTapGestures { apply(it.x, size.width.toFloat()) }
                            detectDragGestures { change, _ -> apply(change.position.x, size.width.toFloat()) }
                        }
                )
                Spacer(Modifier.height(10.dp))

                // hex input + preset pairs
                val currentColor = colors[editingSlot]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var hex by remember(currentColor) { mutableStateOf(String.format("#%06X", currentColor and 0xFFFFFF)) }
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { txt ->
                            hex = txt
                            txt.trim().removePrefix("#").toLongOrNull(16)?.let { v -> setTarget(v.toInt()) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1.6f)
                    )
                    PRESET_PAIRS.forEach { (pBg, pText) ->
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(pBg))
                                .border(1.dp, Color.Gray, CircleShape)
                                .clickable { applyPreset(pBg, pText) }
                        ) {
                            Box(
                                Modifier
                                    .padding(5.dp)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(pText))
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // live keyboard preview, identical to the real keyboard layout
                Text(stringResource(R.string.two_color_preview), fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                KeyboardThemePreview(
                    AllColors(
                        KeyboardTheme.customColorMap(
                            colors[SLOT_BACKGROUND], colors[SLOT_KEYS], colors[SLOT_FUNCTIONAL],
                            colors[SLOT_ACTION], colors[SLOT_TOOLBAR], colors[SLOT_TEXT]
                        ),
                        prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!,
                        prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS),
                        null
                    )
                )
            }
        }
    )
}

/** default derivation for the non-settable surfaces from a background/text pair */
private fun deriveFromPair(bg: Int, text: Int): List<Int> {
    val surface = if (ColorUtils.calculateLuminance(bg) < 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    fun blend(f: Float) = ColorUtils.blendARGB(bg, surface, f)
    return listOf(bg, blend(0.16f), blend(0.08f), blend(0.08f), bg, text)
}
