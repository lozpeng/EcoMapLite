package org.kori.plugin.geo.map

import org.kori.plugin.geo.track.TrackMediaRecord
import org.kori.plugin.geo.track.TrackPoint
import org.kori.plugin.geo.track.TrackSession

data class TrackMapUiState(
    val recording: Boolean = false,
    val points: Int = 0,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val segments: Int = 0,

    val liveTrackPoints: List<TrackPoint> = emptyList(),
    val liveSmoothPoints: List<TrackPoint> = emptyList(),
    val liveMedia: List<TrackMediaRecord> = emptyList(),

    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val myAccuracyM: Float? = null,
    val following: Boolean = true,

    val selectedBaseMap: String = "satellite",
    val contourEnabled: Boolean = false,

    val sessions: List<TrackSession> = emptyList(),
    val loadingSessions: Boolean = false,

    val errorMessage: String? = null,
) {
    data class LatLng(val lat: Double, val lng: Double)
}