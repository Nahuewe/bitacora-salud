package ar.nahuel.bitacora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.BitacoraTheme
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type

private val sections = listOf(
    "Qué lee" to "Ejercicio, pasos, distancia, calorías, frecuencia cardíaca, sueño y peso desde Health Connect. Solo lectura: la app no escribe ni borra datos de salud.",
    "Para qué" to "Para mostrarte tu actividad y generar recomendaciones en el propio teléfono. Las reglas corren localmente; ningún dato se envía a terceros ni a servicios de IA.",
    "Dónde se guarda" to "Si iniciás sesión, una copia se sincroniza con el proyecto de Supabase configurado por vos, protegida con Row Level Security: solo tu usuario puede leer sus filas.",
    "Credenciales" to "La sesión se guarda cifrada con una clave del Android Keystore. La app no guarda tu contraseña ni hace copias de seguridad automáticas.",
    "Cómo revocar" to "Quitá los permisos desde Health Connect en cualquier momento. Para borrar los datos sincronizados, eliminá tu usuario en Supabase: las tablas borran sus filas en cascada.",
)

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BitacoraTheme {
                val ink = LocalInk.current
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(ink.paper)
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text("PRIVACIDAD", style = Type.masthead, color = ink.ink)
                    Spacer(Modifier.height(6.dp))
                    Rule(thickness = 3.dp, strong = true)
                    sections.forEach { (title, body) ->
                        SectionHeader(title)
                        Text(body, style = Type.body, color = ink.ink, modifier = Modifier.padding(top = 12.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    LedgerButton("Volver", ::finish)
                }
            }
        }
    }
}
