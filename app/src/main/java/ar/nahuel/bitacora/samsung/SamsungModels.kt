package ar.nahuel.bitacora.samsung

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.Route

sealed interface SamsungStatus {
    data object Ready : SamsungStatus
    data object NeedsPermission : SamsungStatus
    data class NeedsResolution(val message: String) : SamsungStatus
    data class Unavailable(val message: String) : SamsungStatus
    data class Paused(val message: String) : SamsungStatus
}

object SamsungParts {
    const val STEPS = "pasos"
    const val ACTIVE_TIME = "tiempo activo"
    const val DISTANCE = "distancia"
    const val CALORIES = "calorías"
    const val WALKS = "caminatas"
    val ALL = listOf(STEPS, ACTIVE_TIME, DISTANCE, CALORIES, WALKS)
}

data class SamsungActivity(
    val days: List<DayActivity>,
    val sessions: List<ExerciseSession>,
    val routes: List<Route>,
    val failed: List<String> = emptyList(),
    val fault: String? = null,
) {
    val complete: Boolean get() = failed.isEmpty()
    val usable: Boolean get() = failed.size < SamsungParts.ALL.size

    companion object {
        fun failure(fault: String) = SamsungActivity(emptyList(), emptyList(), emptyList(), SamsungParts.ALL, fault)
    }
}
