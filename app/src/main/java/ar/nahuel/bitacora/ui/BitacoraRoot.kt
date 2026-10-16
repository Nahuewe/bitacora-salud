package ar.nahuel.bitacora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.screens.AccessScreen
import ar.nahuel.bitacora.ui.screens.LogScreen
import ar.nahuel.bitacora.ui.screens.SettingsScreen
import ar.nahuel.bitacora.ui.screens.SheetScreen
import ar.nahuel.bitacora.ui.screens.TrendsScreen
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

class RootActions(
    val selectTab: (Tab) -> Unit,
    val retry: () -> Unit,
    val clearCrash: () -> Unit,
    val requestPermissions: () -> Unit,
    val installHealthConnect: () -> Unit,
    val openHealthConnect: () -> Unit,
    val openPrivacy: () -> Unit,
    val signIn: (String, String) -> Unit,
    val signOut: () -> Unit,
    val sync: () -> Unit,
)

@Composable
fun BitacoraRoot(state: UiState, actions: RootActions) {
    val ink = LocalInk.current
    Box(
        Modifier
            .fillMaxSize()
            .background(ink.paper)
            .safeDrawingPadding(),
    ) {
        if (state.access != AccessState.Ready) {
            AccessScreen(
                access = state.access,
                onRequestPermissions = actions.requestPermissions,
                onInstallHealthConnect = actions.installHealthConnect,
                onOpenPrivacy = actions.openPrivacy,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MainLayout(state, actions)
        }
    }
}

@Composable
private fun MainLayout(state: UiState, actions: RootActions) {
    Column(Modifier.fillMaxSize()) {
        val body = Modifier
            .weight(1f)
            .fillMaxWidth()
        when (state.tab) {
            Tab.SHEET -> SheetScreen(state.content, state.today, actions.retry, body)
            Tab.TRENDS -> TrendsScreen(state.content, body)
            Tab.LOG -> LogScreen(state.content, body)
            Tab.SETTINGS -> SettingsScreen(
                account = state.account,
                sync = state.sync,
                backgroundGranted = state.backgroundGranted,
                historyGranted = state.historyGranted,
                crash = state.crash,
                onClearCrash = actions.clearCrash,
                onSignIn = actions.signIn,
                onSignOut = actions.signOut,
                onSync = actions.sync,
                onOpenHealthConnect = actions.openHealthConnect,
                onOpenPrivacy = actions.openPrivacy,
                modifier = body,
            )
        }
        TabStrip(state.tab, actions.selectTab)
    }
}

@Composable
private fun TabStrip(selected: Tab, onSelect: (Tab) -> Unit) {
    val ink = LocalInk.current
    val labels = listOf(Tab.SHEET to "Hoja", Tab.TRENDS to "Tendencias", Tab.LOG to "Registro", Tab.SETTINGS to "Ajustes")
    Column(Modifier.background(ink.paper)) {
        Rule(thickness = 2.dp, strong = true)
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .selectableGroup(),
        ) {
            labels.forEach { (tab, label) ->
                val isSelected = tab == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(56.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label.uppercase(),
                        style = Type.sectionLabel.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
                        color = if (isSelected) ink.ink else ink.inkSoft,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (isSelected) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(0.5f)
                                .height(4.dp)
                                .background(ink.signal),
                        )
                    }
                }
            }
        }
    }
}
