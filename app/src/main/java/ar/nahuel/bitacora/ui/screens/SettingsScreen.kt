package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import ar.nahuel.bitacora.CrashReport
import ar.nahuel.bitacora.samsung.SamsungStatus
import ar.nahuel.bitacora.ui.AccountState
import ar.nahuel.bitacora.ui.SyncUi
import ar.nahuel.bitacora.ui.components.KeyValueRow
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.LedgerField
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("dd/MM HH:mm")

@Composable
fun SettingsScreen(
    account: AccountState,
    sync: SyncUi,
    backgroundGranted: Boolean,
    historyGranted: Boolean,
    crash: CrashReport?,
    onClearCrash: () -> Unit,
    samsung: SamsungStatus?,
    onConnectSamsung: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onSync: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(top = 12.dp)) {
            Text("AJUSTES", style = Type.masthead, color = ink.ink)
            Spacer(Modifier.height(6.dp))
            Rule(thickness = 3.dp, strong = true)
        }

        SectionHeader("Cuenta de Supabase")
        when (account) {
            AccountState.NotConfigured -> Text(
                "Esta copia de la app se compiló sin SUPABASE_URL ni SUPABASE_ANON_KEY. Los datos se ven acá, pero no se sincronizan.",
                style = Type.body,
                color = ink.inkSoft,
                modifier = Modifier.padding(vertical = 14.dp),
            )
            is AccountState.SignedIn -> {
                KeyValueRow("Sesión iniciada", account.email)
                Spacer(Modifier.height(14.dp))
                LedgerButton("Cerrar sesión", onSignOut)
            }
            else -> SignInForm(account, onSignIn)
        }

        SectionHeader("Samsung Health · reloj")
        Text(
            when (samsung) {
                SamsungStatus.Ready -> "Conectado. Los pasos, el tiempo activo y las caminatas con su recorrido salen directo de Samsung Health, con lo que mide el reloj."
                SamsungStatus.NeedsPermission -> "Falta autorizar la lectura. Sin esto, la app usa Health Connect, que puede no tener todos los pasos del reloj."
                is SamsungStatus.NeedsResolution -> samsung.message
                is SamsungStatus.Unavailable -> samsung.message + " Mientras tanto uso Health Connect."
                is SamsungStatus.Paused -> samsung.message + " Mientras tanto uso Health Connect."
                null -> "Revisando…"
            },
            style = Type.body,
            color = if (samsung == SamsungStatus.Ready) ink.good else ink.inkSoft,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        if (samsung != SamsungStatus.Ready) {
            LedgerButton(if (samsung is SamsungStatus.Paused) "Reintentar Samsung Health" else "Conectar Samsung Health", onConnectSamsung, filled = true)
        }

        SectionHeader("Sincronización")
        KeyValueRow(
            "Última exitosa",
            sync.lastSuccess?.atZone(ZoneId.systemDefault())?.format(stamp) ?: "nunca",
            valueColorSoft = sync.lastSuccess == null,
        )
        KeyValueRow("Automática cada 12 h", if (backgroundGranted) "activa" else "sin permiso", valueColorSoft = !backgroundGranted)
        KeyValueRow("Historial completo", if (historyGranted) "365 días" else "30 días", valueColorSoft = !historyGranted)
        if (sync.message != null) {
            Text(
                sync.message,
                style = Type.body,
                color = if (sync.messageIsError) ink.signal else ink.good,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(14.dp))
        LedgerButton(
            if (sync.running) "Sincronizando…" else "Sincronizar ahora",
            onSync,
            enabled = !sync.running && account is AccountState.SignedIn,
            filled = true,
        )

        SectionHeader("Health Connect")
        Text(
            "La app solo lee. Los permisos se administran desde Health Connect; si falta la sincronización automática o el historial, activalos ahí.",
            style = Type.body,
            color = ink.inkSoft,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Row {
            LedgerButton("Abrir Health Connect", onOpenHealthConnect)
            Spacer(Modifier.width(12.dp))
            LedgerButton("Privacidad", onOpenPrivacy)
        }
        if (crash != null) {
            SectionHeader("Último cierre inesperado", crash.at.atZone(ZoneId.systemDefault()).format(stamp))
            Text(
                "Mantené apretado el texto para copiarlo y pasárselo a quien mantiene la app.",
                style = Type.small,
                color = ink.inkSoft,
                modifier = Modifier.padding(top = 10.dp),
            )
            SelectionContainer {
                Text(crash.summary, style = Type.figureSmall, color = ink.signal, modifier = Modifier.padding(vertical = 10.dp))
            }
            LedgerButton("Borrar registro", onClearCrash)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SignInForm(account: AccountState, onSignIn: (String, String) -> Unit) {
    val ink = LocalInk.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = account is AccountState.SigningIn
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LedgerField(
            label = "Email",
            value = email,
            onValueChange = { email = it },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        LedgerField(
            label = "Contraseña",
            value = password,
            onValueChange = { password = it },
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSignIn(email, password) }),
        )
        if (account is AccountState.Failed) {
            Text(
                account.message,
                style = Type.body,
                color = ink.signal,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(Modifier.height(16.dp))
        LedgerButton(if (busy) "Entrando…" else "Iniciar sesión", { onSignIn(email, password) }, enabled = !busy, filled = true)
    }
}
