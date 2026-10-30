package io.github.arnavdugad.metropulse.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionVerticalGradient
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOpacity
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Colours of the metro layers for one theme. */
internal class MapPalette(val dark: Boolean) {
    /**
     * A line's colour as drawn. Black lines (London's Northern line) would vanish on the dark map,
     * so there they are drawn in a mid grey, as TfL's own dark maps do.
     */
    fun lineColour(): Expression = if (dark) match(get("color"), get("color"), stop("#000000", "#8E919C")) else get("color")

    val casing = if (dark) "#0D0C13" else "#FFFFFF"
    val deck = if (dark) "#4A4560" else "#D9D5E3"
    val parapet = if (dark) "#5E5878" else "#EEEBF5"
    val pier = if (dark) "#3A3550" else "#C2BDD1"
    val cap = if (dark) "#443F5A" else "#CCC7DA"
    val embankment = if (dark) "#3F3A52" else "#CFCADB"
    val wall = if (dark) "#4F4966" else "#D3CFDE"
    val portal = if (dark) "#2C283B" else "#A9A3BD"
    val trenchFloor = if (dark) "#08070C" else "#C9C5D6"
    val roof = if (dark) "#B9B4CC" else "#E4E1EC"
    val bogie = if (dark) "#14121C" else "#3A3648"
    val label = if (dark) "#EDEAF7" else "#1E1B29"
    val halo = if (dark) "#0D0C13" else "#FFFFFF"
    val stationFill = if (dark) Color.parseColor("#F4F3F8") else Color.WHITE
    val stationRing = if (dark) Color.parseColor("#0D0C13") else Color.parseColor("#1E1B29")
    val myTrain = if (dark) "#A98BFF" else "#6C3BFF"
    val myTrainBody = if (dark) "#E4DAFF" else "#EEE8FF"
}

/** Ids of everything the app adds to the base style. */
internal object MetroLayers {
    const val SRC_LINES = "mp-lines"
    const val SRC_STRUCTURES = "mp-structures"
    const val SRC_TRENCHES = "mp-trenches"
    const val SRC_STATIONS = "mp-stations"
    const val SRC_TRAINS = "mp-trains"
    const val SRC_TRAINS_TUNNEL = "mp-trains-tunnel"
    const val SRC_TRAIN_DOTS = "mp-train-dots"
    const val SRC_MY_TRAIN = "mp-my-train"
    const val SRC_MY_TRAIN_DOT = "mp-my-train-dot"
    const val LAYER_STATIONS = "mp-stations"

    private const val ICON_STATION = "mp-station"
    private const val ICON_INTERCHANGE = "mp-interchange"
    private const val ICON_TERMINUS = "mp-terminus"
    private const val ICON_ACCESS = "mp-access"
    const val SRC_ROUTE = "mp-route"
    private val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())

    /** Adds the sources, icons and layers once per style load. */
    fun install(style: Style, palette: MapPalette, density: Float) {
        for (id in listOf(SRC_LINES, SRC_STRUCTURES, SRC_TRENCHES, SRC_STATIONS, SRC_TRAINS, SRC_TRAINS_TUNNEL, SRC_TRAIN_DOTS, SRC_MY_TRAIN, SRC_MY_TRAIN_DOT, SRC_ROUTE)) {
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, EMPTY))
        }
        style.addImage(ICON_STATION, stationIcon(density, 9f, 2.4f, palette, interchange = false))
        style.addImage(ICON_INTERCHANGE, stationIcon(density, 11f, 2.6f, palette, interchange = true))
        style.addImage(ICON_TERMINUS, terminusIcon(density, palette))
        style.addImage(ICON_ACCESS, accessIcon(density))

        // Lines and 3D structures go under the base map's labels; stations go on top of everything.
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        fun below(layer: Layer) = if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)

        val kind = get("kind")
        below(FillLayer("mp-trenches", SRC_TRENCHES).withProperties(fillColor(palette.trenchFloor), fillOpacity(0.85f)).apply { minZoom = 13f })
        below(
            LineLayer("mp-line-casing", SRC_LINES).withFilter(neq(kind, literal("underground"))).withProperties(
                lineColor(palette.casing), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 4.5f), stop(12, 7.5f), stop(15, 12f), stop(17, 20f))),
            ),
        )
        below(
            LineLayer("mp-line-underground", SRC_LINES).withFilter(eq(kind, literal("underground"))).withProperties(
                lineColor(palette.lineColour()), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 2.5f), stop(12, 4.5f), stop(15, 7f), stop(17, 11f))),
                lineDasharray(arrayOf(1.4f, 1.1f)), lineOpacity(0.75f),
            ),
        )
        below(
            LineLayer("mp-line", SRC_LINES).withFilter(neq(kind, literal("underground"))).withProperties(
                lineColor(palette.lineColour()), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 2.5f), stop(12, 4.5f), stop(15, 7f), stop(17, 11f))),
                // Under a viaduct the ground line fades to a coloured shadow once the 3D deck shows.
                lineOpacity(interpolate(linear(), zoom(), stop(13, 1f), stop(15, switchCase(eq(kind, literal("elevated")), literal(0.3f), literal(1f))))),
            ),
        )
        // Every static 3D part in one layer, coloured by part; it rises from the ground as the map
        // zooms in, like the buildings.
        below(
            FillExtrusionLayer("mp-structures", SRC_STRUCTURES).withProperties(
                fillExtrusionColor(
                    match(
                        get("part"), palette.lineColour(),
                        stop("deck", palette.deck), stop("parapet", palette.parapet), stop("pier", palette.pier),
                        stop("cap", palette.cap), stop("embankment", palette.embankment), stop("wall", palette.wall),
                        stop("portal", palette.portal),
                    ),
                ),
                fillExtrusionBase(interpolate(linear(), zoom(), stop(13, 0f), stop(14, get("base")))),
                fillExtrusionHeight(interpolate(linear(), zoom(), stop(13, 0f), stop(14, get("top")))),
                fillExtrusionOpacity(1f), fillExtrusionVerticalGradient(true),
            ).apply { minZoom = 13f },
        )
        // Trains in tunnels: low and translucent, so they read as below ground.
        below(
            FillExtrusionLayer("mp-trains-tunnel", SRC_TRAINS_TUNNEL).withProperties(
                fillExtrusionColor(palette.lineColour()), fillExtrusionBase(get("base")), fillExtrusionHeight(get("top")),
                fillExtrusionOpacity(0.32f),
            ).apply { minZoom = 13f },
        )
        below(trainLayer("mp-trains", SRC_TRAINS, palette))
        below(
            CircleLayer("mp-train-dots", SRC_TRAIN_DOTS).withProperties(
                circleColor(palette.lineColour()), circleRadius(interpolate(linear(), zoom(), stop(9, 3f), stop(13, 5.5f), stop(14.5, 6.5f))),
                circleStrokeColor(palette.casing), circleStrokeWidth(1.5f),
            ).apply { maxZoom = 14.5f },
        )
        // The rider's own train: violet cars, and a haloed dot that stays visible at every zoom.
        below(trainLayer("mp-my-train", SRC_MY_TRAIN, palette))
        // A planned journey ("Show on map"), drawn over the network: casing, rides, dashed walks.
        style.addLayer(
            LineLayer("mp-route-casing", SRC_ROUTE).withProperties(
                lineColor(palette.casing), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 7f), stop(13, 11f), stop(16, 18f))),
            ),
        )
        style.addLayer(
            LineLayer("mp-route", SRC_ROUTE).withFilter(eq(get("walk"), literal(false))).withProperties(
                lineColor(palette.lineColour()), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 4.5f), stop(13, 7f), stop(16, 12f))),
            ),
        )
        style.addLayer(
            LineLayer("mp-route-walk", SRC_ROUTE).withFilter(eq(get("walk"), literal(true))).withProperties(
                lineColor(palette.label), lineCap(Property.LINE_CAP_ROUND),
                lineWidth(interpolate(linear(), zoom(), stop(9, 2.5f), stop(16, 5f))), lineDasharray(arrayOf(0.1f, 1.8f)),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_STATIONS, SRC_STATIONS).withProperties(
                iconImage(match(get("kind"), literal(ICON_STATION), stop("interchange", ICON_INTERCHANGE), stop("terminus", ICON_TERMINUS))),
                iconSize(interpolate(linear(), zoom(), stop(9, 0.38f), stop(11, 0.55f), stop(13, 0.85f), stop(16, 1.25f))),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                symbolSortKey(switchCase(get("interchange"), literal(0f), literal(1f))),
                textField(get("name")), textFont(arrayOf("Noto Sans Bold")),
                textSize(interpolate(linear(), zoom(), stop(10, 10.5f), stop(15, 14f))),
                textColor(palette.label), textHaloColor(palette.halo), textHaloWidth(1.6f), textMaxWidth(8f),
                textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.9f)), textOptional(true),
                // Interchanges are named from the city view; other stations fade in as the map zooms.
                textOpacity(interpolate(linear(), zoom(), stop(10.5, switchCase(get("interchange"), literal(1f), literal(0f))), stop(12.5, 1f))),
            ),
        )
        // Step-free stations get a small badge once the map is close enough to read it.
        style.addLayer(
            SymbolLayer("mp-station-access", SRC_STATIONS).withFilter(eq(get("access"), literal(true))).withProperties(
                iconImage(ICON_ACCESS), iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(interpolate(linear(), zoom(), stop(13, 0.7f), stop(16, 1f))), iconOffset(arrayOf(11f, -11f)),
            ).apply { minZoom = 13.5f },
        )
        style.addLayer(
            CircleLayer("mp-my-train-dot", SRC_MY_TRAIN_DOT).withProperties(
                circleColor(palette.myTrain), circleRadius(interpolate(linear(), zoom(), stop(9, 6f), stop(14, 8f), stop(17, 6f))),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
            ),
        )
    }

    fun setRoute(style: Style, route: FeatureCollection) {
        style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(route)
    }

    fun setNetwork(style: Style, data: NetworkMapData) {
        style.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(data.lines)
        style.getSourceAs<GeoJsonSource>(SRC_STRUCTURES)?.setGeoJson(data.structures)
        style.getSourceAs<GeoJsonSource>(SRC_TRENCHES)?.setGeoJson(data.trenches)
        style.getSourceAs<GeoJsonSource>(SRC_STATIONS)?.setGeoJson(data.stations)
    }

    /** Draws (or clears) the rider's train, in violet. */
    fun setMyTrain(style: Style, data: NetworkMapData, follow: MapFollow?, palette: MapPalette) {
        val cars = style.getSourceAs<GeoJsonSource>(SRC_MY_TRAIN) ?: return
        val dot = style.getSourceAs<GeoJsonSource>(SRC_MY_TRAIN_DOT) ?: return
        val shape = follow?.let { data.network.patternById[it.patternId]?.shape }
        if (follow == null || shape == null) {
            cars.setGeoJson(EMPTY)
            dot.setGeoJson(EMPTY)
            return
        }
        // The rider's train stays visible in tunnels: there it is drawn at ground level.
        val above = ArrayList<Feature>()
        val elevation = data.elevation[follow.patternId]?.takeUnless { it.inTunnel(follow.alongM) }
        TrainModel.build(shape, elevation, follow.alongM, palette.myTrain, palette.myTrainBody, above, ArrayList())
        cars.setGeoJson(FeatureCollection.fromFeatures(above))
        val head = shape.pointAt(follow.alongM)
        dot.setGeoJson(Point.fromLngLat(head.lon, head.lat))
    }

    /** A 3D train layer: parts coloured by the feature, roof and bogies by the theme. */
    private fun trainLayer(id: String, source: String, palette: MapPalette) =
        FillExtrusionLayer(id, source).withProperties(
            fillExtrusionColor(match(get("part"), palette.lineColour(), stop("roof", palette.roof), stop("bogie", palette.bogie))),
            fillExtrusionBase(get("base")), fillExtrusionHeight(get("top")),
            fillExtrusionOpacity(1f), fillExtrusionVerticalGradient(true),
        ).apply { minZoom = 13f }

    /** A terminus: a rounded "cap", wider than a station disc, so line ends read at a glance. */
    private fun terminusIcon(density: Float, palette: MapPalette): Bitmap {
        val w = 26f * density
        val h = 16f * density
        val shadow = 2f * density
        val bitmap = createBitmap((w + 2 * shadow).toInt() + 2, (h + 2 * shadow).toInt() + 2)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = shadow + 1
        val top = shadow + 1
        val radius = h / 2
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(left, top + shadow * 0.6f, left + w, top + h + shadow * 0.6f, radius, radius, paint)
        paint.color = palette.stationRing
        canvas.drawRoundRect(left, top, left + w, top + h, radius, radius, paint)
        val ring = 2.6f * density
        paint.color = palette.stationFill
        canvas.drawRoundRect(left + ring, top + ring, left + w - ring, top + h - ring, radius - ring, radius - ring, paint)
        return bitmap
    }

    /** A step-free badge: a blue disc with a wheelchair glyph. */
    private fun accessIcon(density: Float): Bitmap {
        val size = (18 * density).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, c, paint)
        paint.color = Color.parseColor("#1F6FEB")
        canvas.drawCircle(c, c, c - 1.5f * density, paint)
        paint.color = Color.WHITE
        val u = size / 18f
        canvas.drawCircle(9.4f * u, 4.2f * u, 1.5f * u, paint) // head
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * u
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(9.2f * u, 6.4f * u, 8.6f * u, 10.2f * u, paint) // back
        canvas.drawLine(8.6f * u, 10.2f * u, 12.2f * u, 10.2f * u, paint) // seat
        canvas.drawLine(12.2f * u, 10.2f * u, 13.4f * u, 13.6f * u, paint) // leg
        canvas.drawCircle(8.4f * u, 12.4f * u, 3.2f * u, paint) // wheel
        return bitmap
    }

    /** A station pin: a white disc in a dark ring; interchanges get a second inner ring. */
    private fun stationIcon(density: Float, radiusDp: Float, ringDp: Float, palette: MapPalette, interchange: Boolean): Bitmap {
        val r = radiusDp * density
        val ring = ringDp * density
        val shadow = 2f * density
        val size = (2 * (r + shadow)).toInt() + 2
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(c, c + shadow * 0.6f, r, paint)
        paint.color = palette.stationRing
        canvas.drawCircle(c, c, r, paint)
        paint.color = palette.stationFill
        canvas.drawCircle(c, c, r - ring, paint)
        if (interchange) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.color = palette.stationRing
            canvas.drawCircle(c, c, (r - ring) * 0.48f, paint)
        }
        return bitmap
    }
}
