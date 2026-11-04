package ar.nahuel.bitacora.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.samsung.SamsungStatus
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

enum class NoticeTone { OK, WARNING, NEUTRAL }

data class SamsungNoticeText(val text: String, val action: String?, val tone: NoticeTone)

object SamsungNotices {

    fun of(status: SamsungStatus?, note: String?): SamsungNoticeText = when (status) {
        null -> SamsungNoticeText("Revisando la conexión con Samsung Health…", null, NoticeTone.NEUTRAL)
        SamsungStatus.Ready -> if (note != null) {
            SamsungNoticeText(note, "Reintentar", NoticeTone.WARNING)
        } else {
            SamsungNoticeText(
                "Conectado. Los pasos, el tiempo activo y las caminatas con su recorrido salen directo de Samsung Health, con lo que mide el reloj.",
                null,
                NoticeTone.OK,
            )
        }
        SamsungStatus.NeedsPermission -> SamsungNoticeText(
            "Conectá Samsung Health para leer los pasos y caminatas del reloj. Mientras tanto uso Health Connect.",
            "Conectar",
            NoticeTone.WARNING,
        )
        is SamsungStatus.NeedsResolution -> SamsungNoticeText(status.message, "Conectar", NoticeTone.WARNING)
        is SamsungStatus.Unavailable -> SamsungNoticeText("${status.message} Mientras tanto uso Health Connect.", "Reintentar", NoticeTone.WARNING)
        is SamsungStatus.Paused -> SamsungNoticeText(status.message, "Reintentar", NoticeTone.WARNING)
    }

    fun needsAttention(status: SamsungStatus?, note: String?): Boolean = of(status, note).tone == NoticeTone.WARNING
}

@Composable
fun SamsungNotice(
    status: SamsungStatus?,
    note: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    prominent: Boolean = false,
) {
    val ink = LocalInk.current
    val notice = SamsungNotices.of(status, note)
    val color = when (notice.tone) {
        NoticeTone.OK -> ink.good
        NoticeTone.WARNING -> ink.signal
        NoticeTone.NEUTRAL -> ink.inkSoft
    }
    val textStyle = if (prominent) Type.body else Type.small
    if (prominent) {
        Column(modifier.fillMaxWidth()) {
            Text(notice.text, style = textStyle, color = color, modifier = Modifier.padding(vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (detail != null && notice.tone != NoticeTone.OK) {
                SelectionContainer {
                    Text(detail, style = Type.figureSmall, color = ink.inkSoft, modifier = Modifier.padding(bottom = 14.dp))
                }
            }
            notice.action?.let { LedgerButton("$it Samsung Health", onAction, filled = true) }
        }
    } else {
        Row(modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                notice.text,
                style = textStyle,
                color = color,
                modifier = Modifier.weight(1f).padding(end = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            notice.action?.let { LedgerButton(it, onAction) }
        }
    }
}
