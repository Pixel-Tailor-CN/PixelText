package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.data.source.mms.localMmsUri
import vip.mystery0.pixel.text.data.source.mms.mediaCacheKey
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

@Composable
fun MmsImageContent(
    part: MmsPartContent,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    interactionEnabled: Boolean = true,
    imageNumber: Int? = null,
) {
    if (!part.statusInfo().actionable) {
        MmsPartStatus(part, modifier)
        return
    }
    MmsLocalImage(
        uri = part.localUri,
        cacheKey = part.mediaCacheKey(),
        cacheEnabled = part.contentHash != null,
        description = listOfNotNull(imageNumber?.let { "图片 $it" }, part.displayName).joinToString("，"),
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
            .clickable(enabled = interactionEnabled, onClickLabel = "查看图片", onClick = onOpen),
        visible = visible,
    )
}

/** 旧 URI 卡片同样复用 Coil；没有内容哈希时禁用缓存，不能假设 URI 内容不变。 */
@Composable
internal fun MmsLocalImage(
    uri: String?,
    cacheKey: String,
    cacheEnabled: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    imageLoader: ImageLoader = koinInject(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var inWindow by remember(uri) { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val localUri = remember(uri) { localMmsUri(uri) }
    val request = remember(context, localUri, cacheKey, cacheEnabled) {
        ImageRequest.Builder(context).data(localUri).memoryCacheKey(cacheKey)
            .memoryCachePolicy(if (cacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            // 限制查看页的解码尺寸；手势缩放不触发原图全尺寸分配。
            .size(2048, 2048).build()
    }
    Box(modifier.semantics { contentDescription = description }.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        inWindow = bounds.width > 0 && bounds.height > 0
    }, contentAlignment = Alignment.Center) {
        if (localUri == null) Text("图片尚不可读")
        else if (visible && resumed && inWindow) {
            // 从组合移除 painter 会停止 Animatable；覆盖后台、离屏和 SMIL 非当前页。
            SubcomposeAsyncImage(
                model = request, imageLoader = imageLoader, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "正在加载图片" })
                } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片无法解码，可保存原件") } },
            )
        }
    }
}
