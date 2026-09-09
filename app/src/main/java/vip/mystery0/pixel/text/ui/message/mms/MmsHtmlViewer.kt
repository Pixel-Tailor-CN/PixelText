package vip.mystery0.pixel.text.ui.message.mms

import androidx.core.net.toUri

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.data.source.mms.MmsHtmlResourceHandler
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlDocument
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlParser
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

@Composable
fun MmsHtmlViewer(
    part: MmsPartContent,
    allParts: List<MmsPartContent>,
    onBack: () -> Unit,
    parser: MmsHtmlParser = koinInject(),
    mirror: MessageMirrorRepository = koinInject(),
) {
    BackHandler(onBack = onBack)
    val document by produceState<MmsHtmlDocument?>(null, part, allParts) {
        value = null
        value = withContext(Dispatchers.Default) { parser.prepare(part, allParts) }
    }
    val context = LocalContext.current
    var pendingLink by remember(document) { mutableStateOf<String?>(null) }
    var unavailable by remember(document) { mutableStateOf(false) }
    var plainOnly by remember(part.key) { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    val fontScale = LocalDensity.current.fontScale
    Scaffold(topBar = { TopAppBar(title = { Text("离线网页") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") }
    }, actions = { TextButton(onClick = { plainOnly = !plainOnly }) { Text(if (plainOnly) "网页" else "纯文本") }; MmsAttachmentActions(part, menuOnly = true) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MmsPartStatus(part)
            val prepared = document
            when {
                prepared == null -> CircularProgressIndicator()
                unavailable || plainOnly || part.text == null || prepared.issue != null -> {
                    if (unavailable) Text("系统网页组件不可用，仍可阅读纯文本或导出原件")
                    if (prepared.issue != null) Text("网页超过安全预览限制，可导出原件")
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(prepared.plainText.ifBlank { "没有可显示的文本" })
                    }
                }
                else -> {
                    if (prepared.unresolvedReferenceCount > 0) Text("${prepared.unresolvedReferenceCount} 个图片引用不可用或已阻止")
                    // 主题只修改应用生成的样式，不保留来信样式。主题切换重建页面与资源拦截器。
                    key(prepared.documentUrl, dark) {
                        val themed = remember(prepared, dark) { prepared.copy(html = prepared.html.replace(
                            "@media(prefers-color-scheme:dark)", if (dark) "@media all" else "@media not all",
                        )) }
                        AndroidView(modifier = Modifier.weight(1f).fillMaxSize(), factory = { viewContext ->
                            val container = FrameLayout(viewContext)
                            try {
                                val handler = MmsHtmlResourceHandler(viewContext, themed, mirror)
                                val webView = WebView(viewContext)
                                // 先接入容器；初始化失败也能释放已创建的 WebView。
                                container.addView(webView, FrameLayout.LayoutParams(-1, -1))
                                webView.settings.apply {
                                    javaScriptEnabled = false
                                    javaScriptCanOpenWindowsAutomatically = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    blockNetworkLoads = true
                                    // 允许拦截器供应 HTTPS 外形的本地图片；实际网络仍由 blockNetworkLoads 禁用。
                                    blockNetworkImage = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    domStorageEnabled = false
                                    databaseEnabled = false
                                    cacheMode = WebSettings.LOAD_NO_CACHE
                                    setSupportMultipleWindows(false)
                                    mediaPlaybackRequiresUserGesture = true
                                    textZoom = (fontScale * 100).toInt()
                                }
                                webView.isLongClickable = false
                                webView.setOnLongClickListener { true }
                                webView.webViewClient = MmsHtmlWebViewClient(
                                    document = themed,
                                    handler = handler,
                                    container = container,
                                    onExternalLink = { pendingLink = it },
                                    onUnavailable = { unavailable = true },
                                )
                                webView.loadUrl(themed.documentUrl)
                            } catch (_: Exception) {
                                releaseHtmlWebView(container)
                                unavailable = true
                            } catch (_: LinkageError) {
                                releaseHtmlWebView(container)
                                unavailable = true
                            }
                            container
                        }, update = { container ->
                            (container.getChildAt(0) as? WebView)?.settings?.textZoom = (fontScale * 100).toInt()
                        }, onRelease = ::releaseHtmlWebView)
                    }
                }
            }
        }
    }
    pendingLink?.let { link ->
        val scheme = link.toUri().scheme?.lowercase()
        val system = scheme == "tel" || scheme == "mailto"
        AlertDialog(onDismissRequest = { pendingLink = null }, title = { Text(if (system) "打开系统应用？" else "在浏览器中打开链接？") },
            text = { Text(if (system) "将打开拨号或邮件编辑界面：\n$link" else "此操作将离开彩信并访问网络：\n$link") },
            confirmButton = { TextButton(onClick = {
                pendingLink = null
                try {
                    val action = when (scheme) { "tel" -> Intent.ACTION_DIAL; "mailto" -> Intent.ACTION_SENDTO; else -> Intent.ACTION_VIEW }
                    context.startActivity(Intent(action, link.toUri()).apply { if (!system) addCategory(Intent.CATEGORY_BROWSABLE) })
                } catch (_: Exception) { Toast.makeText(context, "没有可用的处理应用", Toast.LENGTH_SHORT).show() }
            }) { Text(if (system) "打开应用" else "打开浏览器") } },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("取消") } })
    }
}

private fun releaseHtmlWebView(container: FrameLayout) {
    val view = container.getChildAt(0) as? WebView
    container.removeAllViews()
    view?.stopLoading()
    view?.destroy()
}

/** 将生命周期回调集中到具名客户端，便于检查渲染进程退出后的清理行为。 */
// WebKit Lint 将 Kotlin 父类构造调用误报为未处理退出；下方已覆写回调并销毁视图、降级纯文本。
@SuppressLint("MissingOnRenderProcessGone")
private class MmsHtmlWebViewClient(
    private val document: MmsHtmlDocument,
    private val handler: MmsHtmlResourceHandler,
    private val container: FrameLayout,
    private val onExternalLink: (String) -> Unit,
    private val onUnavailable: () -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        if (request.method == "GET") handler.intercept(request.url) else MmsHtmlResourceHandler.denied()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = document.externalLinks[request.url.toString()]
        if (request.isForMainFrame && request.hasGesture() && !request.isRedirect && target != null) {
            onExternalLink(target)
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (url != document.documentUrl) view.stopLoading()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        container.removeView(view)
        view.destroy()
        onUnavailable()
        return true
    }
}
