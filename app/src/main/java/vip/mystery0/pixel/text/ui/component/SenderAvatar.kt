package vip.mystery0.pixel.text.ui.component

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.ui.theme.getAvatarColor

@Composable
fun SenderAvatar(
    identifier: String,
    avatarPath: String?,
    avatarSha256: String?,
    selected: Boolean,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val cacheKey = avatarPath?.let { "$it:${avatarSha256.orEmpty()}" }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(avatarCache::get)) }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null || bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(avatarPath)?.asImageBitmap()?.also {
                avatarCache.put(cacheKey, it)
            }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else getAvatarColor(identifier)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.67f),
            )
        }
    }
}

private val avatarCache = object : LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(32) {}
