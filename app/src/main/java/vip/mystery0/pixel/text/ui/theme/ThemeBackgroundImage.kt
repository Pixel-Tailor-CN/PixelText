package vip.mystery0.pixel.text.ui.theme

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ThemeBackgroundImage"

@Composable
fun ThemeBackgroundImage(
    file: File?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val path = file?.absolutePath
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                decodeThemeBackground(path)
            }
        }
    }

    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private fun decodeThemeBackground(path: String): ImageBitmap? {
    return try {
        val source = File(path)
        if (!source.isFile) {
            Log.w(TAG, "theme background missing path=$path")
            return null
        }
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    } catch (error: Exception) {
        Log.w(TAG, "theme background decode failed path=$path", error)
        null
    }
}
