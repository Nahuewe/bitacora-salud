package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.ui.AccessState
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

private val readList = listOf(
    "Ejercicio" to "tipo, duración, distancia",
    "Pasos" to "total por día",
    "Frecuencia cardíaca" to "promedio por sesión y por noche",
    "Sueño" to "duración y etapas",
    "Peso" to "cada registro",
    "Calorías" to "activas y totales",
)

@Composable
fun AccessScreen(
    access: AccessState,
    onRequestPermissions: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text("BITÁCORA", style = Type.masthead, color = ink.ink)
        Spacer(Modifier.height(6.dp))
        Rule(thickness = 3.dp, strong = true)
        Spacer(Modifier.height(28.dp))

        when (access) {
            is AccessState.HealthConnectMissing -> {
                Text(
                    if (access.needsUpdate) "Actualizá Health Connect" else "Falta Health Connect",
                    style = Type.headline,
                    color = ink.ink,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "La bitácora lee lo que Samsung Health publica en Health Connect. Instalalo o actualizalo desde Play Store y volvé.",
                    style = Type.body,
                    color = ink.inkSoft,
                )
                Spacer(Modifier.height(24.dp))
                LedgerButton(if (access.needsUpdate) "Actualizar" else "Instalar", onInstallHealthConnect, filled = true)
            }
            AccessState.Checking -> Text("Revisando Health Connect…", style = Type.body, color = ink.inkSoft)
            else -> {
                Text("Antes de empezar, la app necesita leer tus registros.", style = Type.headline, color = ink.ink)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Solo lectura: no escribe ni borra nada en Health Connect. Los datos se muestran acá y, si iniciás sesión, se copian a tu Supabase.",
                    style = Type.body,
                    color = ink.inkSoft,
                )
                SectionHeader("Qué se lee")
                readList.forEach { (name, detail) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(detail, style = Type.small, color = ink.inkSoft)
                    }
                    Rule()
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Health Connect también te va a ofrecer el historial completo y la lectura en segundo plano: activalos para que la sincronización automática funcione.",
                    style = Type.small,
                    color = ink.inkSoft,
                )
                Spacer(Modifier.height(24.dp))
                LedgerButton("Dar acceso", onRequestPermissions, filled = true)
                Spacer(Modifier.height(12.dp))
                LedgerButton("Política de privacidad", onOpenPrivacy)
            }
        }
    }
}
