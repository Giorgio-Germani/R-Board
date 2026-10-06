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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import kotlin.math.roundToInt

private val PRESET_PAIRS = listOf(
    0xFF16213E.toInt() to 0xFFEAEAEA.toInt(), // navy / light
    0xFF0F0F0F.toInt() to 0xFF39FF14.toInt(), // black / terminal green
    0xFFF2F2F7.toInt() to 0xFF1C1C1E.toInt(), // light / dark text
    0xFF2D1B4E.toInt() to 0xFFE0BBE4.toInt(), // purple / lavender
    0xFF1B3A2F.toInt() to 0xFFD7F9E9.toInt(), // forest / mint
    0xFF3E2723.toInt() to 0xFFFFD9B3.toInt(), // coffee / cream
)

/** "Wähle deine eigene Farbe": background + key text picker with live keyboard preview */
@Composable
fun TwoColorPickerDialog(
    prefs: android.content.SharedPreferences,
    initialBg: Int?,
    initialText: Int?,
    onDismiss: () -> Unit,
    onApply: (bg: Int?, text: Int?) -> Unit,
) {
    var bgColor by remember { mutableStateOf(initialBg ?: PRESET_PAIRS.first().first) }
    var textColor by remember { mutableStateOf(initialText ?: PRESET_PAIRS.first().second) }
    var editingBackground by remember { mutableStateOf(true) }
    var hue by remember { mutableStateOf(220f) }
    var sat by remember { mutableStateOf(0.6f) }
    var value by remember { mutableStateOf(0.3f) }

    fun syncHsvFrom(color: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }
    fun setTarget(color: Int) {
        val argb = 0xFF000000L or (color.toLong() and 0xFFFFFF)
        if (editingBackground) bgColor = argb.toInt() else textColor = argb.toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_own_color)) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putString(KeyboardTheme.PREF_TWO_COLOR_BACKGROUND, bgColor.toString())
                    .putString(KeyboardTheme.PREF_TWO_COLOR_TEXT, textColor.toString())
                    .apply()
                onApply(bgColor, textColor)
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit()
                    .remove(KeyboardTheme.PREF_TWO_COLOR_BACKGROUND)
                    .remove(KeyboardTheme.PREF_TWO_COLOR_TEXT)
                    .apply()
                onApply(null, null)
                onDismiss()
            }) { Text(stringResource(R.string.two_color_off)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // which color is being edited
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (editingBackground) Color(0xFF3D5AFE).copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.15f))
                            .border(1.dp, if (editingBackground) Color(0xFF3D5AFE) else Color.Gray, RoundedCornerShape(10.dp))
                            .clickable { editingBackground = true; syncHsvFrom(bgColor) }
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(bgColor)).border(1.dp, Color.Gray, CircleShape))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.two_color_background_short), fontSize = 13.sp)
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!editingBackground) Color(0xFF3D5AFE).copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.15f))
                            .border(1.dp, if (!editingBackground) Color(0xFF3D5AFE) else Color.Gray, RoundedCornerShape(10.dp))
                            .clickable { editingBackground = false; syncHsvFrom(textColor) }
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(textColor)).border(1.dp, Color.Gray, CircleShape))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.two_color_text_short), fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // saturation/value square
                val hueColor = Color.hsv(hue, 1f, 1f)
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(0f to Color.White, 1f to Color.Black))
                        .background(Brush.horizontalGradient(0f to Color.White, 1f to hueColor))
                        .border(1.dp, Color.Gray, RoundedCornerShape(10.dp))
                        .pointerInput(editingBackground) {
                            fun apply(x: Float, y: Float, w: Float, h: Float) {
                                sat = (x / w).coerceIn(0f, 1f)
                                value = 1f - (y / h).coerceIn(0f, 1f)
                                setTarget(Color.hsv(hue, sat, value).toArgb())
                            }
                            detectTapGestures { apply(it.x, it.y, size.width.toFloat(), size.height.toFloat()) }
                            detectDragGestures { change, _ -> apply(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat()) }
                        }
                ) {
                    Box(
                        Modifier
                            .offset(x = (maxWidth - 14.dp) * sat, y = (maxHeight - 14.dp) * (1f - value))
                            .size(14.dp)
                            .border(2.dp, Color.White, CircleShape)
                    )
                }
                Spacer(Modifier.height(10.dp))

                // hue bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(List(7) { i -> Color.hsv(i * 60f, 1f, 1f) }))
                        .pointerInput(editingBackground) {
                            fun apply(x: Float, w: Float) {
                                hue = (x / w).coerceIn(0f, 1f) * 360f
                                setTarget(Color.hsv(hue, sat, value).toArgb())
                            }
                            detectTapGestures { apply(it.x, size.width.toFloat()) }
                            detectDragGestures { change, _ -> apply(change.position.x, size.width.toFloat()) }
                        }
                )
                Spacer(Modifier.height(10.dp))

                // hex input + preset pairs
                val current = if (editingBackground) bgColor else textColor
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var hex by remember(current) { mutableStateOf(String.format("#%06X", current and 0xFFFFFF)) }
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
                                .clickable {
                                    bgColor = pBg
                                    textColor = pText
                                    syncHsvFrom(if (editingBackground) bgColor else textColor)
                                }
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

                // live keyboard preview
                Text(stringResource(R.string.two_color_preview), fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                KeyboardMockPreview(
                    background = bgColor,
                    keyBackground = ColorUtils.blendARGB(bgColor, 0xFFFFFFFF.toInt(), 0.16f),
                    functionalKeyBackground = ColorUtils.blendARGB(bgColor, 0xFFFFFFFF.toInt(), 0.08f),
                    text = textColor
                )
            }
        }
    )
}

/** small stylized keyboard rendering with the two-color derivation */
@Composable
private fun KeyboardMockPreview(background: Int, keyBackground: Int, functionalKeyBackground: Int, text: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(background))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(Color(keyBackground)))
            }
        }
        listOf("qwertzuiopü", "asdfghjklöä", "⇧yxcvbnmß⌫").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { ch ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(keyBackground)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            ch.toString(), color = Color(text), fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 9.dp)
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("123", ",", "☺").forEach {
                Box(
                    Modifier
                        .weight(0.9f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(functionalKeyBackground)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(it, color = Color(text), fontSize = 11.sp, modifier = Modifier.padding(vertical = 9.dp))
                }
            }
            Box(
                Modifier
                    .weight(4f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(keyBackground)),
                contentAlignment = Alignment.Center
            ) {
                Text("Deutsch", color = Color(text), fontSize = 11.sp, modifier = Modifier.padding(vertical = 9.dp))
            }
            Box(
                Modifier
                    .weight(0.9f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(keyBackground)),
                contentAlignment = Alignment.Center
            ) {
                Text(".", color = Color(text), fontSize = 11.sp, modifier = Modifier.padding(vertical = 9.dp))
            }
            Box(
                Modifier
                    .weight(1.3f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(functionalKeyBackground)),
                contentAlignment = Alignment.Center
            ) {
                Text("⏎", color = Color(text), fontSize = 11.sp, modifier = Modifier.padding(vertical = 9.dp))
            }
        }
    }
}
