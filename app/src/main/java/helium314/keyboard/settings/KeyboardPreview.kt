// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/** stylized keyboard rendering that closely mirrors the real keyboard layout
 *  (German QWERTZ, bottom row with ?123 / comma / emoji / space / mic / period / action key),
 *  so theme previews show what the keyboard will actually look like */
@Composable
fun KeyboardThemePreview(colors: Colors, modifier: Modifier = Modifier) {
    val keyShape = RoundedCornerShape(4.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(colors.get(ColorType.MAIN_BACKGROUND)))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // top bar with the toolbar expand key on the left, like the real keyboard
        Row(
            Modifier.fillMaxWidth().padding(bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 22.dp, height = 16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(colors.get(ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ">",
                    color = Color(colors.get(ColorType.TOOL_BAR_EXPAND_KEY)),
                    fontSize = 9.sp
                )
            }
        }
        PreviewKeyRow(colors, keyShape, "qwertzuiop", hints = "1234567890")
        PreviewKeyRow(colors, keyShape, "asdfghjklä")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewKey(colors, keyShape, "⇧", functional = true, modifier = Modifier.weight(1.5f))
            PreviewKeyRow(colors, keyShape, "yxcvbnmß", modifier = Modifier.weight(8f))
            PreviewKey(colors, keyShape, "⌫", functional = true, modifier = Modifier.weight(1.5f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewKey(colors, keyShape, "?123", functional = true, modifier = Modifier.weight(1.5f), fontSize = 8.sp)
            PreviewKey(colors, keyShape, ",", functional = true, modifier = Modifier.weight(1f))
            PreviewKey(colors, keyShape, "☺\uFE0E", functional = true, modifier = Modifier.weight(1f), fontSize = 10.sp)
            PreviewKey(
                colors, keyShape, "Deutsch", functional = false, modifier = Modifier.weight(3.5f),
                textType = ColorType.SPACE_BAR_TEXT, backgroundType = ColorType.SPACE_BAR_BACKGROUND,
                fontSize = 9.sp
            )
            MicKey(colors, keyShape, Modifier.weight(1f))
            PreviewKey(colors, keyShape, ".", functional = true, modifier = Modifier.weight(1f))
            PreviewKey(
                colors, keyShape, "⏎", functional = false, modifier = Modifier.weight(1.5f),
                textType = ColorType.ACTION_KEY_ICON, backgroundType = ColorType.ACTION_KEY_BACKGROUND
            )
        }
    }
}

@Composable
private fun PreviewKeyRow(
    colors: Colors,
    keyShape: RoundedCornerShape,
    keys: String,
    modifier: Modifier = Modifier,
    hints: String? = null
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEachIndexed { index, ch ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(keyShape)
                    .background(Color(colors.get(ColorType.KEY_BACKGROUND))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ch.toString(),
                    color = Color(colors.get(ColorType.KEY_TEXT)),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                hints?.getOrNull(index)?.let { hint ->
                    Text(
                        hint.toString(),
                        color = Color(colors.get(ColorType.KEY_HINT_TEXT)),
                        fontSize = 6.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewKey(
    colors: Colors,
    keyShape: RoundedCornerShape,
    label: String,
    functional: Boolean,
    modifier: Modifier = Modifier,
    textType: ColorType = if (functional) ColorType.FUNCTIONAL_KEY_TEXT else ColorType.KEY_TEXT,
    backgroundType: ColorType = if (functional) ColorType.FUNCTIONAL_KEY_BACKGROUND else ColorType.KEY_BACKGROUND,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
    Box(
        modifier
            .clip(keyShape)
            .background(Color(colors.get(backgroundType))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color(colors.get(textType)),
            fontSize = fontSize,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}

/** simple monochrome mic glyph (capsule + base), matching the real keyboard's icon */
@Composable
private fun MicKey(colors: Colors, keyShape: RoundedCornerShape, modifier: Modifier = Modifier) {
    val iconColor = Color(colors.get(ColorType.FUNCTIONAL_KEY_TEXT))
    Box(
        modifier
            .clip(keyShape)
            .background(Color(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(5.dp).height(9.dp).clip(RoundedCornerShape(2.5.dp)).background(iconColor))
            Box(Modifier.padding(top = 1.dp).width(9.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(iconColor))
        }
    }
}
