package com.example.smartroomdashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared e-ink building blocks.
 *
 * These replace the white-card-with-a-black-border block that was copy-pasted into
 * three screens, and they restore the touch feedback that `LocalRippleConfiguration
 * provides null` removes.
 *
 * Feedback rule: a press darkens the *fill* to a light grey and thickens the
 * border, but never to black. A black resting fill is unreadable and some Boox
 * firmware inverts it.
 */

private val CardShape = RoundedCornerShape(4.dp)

/** Standard bordered surface used for cards, list rows and detail panels. */
@Composable
fun EinkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CardShape,
    fill: Color = MaterialTheme.colorScheme.surface,
    borderWidth: Dp = 2.dp,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable () -> Unit,
) {
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
    Box(
        modifier
            .background(fill, shape)
            .border(borderWidth, borderColor, shape)
            .then(clickable),
    ) {
        content()
    }
}

/**
 * Primary action. White fill, black border, darkens on press.
 *
 * Deliberately still not a filled-black button: the distinction between "primary"
 * and "secondary" here is border weight and label case, not fill.
 */
@Composable
fun EinkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 60.dp,
    content: @Composable () -> Unit,
) {
    val pressed = interactionPressed()
    val colors = MaterialTheme.colorScheme
    val fill = when {
        !enabled -> colors.surfaceVariant
        pressed -> colors.primaryContainer
        else -> colors.surface
    }
    val contentColor = if (enabled) colors.onSurface else colors.onSurfaceVariant

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = CardShape,
        border = BorderStroke(if (pressed && enabled) 3.dp else 2.dp, colors.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = contentColor,
            disabledContainerColor = colors.surfaceVariant,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        interactionSource = remember { MutableInteractionSource() },
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}

/** Secondary action. Lighter border, same press feedback. */
@Composable
fun EinkOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 60.dp,
    content: @Composable () -> Unit,
) {
    val pressed = interactionPressed()
    val colors = MaterialTheme.colorScheme
    val fill = if (pressed && enabled) colors.primaryContainer else colors.surface
    val contentColor = if (enabled) colors.onSurface else colors.onSurfaceVariant

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = CardShape,
        border = BorderStroke(1.dp, if (enabled) colors.outlineVariant else colors.surfaceVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = fill,
            contentColor = contentColor,
            disabledContainerColor = colors.surface,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        interactionSource = remember { MutableInteractionSource() },
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}

/** Observes press state for a control that manages its own click handling. */
@Composable
fun interactionPressed(interaction: MutableInteractionSource = remember { MutableInteractionSource() }): Boolean {
    val pressed by interaction.collectIsPressedAsState()
    return pressed
}

/**
 * Busy indicator.
 *
 * A determinate bar was rejected: the operations here are network calls with no
 * meaningful progress, and an indeterminate animation on e-ink is a full-screen
 * repaint per frame. A labelled block that appears and disappears is honest and
 * cheap.
 */
@Composable
fun EinkBusyBar(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .height(14.dp)
                .weight(0.06f)
                .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Section heading, used to break the long settings form into scannable groups. */
@Composable
fun EinkSectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/**
 * A bordered message block.
 *
 * [tone] selects the border weight rather than a colour, because the only way to
 * signal "error" on a greyscale panel is weight and text.
 */
@Composable
fun EinkNotice(
    text: String,
    tone: NoticeTone = NoticeTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val border = when (tone) {
        NoticeTone.NEUTRAL -> 1.dp
        NoticeTone.ERROR -> 3.dp
    }
    val fill = when (tone) {
        NoticeTone.NEUTRAL -> colors.surfaceVariant
        NoticeTone.ERROR -> colors.errorContainer
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Start,
        color = colors.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(3.dp))
            .border(border, colors.onSurface, RoundedCornerShape(3.dp))
            .padding(12.dp),
    )
}

enum class NoticeTone { NEUTRAL, ERROR }

/** Full-width empty state with an optional call to action. */
@Composable
fun EinkEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
