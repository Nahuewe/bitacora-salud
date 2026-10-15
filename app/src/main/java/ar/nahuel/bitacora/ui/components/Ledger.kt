package ar.nahuel.bitacora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

@Composable
fun Rule(modifier: Modifier = Modifier, thickness: Dp = 1.dp, strong: Boolean = false) {
    val ink = LocalInk.current
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(if (strong) ink.ruleStrong else ink.rule),
    )
}

@Composable
fun SectionHeader(label: String, aside: String? = null, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Column(modifier.padding(top = 28.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                label.uppercase(),
                style = Type.sectionLabel,
                color = ink.ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (aside != null) Text(aside, style = Type.figureSmall, color = ink.inkSoft)
        }
        Rule(thickness = 2.dp, strong = true)
    }
}

@Composable
fun LedgerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val ink = LocalInk.current
    val fg = when {
        !enabled -> ink.inkSoft
        filled -> ink.paper
        else -> ink.ink
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .border(1.5.dp, if (enabled) ink.ink else ink.rule)
            .background(if (filled && enabled) ink.ink else ink.paper)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = Type.sectionLabel, color = fg)
    }
}

@Composable
fun LedgerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    val ink = LocalInk.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = Type.small, color = ink.inkSoft)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = Type.figure.copy(color = ink.ink),
            cursorBrush = SolidColor(ink.ink),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(top = 10.dp, bottom = 8.dp)
                .onFocusChanged { focused = it.isFocused },
        )
        Rule(thickness = if (focused) 2.dp else 1.dp, strong = focused)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, valueColorSoft: Boolean = false) {
    val ink = LocalInk.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(key, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = Type.figure, color = if (valueColorSoft) ink.inkSoft else ink.ink)
        }
        Rule()
    }
}
