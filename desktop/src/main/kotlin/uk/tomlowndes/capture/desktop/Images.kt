package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/** Loads vault images (a File) and web images (a URL string), shrunk for display, cached in memory. */
object Images {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun load(model: Any, maxSide: Int): ImageBitmap? {
        val key = "$model@$maxSide"
        cache[key]?.let { return it }
        val img = runCatching {
            when (model) {
                is File -> ImageIO.read(model)
                is String -> URI(model).toURL().openConnection().run {
                    setRequestProperty("User-Agent", UA); connectTimeout = 10_000; readTimeout = 15_000
                    getInputStream().use { ImageIO.read(it) }
                }
                else -> null
            }
        }.getOrNull() ?: return null
        return shrink(img, maxSide).toComposeImageBitmap().also { cache[key] = it }
    }

    fun shrink(img: BufferedImage, maxSide: Int): BufferedImage {
        val scale = maxSide.toDouble() / max(img.width, img.height)
        if (scale >= 1.0) return img
        val w = (img.width * scale).roundToInt().coerceAtLeast(1)
        val h = (img.height * scale).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(img, 0, 0, w, h, java.awt.Color.WHITE, null)
            dispose()
        }
        return out
    }

    /** At most 1600 px on the long side, as JPEG, like the Android app saves photos. */
    fun toJpeg(img: BufferedImage): ByteArray {
        val src = shrink(img, 1600).let { if (it.type == BufferedImage.TYPE_INT_RGB) it else flatten(it) }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.85f }
            writer.write(null, IIOImage(src, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun flatten(img: BufferedImage): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(img, 0, 0, java.awt.Color.WHITE, null); dispose() }
        return out
    }

    fun read(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    /** An image on the clipboard (a screenshot, or a copied image file), if there is one. */
    fun fromClipboard(): List<BufferedImage> {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        return runCatching {
            when {
                cb.isDataFlavorAvailable(DataFlavor.imageFlavor) ->
                    listOfNotNull((cb.getData(DataFlavor.imageFlavor) as? java.awt.Image)?.let(::toBuffered))
                cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
                    (cb.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().mapNotNull(::read)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun toBuffered(img: java.awt.Image): BufferedImage {
        if (img is BufferedImage) return img
        val out = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(img, 0, 0, java.awt.Color.WHITE, null); dispose() }
        return out
    }
}

/** An image from the vault or the web, loaded off the UI thread. Shows nothing until (and unless) it loads. */
@Composable
fun AsyncImage(model: Any?, modifier: Modifier = Modifier, maxSide: Int = 1200, scale: ContentScale = ContentScale.Crop) {
    var bmp by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(model) { if (model != null) bmp = withContext(Dispatchers.IO) { Images.load(model, maxSide) } }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = scale) else Box(modifier)
}
