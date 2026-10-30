package io.github.arnavdugad.metropulse.core.map

import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile
import io.github.arnavdugad.metropulse.core.map.geometry.MapGeometry
import org.maplibre.geojson.Feature

/**
 * A metro train as 3D parts: dark bogies, a band in the line's colour, a pale body and a narrower
 * roof, with tapered noses. Each car sits on the track height at its own middle, so a train climbs
 * a ramp car by car. In a tunnel a car is drawn as a low "ghost" so it reads as below ground.
 */
internal object TrainModel {
    const val CARS = 4
    private const val CAR_LENGTH = 21.0
    private const val GAP = 1.2
    private const val WIDTH = 3.2 // a little wider than real (2.9 m) so trains read at city zooms
    private const val GHOST_HEIGHT = 0.6

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
                above += NetworkMapData.polygon(car.ring).apply {
                    addStringProperty("part", layer.part)
                    addStringProperty("color", if (layer.part == "body") body else colour)
                    addNumberProperty("base", ground + layer.bottom)
                    addNumberProperty("top", ground + layer.top)
                }
            }
        }
    }

    /** Mixes [rgb] towards white by [amount] (0..1). */
    fun tint(rgb: Int, amount: Double): Int {
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return (mix(rgb shr 16 and 0xFF) shl 16) or (mix(rgb shr 8 and 0xFF) shl 8) or mix(rgb and 0xFF)
    }
}
