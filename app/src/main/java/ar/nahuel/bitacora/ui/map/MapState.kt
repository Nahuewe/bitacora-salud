package ar.nahuel.bitacora.ui.map

import ar.nahuel.bitacora.routes.MapData

sealed interface MapState {
    data object Idle : MapState
    data class Loading(val message: String) : MapState
    data class Ready(val data: MapData, val message: String? = null) : MapState
    data class Error(val message: String) : MapState
}
