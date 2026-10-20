package ar.nahuel.bitacora

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import ar.nahuel.bitacora.health.HealthAccess
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ar.nahuel.bitacora.ui.BitacoraRoot
import ar.nahuel.bitacora.ui.MainViewModel
import ar.nahuel.bitacora.ui.RootActions
import ar.nahuel.bitacora.ui.theme.BitacoraTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted -> viewModel.onPermissionsResult(granted) }

    private val routePermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.loadMap() }

    private val exportPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importRoutes(uri)
    }

    private var pendingRouteId: String? = null

    private val routeConsentLauncher = registerForActivityResult(ExerciseRouteRequestContract()) { route ->
        pendingRouteId?.let { viewModel.onRouteConsent(it, route) }
        pendingRouteId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = RootActions(
            selectTab = viewModel::selectTab,
            retry = { viewModel.refresh(force = true) },
            clearCrash = viewModel::clearCrash,
            toggleRoutine = viewModel::toggleRoutine,
            openMap = viewModel::openMap,
            closeMap = viewModel::closeMap,
            importRoutes = { exportPicker.launch(null) },
            requestRoutes = { routePermissionLauncher.launch(HealthAccess.core + HealthAccess.ROUTES) },
            clearRoutes = viewModel::clearImportedRoutes,
            retryMap = { viewModel.loadMap() },
            consentRoute = { id ->
                pendingRouteId = id
                routeConsentLauncher.launch(id)
            },
            requestPermissions = { permissionLauncher.launch(viewModel.permissionsToRequest) },
            installHealthConnect = ::openHealthConnectListing,
            openHealthConnect = ::openHealthConnectSettings,
            openPrivacy = { startActivity(Intent(this, PrivacyActivity::class.java)) },
            signIn = viewModel::signIn,
            signOut = viewModel::signOut,
            sync = viewModel::syncNow,
        )
        setContent {
            BitacoraTheme {
                val state = viewModel.state.collectAsStateWithLifecycle()
                BitacoraRoot(state.value, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openHealthConnectSettings() {
        try {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            openHealthConnectListing()
        }
    }

    private fun openHealthConnectListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE"))
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE")),
            )
        }
    }

    private companion object {
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
