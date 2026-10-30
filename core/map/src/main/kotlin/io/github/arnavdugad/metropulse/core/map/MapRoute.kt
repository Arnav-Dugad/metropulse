package io.github.arnavdugad.metropulse.core.map

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon

/** A planned journey to draw on the map: rides in their line's colour, walks dashed. */
data class MapRoute(val segments: List<Segment>) {
    data class Segment(val points: List<LatLon>, val color: Int, val walk: Boolean)
}
