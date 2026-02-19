package com.retichat.app.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.retichat.app.ui.theme.*

/**
 * A frosted-glass surface inspired by Apple's Liquid Glass design language.
 * On API 31+ uses RenderEffect blur; on older devices falls back to a
 * semi-transparent gradient.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    blurRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f))
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.graphicsLayer {
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                blurRadius.toPx(), blurRadius.toPx(),
                                Shader.TileMode.CLAMP
                            )
                            .asComposeRenderEffect()
                    }
                } else Modifier
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(GlassWhite, GlassWhiteLight)
                )
            )
            .border(1.dp, GlassBorder, shape),
        content = content,
    )
}

/**
 * Pill-shaped glass chip (for navigation tabs, badges, etc).
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f))
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    listOf(GlassWhite, GlassWhiteLight)
                )
            )
            .border(1.dp, GlassBorder, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Circular avatar placeholder with initials.
 */
@Composable
fun AvatarCircle(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val initials = name.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * A chat bubble shape.
 */
@Composable
fun ChatBubble(
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val bgColor = if (isOutgoing) BubbleOutgoing else BubbleIncoming
    val textColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 18.dp,
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}
