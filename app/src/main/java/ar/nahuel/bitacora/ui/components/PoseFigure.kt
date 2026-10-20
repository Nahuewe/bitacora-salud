package ar.nahuel.bitacora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.Frame
import ar.nahuel.bitacora.domain.P
import ar.nahuel.bitacora.domain.Prop
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

@Composable
fun PoseFigure(frame: Frame, description: String, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val body = if (frame.wrong) ink.signal else ink.ink
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            fun o(p: P) = Offset(p.x * size.width, p.y * size.height)
            val thick = size.width * 0.035f
            val far = size.width * 0.028f

            drawLine(ink.rule, Offset(0f, size.height * 0.92f + thick / 2), Offset(size.width, size.height * 0.92f + thick / 2), 2.dp.toPx())

            frame.props.forEach { prop ->
                when (prop) {
                    is Prop.Block -> drawRect(
                        color = ink.barMuted,
                        topLeft = Offset(prop.left * size.width, prop.top * size.height),
                        size = Size((prop.right - prop.left) * size.width, (prop.bottom - prop.top) * size.height),
                    )
                    is Prop.Band -> drawLine(ink.inkSoft, o(prop.from), o(prop.to), 1.5.dp.toPx())
                }
            }

            val pose = frame.pose
            fun limb(a: P, b: P, color: Color, width: Float) = drawLine(color, o(a), o(b), width, cap = StrokeCap.Round)

            pose.elbow2?.let { e -> limb(pose.shoulder, e, ink.barMuted, far); pose.hand2?.let { limb(e, it, ink.barMuted, far) } }
            pose.knee2?.let { k -> limb(pose.hip, k, ink.barMuted, far); pose.ankle2?.let { limb(k, it, ink.barMuted, far) } }
            if (pose.feet) pose.ankle2?.let { limb(it, P(it.x + 0.06f, it.y), ink.barMuted, far) }

            limb(pose.hip, pose.knee, body, thick)
            limb(pose.knee, pose.ankle, body, thick)
            when {
                pose.footTo != null -> limb(pose.ankle, pose.footTo, body, thick)
                pose.feet -> limb(pose.ankle, P(pose.ankle.x + 0.07f, pose.ankle.y), body, thick)
            }
            limb(pose.shoulder, pose.hip, body, thick * 1.25f)
            limb(pose.shoulder, pose.elbow, body, thick)
            limb(pose.elbow, pose.hand, body, thick)
            limb(pose.shoulder, P((pose.shoulder.x + pose.head.x) / 2, (pose.shoulder.y + pose.head.y) / 2), body, thick)
            drawCircle(body, radius = size.width * 0.05f, center = o(pose.head))

            if (frame.dumbbell) dumbbell(o(pose.hand), size.width * 0.07f, ink.ink)

            frame.guides.forEach { guide ->
                drawLine(
                    color = if (frame.wrong) ink.inkSoft else ink.signal,
                    start = o(guide.from),
                    end = o(guide.to),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
            }
        }
        Text(
            frame.label.uppercase(),
            style = Type.sectionLabel,
            color = if (frame.wrong) ink.signal else ink.inkSoft,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun DrawScope.dumbbell(center: Offset, length: Float, color: Color) {
    val plate = length * 0.32f
    drawLine(color, Offset(center.x - length / 2, center.y), Offset(center.x + length / 2, center.y), length * 0.12f)
    listOf(-1f, 1f).forEach { side ->
        drawRoundRect(
            color = color,
            topLeft = Offset(center.x + side * length / 2 - plate * 0.3f, center.y - plate / 2),
            size = Size(plate * 0.6f, plate),
            cornerRadius = CornerRadius(plate * 0.1f),
        )
    }
}
