package io.github.arnavdugad.metropulse.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Operator logos and line symbols bundled in assets/logos (from Wikimedia Commons; credits in
 * licenses/LOGOS.md and the About screen). The marks belong to their owners; MetroPulse is
 * independent and not endorsed by any operator.
 */
object Logos {
    private const val DIR = "logos"
    private var files: Map<String, String>? = null
    private val cache = LruCache<String, Bitmap>(120)

    /** Logo key -> file name, read once from the assets folder. */
    private fun files(context: Context): Map<String, String> = files ?: synchronized(this) {
        files ?: context.assets.list(DIR).orEmpty()
            .filter { it.endsWith(".svg") || it.endsWith(".png") }
            .associateBy { it.substringBeforeLast('.') }
            .also { files = it }
    }

    /** A line's own symbol (New York bullets, London roundels), or its operator's mark where the line has none. */
    fun lineKey(context: Context, lineId: String): String? {
        val all = files(context)
        "line-$lineId".takeIf(all::containsKey)?.let { return it }
        return when {
            lineId.startsWith("lon-dlr") -> "op-london-dlr"
            lineId == "mum-l1" -> "op-mumbai-metro-one"
            lineId == "mum-l3" -> "op-mumbai-mmrcl"
            lineId.startsWith("mum-") -> "op-mumbai-mmmocl"
            else -> null
        }?.takeIf(all::containsKey)
    }

    /** The city's metro operator mark. */
    fun operatorKey(context: Context, cityId: String): String? {
        val all = files(context)
        return listOf("op-$cityId", "op-$cityId-metro-one").firstOrNull(all::containsKey)
    }

    /** The logo drawn [heightPx] tall at its own aspect ratio; null if missing or unreadable. */
    fun bitmap(context: Context, key: String, heightPx: Int): Bitmap? {
        val cacheKey = "$key@$heightPx"
        cache.get(cacheKey)?.let { return it }
        val file = files(context)[key] ?: return null
        val bitmap = runCatching {
            context.assets.open("$DIR/$file").use { input ->
                if (file.endsWith(".png")) {
                    val raw = BitmapFactory.decodeStream(input) ?: return null
                    val w = (raw.width * heightPx.toFloat() / raw.height).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(raw, w, heightPx, true)
                } else {
                    val svg = SVG.getFromInputStream(input)
                    // Some files carry only a width and height: give them a viewBox so they scale.
                    if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
                    val box = svg.documentViewBox
                    val w = (box.width() * heightPx / box.height()).roundToInt().coerceIn(1, heightPx * 8)
                    svg.setDocumentWidth(w.toFloat())
                    svg.setDocumentHeight(heightPx.toFloat())
                    Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
                }
            }
        }.getOrNull() ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap
    }
}

/** A bundled logo [height] tall, decoded off the main thread; nothing until ready (or if missing). */
@Composable
fun Logo(key: String, height: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { height.roundToPx() }
    val image by produceState<ImageBitmap?>(null, key, px) {
        value = withContext(Dispatchers.Default) { Logos.bitmap(context, key, px)?.asImageBitmap() }
    }
    image?.let { img ->
        val width = with(LocalDensity.current) { img.width.toDp() }
        Image(img, contentDescription, modifier.height(height).width(width))
    }
}
