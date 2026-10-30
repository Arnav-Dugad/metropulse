package io.github.arnavdugad.metropulse.core.map

import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile
import io.github.arnavdugad.metropulse.core.map.geometry.MapGeometry
import org.maplibre.geojson.Feature

/**
 * A metro train as 3D parts: dark bogies, a band in the line's colour, a pale body and a narrower
 * roof, with tapered noses. Each car sits on the track height at its own middle, so a train climbs
 * a ramp car by car. Approaching a tunnel portal the cars sink into the cutting, then carry on
 * below ground as low translucent "ghosts".
 */
internal object TrainModel {
    const val CARS = 4
    private const val CAR_LENGTH = 21.0
    private const val GAP = 1.2
    private const val WIDTH = 3.2 // a little wider than real (2.9 m) so trains read at city zooms
    private const val GHOST_HEIGHT = 0.6
    /** Distance before a portal over which a car sinks into the cutting. */
    private const val SINK_M = 45.0
    private const val SINK_MIN = 0.12

    private class Layer(val part: String, val widthScale: Double, val bottom: Double, val top: Double, val taper: Boolean)

    private val layers = listOf(
        Layer("bogie", 0.72, 0.0, 0.55, taper = false),
        Layer("livery", 1.0, 0.55, 1.5, taper = true),
        Layer("body", 1.0, 1.5, 3.45, taper = true),
        Layer("roof", 0.8, 3.45, 3.8, taper = true),
    )

    /** Adds the train with its nose at [headAlongM] to [above] (and any tunnel cars to [tunnel]). */
    fun build(
        shape: LineString, elevation: ElevationProfile?, headAlongM: Double, colour: String, body: String,
        above: MutableList<Feature>, tunnel: MutableList<Feature>,
    ) {
        val outline = MapGeometry.trainCars(shape, headAlongM, CARS, CAR_LENGTH, GAP, WIDTH)
        val inTunnel = outline.map { elevation?.inTunnel(it.centerAlongM) == true }
        outline.forEachIndexed { i, car ->
            if (inTunnel[i]) {
                tunnel += NetworkMapData.polygon(car.ring).apply {
                    addStringProperty("color", colour)
                    addNumberProperty("base", 0.0)
                    addNumberProperty("top", GHOST_HEIGHT)
                }
            }
        }
        if (inTunnel.all { it }) return
        for (layer in layers) {
            val cars = if (layer.widthScale == 1.0 && layer.taper) outline
            else MapGeometry.trainCars(shape, headAlongM, CARS, CAR_LENGTH, GAP, WIDTH, layer.widthScale, layer.taper)
            for (car in cars) {
                if (inTunnel.getOrElse(car.index) { false }) continue
                val ground = elevation?.top(car.centerAlongM)?.let { if (it > 0) it + Structure.BED_HEIGHT else 0.0 } ?: 0.0
                val sink = elevation?.let { sinkFactor(it, car.centerAlongM) } ?: 1.0
                above += NetworkMapData.polygon(car.ring).apply {
                    addStringProperty("part", layer.part)
                    addStringProperty("color", if (layer.part == "body") body else colour)
                    addNumberProperty("base", ground + layer.bottom * sink)
                    addNumberProperty("top", ground + layer.top * sink)
                }
            }
        }
    }

    /** 1 in the open; falls towards [SINK_MIN] over the last [SINK_M] before a tunnel portal. */
    private fun sinkFactor(elevation: ElevationProfile, alongM: Double): Double {
        var factor = 1.0
        for (portal in elevation.portals) {
            val d = if (portal.tunnelAhead) portal.atM - alongM else alongM - portal.atM
            if (d in 0.0..SINK_M) factor = minOf(factor, SINK_MIN + (1 - SINK_MIN) * d / SINK_M)
        }
        return factor
    }

    /** Mixes [rgb] towards white by [amount] (0..1). */
    fun tint(rgb: Int, amount: Double): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return (mix(rgb shr 16 and 0xFF) shl 16) or (mix(rgb shr 8 and 0xFF) shl 8) or mix(rgb and 0xFF)
    }
}
