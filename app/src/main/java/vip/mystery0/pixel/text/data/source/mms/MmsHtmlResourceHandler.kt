package vip.mystery0.pixel.text.data.source.mms

import androidx.core.net.toUri

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlDocument
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

/** 每个页面单独登记，只在 WebView 的 IO 拦截回调中使用；不映射目录。 */
class MmsHtmlResourceHandler(context: Context, private val document: MmsHtmlDocument, private val mirror: MessageMirrorRepository) {
    private val mirrorRoot = File(context.applicationContext.noBackupFilesDir, "message-mirror").canonicalFile
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(document.origin.toUri().host!!)
        .setHttpAllowed(false)
        .addPathHandler("/", WebViewAssetLoader.PathHandler { path -> registeredResponse("${document.origin}/$path") })
        .build()

    fun intercept(uri: Uri): WebResourceResponse {
        // 原始 URL 精确命中才允许交给 AssetLoader，包含端口、查询或跨消息路径的一律拒绝。
        val url = uri.toString()
        if (url != document.documentUrl && url !in document.resourceMap) return denied()
        return loader.shouldInterceptRequest(uri) ?: denied()
    }

    private fun registeredResponse(url: String): WebResourceResponse {
        if (url == document.documentUrl) return response("text/html", document.html.toByteArray().inputStream())
        val key = document.resourceMap[url] ?: return denied()
        return try {
            val part = runBlocking(Dispatchers.IO) { mirror.getMessage(key.message) }?.parts
                ?.singleOrNull { it.sourceId == key.partId } ?: return denied()
            val attachment = part.attachment ?: return denied()
            val mime = MmsMimeTypes.normalize(part.mimeType)
            if (attachment.state != MirrorAttachmentState.READY || mime !in MmsHtmlParser.IMAGE_TYPES ||
                (attachment.byteCount ?: Long.MAX_VALUE) > MAX_RESOURCE_BYTES) return denied()
            val uri = URI(attachment.localUri ?: return denied())
            if (uri.scheme != "file") return denied()
            val file = File(uri).canonicalFile
            if (file.parentFile != mirrorRoot || !file.isFile || file.length() > MAX_RESOURCE_BYTES) return denied()
            response(mime, file.inputStream())
        } catch (_: Exception) { denied() }
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 32L * 1024 * 1024
        fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", HEADERS, ByteArrayInputStream(ByteArray(0)))
        private val HEADERS = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer")
        private fun response(mime: String, input: java.io.InputStream) = WebResourceResponse(mime, "UTF-8", 200, "OK", HEADERS, input)
    }
}
