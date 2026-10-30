package io.github.arnavdugad.metropulse.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * MetroPulse's own icon set: 24 dp grid, 1.75 dp round strokes, drawn for this app.
 * Glyphs are stroke-only so they tint cleanly through `Icon(tint = ...)`.
 */
object MpIcons {
    val Map by lazy { icon("Map", "M9 4.5L3.75 6.5V19.5L9 17.5L15 19.5L20.25 17.5V4.5L15 6.5L9 4.5Z", "M9 4.5V17.5", "M15 6.5V19.5") }
    val Route by lazy {
        icon(
            "Route",
            "M6 7.75a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 0 0 0 4.5Z",
            "M18 20.75a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 0 0 0 4.5Z",
            "M6 7.75V10a3 3 0 0 0 3 3h6a3 3 0 0 1 3 3v0.25",
        )
    }
    val Search by lazy { icon("Search", "M10.75 17.5a6.75 6.75 0 1 0 0-13.5a6.75 6.75 0 0 0 0 13.5Z", "M15.75 15.75L20 20") }
    val Star by lazy { icon("Star", "M12 3.75l2.47 5.01l5.53 0.8l-4 3.9l0.94 5.5L12 16.36l-4.94 2.6l0.94-5.5l-4-3.9l5.53-0.8L12 3.75Z") }
    val Sliders by lazy {
        icon(
            "Sliders", "M4 7h8.5", "M17.5 7H20", "M4 17h2.5", "M11.5 17H20",
            "M15 9.25a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 0 0 0 4.5Z",
            "M9 19.25a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 0 0 0 4.5Z",
        )
    }
    val Train by lazy {
        icon(
            "Train",
            "M7.25 3.75h9.5a2.5 2.5 0 0 1 2.5 2.5v8.5a3 3 0 0 1-3 3h-8.5a3 3 0 0 1-3-3v-8.5a2.5 2.5 0 0 1 2.5-2.5Z",
            "M4.75 11h14.5", "M9 7.25h6", "M8.5 14.5h0.01", "M15.5 14.5h0.01", "M8 17.75l-2 2.75", "M16 17.75l2 2.75",
        )
    }
    val Pin by lazy {
        icon(
            "Pin",
            "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 1 1 13 0c0 5.4-6.5 11-6.5 11Z",
            "M12 12.25a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 0 0 0 4.5Z",
        )
    }
    val Clock by lazy { icon("Clock", "M12 20.25a8.25 8.25 0 1 0 0-16.5a8.25 8.25 0 0 0 0 16.5Z", "M12 7.5V12l3 2") }
    val Bell by lazy { icon("Bell", "M6 16.5V11a6 6 0 1 1 12 0v5.5l1.5 1.5h-15L6 16.5Z", "M10 20.25a2 2 0 0 0 4 0") }
    val Locate by lazy {
        icon(
            "Locate",
            "M12 18.5a6.5 6.5 0 1 0 0-13a6.5 6.5 0 0 0 0 13Z",
            "M12 2.75v2.75", "M12 18.5v2.75", "M2.75 12h2.75", "M18.5 12h2.75",
            "M12 13.5a1.5 1.5 0 1 0 0-3a1.5 1.5 0 0 0 0 3Z",
        )
    }
    val Download by lazy { icon("Download", "M12 3.75v11", "M7.5 10.75L12 15.25l4.5-4.5", "M4.75 19.75h14.5") }
    val ArrowRight by lazy { icon("ArrowRight", "M4.5 12h15", "M13.5 6l6 6l-6 6") }
    val Close by lazy { icon("Close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11") }
    val Check by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Swap by lazy { icon("Swap", "M7.5 4.5v15", "M4 8l3.5-3.5L11 8", "M16.5 19.5v-15", "M13 16l3.5 3.5L20 16") }
    val Info by lazy { icon("Info", "M12 20.25a8.25 8.25 0 1 0 0-16.5a8.25 8.25 0 0 0 0 16.5Z", "M12 11v5", "M12 7.75h0.01") }
    val Offline by lazy {
        icon(
            "Offline", "M3.5 3.5l17 17",
            "M8.25 8.25A4.75 4.75 0 0 0 7.25 18.5h10.5",
            "M10.75 5.6a6.25 6.25 0 0 1 7.35 5.65a3.75 3.75 0 0 1 2.4 5.5",
        )
    }
    val Sparkle by lazy {
        icon(
            "Sparkle",
            "M11 3.5l1.9 5.1l5.1 1.9l-5.1 1.9L11 17.5l-1.9-5.1L4 10.5l5.1-1.9L11 3.5Z",
            "M18.5 15.5l0.8 2l2 0.8l-2 0.8l-0.8 2l-0.8-2l-2-0.8l2-0.8l0.8-2Z",
        )
    }
    val Shield by lazy {
        icon(
            "Shield",
            "M12 3.5l7 2.75v5.25c0 4.4-3 7.9-7 9c-4-1.1-7-4.6-7-9V6.25L12 3.5Z",
            "M8.75 12l2.25 2.25l4.25-4.5",
        )
    }
    val Refresh by lazy { icon("Refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.25v4.25h-4.25") }
    val ChevronRight by lazy { icon("ChevronRight", "M9.5 5.5l6.5 6.5l-6.5 6.5") }
    val ChevronDown by lazy { icon("ChevronDown", "M5.5 9.5l6.5 6.5l6.5-6.5") }
    val Ticket by lazy {
        icon(
            "Ticket",
            "M4 7.5A1.5 1.5 0 0 1 5.5 6h13A1.5 1.5 0 0 1 20 7.5V10a2 2 0 0 0 0 4v2.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 16.5V14a2 2 0 0 0 0-4V7.5Z",
            "M14.5 6.5v1.5", "M14.5 11.25v1.5", "M14.5 16v1.5",
        )
    }
    val Lines by lazy {
        icon(
            "Lines", "M4 6.5h4l4 5h8", "M4 17.5h4l4-5",
            "M5.5 6.5a1.5 1.5 0 1 0-3 0a1.5 1.5 0 0 0 3 0Z",
            "M5.5 17.5a1.5 1.5 0 1 0-3 0a1.5 1.5 0 0 0 3 0Z",
        )
    }
    val Layers by lazy { icon("Layers", "M12 4l8.5 4.5L12 13L3.5 8.5L12 4Z", "M3.5 12.5L12 17l8.5-4.5", "M3.5 16.5L12 21l8.5-4.5") }
    val Code by lazy { icon("Code", "M8.5 7.5L4 12l4.5 4.5", "M15.5 7.5L20 12l-4.5 4.5", "M13.5 5l-3 14") }
    val Back by lazy { icon("Back", "M19.5 12h-15", "M10.5 6l-6 6l6 6") }
    val Walk by lazy {
        icon(
            "Walk", "M13 5.25a1.75 1.75 0 1 0 0-3.5a1.75 1.75 0 0 0 0 3.5Z",
            "M9.5 21l2.25-6.5L14 17v4", "M8 11.5l1.5-3.5l3.5-1l2.5 3.5l2.5 1", "M11.75 14.5l1.25-6",
        )
    }
    val Exit by lazy { icon("Exit", "M13.5 4.5h-7a1.5 1.5 0 0 0-1.5 1.5v12a1.5 1.5 0 0 0 1.5 1.5h7", "M10.5 12h10", "M17 8.5l3.5 3.5l-3.5 3.5") }

    /** A solid star for "saved"; the outlined [Star] means "not saved". */
    val StarFilled by lazy {
        ImageVector.Builder(name = "MpIcons.StarFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes("M12 3.75l2.47 5.01l5.53 0.8l-4 3.9l0.94 5.5L12 16.36l-4.94 2.6l0.94-5.5l-4-3.9l5.53-0.8L12 3.75Z"),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = "MpIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.75f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}
