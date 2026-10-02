package io.github.yuriyurin.fit3companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Samsung fit3_device artwork, cropped at render time to the case (no strap).
 * The source stays unmodified. Its transparent aperture is 242 × 378 pixels.
 * Keep one geometry for every catalog, BIN and home preview; no painted bezel.
 */
@Composable
internal fun Fit3PreviewFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val body = ImageBitmap.imageResource(R.drawable.fit3_preview_body)
    BoxWithConstraints(modifier.aspectRatio(328f / 472f)) {
        Box(Modifier.offset(maxWidth * (38f / 328f), maxHeight * (34f / 472f))
            .size(maxWidth * (242f / 328f), maxHeight * (378f / 472f))
            .clip(RoundedCornerShape(maxWidth * (30f / 328f)))
            .background(Color.Black), contentAlignment = Alignment.Center, content = content)
        // Draw after the face: the case covers edge pixels and preserves its highlights.
        Canvas(Modifier.fillMaxSize()) {
            // A rectangular crop left the white strap visible outside the curved case.
            // Clip the original artwork to the case silhouette, including its side button.
            val silhouette = Path().apply {
                addRoundRect(RoundRect(Rect(2f, 5f, 320f, 463f), CornerRadius(60f)))
                addRoundRect(RoundRect(Rect(317f, 183f, 326f, 287f), CornerRadius(3f)))
            }
            withTransform({ scale(size.width / 328f, size.height / 472f, Offset.Zero) }) {
                clipPath(silhouette) {
                    drawImage(body, srcOffset = IntOffset(378, 158), srcSize = IntSize(328, 472),
                        dstSize = IntSize(328, 472), filterQuality = FilterQuality.High)
                }
            }
        }
    }
}
