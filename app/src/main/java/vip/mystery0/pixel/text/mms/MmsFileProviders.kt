package vip.mystery0.pixel.text.mms

import android.net.Uri
import androidx.core.content.FileProvider

// Android 按组件类名缓存 Provider 实例；不同 authority 必须使用独立组件，避免路径策略串用。
class MmsSendFileProvider : FileProvider()
class MmsDownloadFileProvider : FileProvider()
class MmsAttachmentFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val inferred = super.getType(uri)
        // 部分系统将 m4a 映射成 audio/mpeg；应用采集明确使用 MPEG-4/AAC，不能改标签成 MP3。
        return if (uri.path?.startsWith("/mms_share/capture/") == true &&
            uri.lastPathSegment?.endsWith(".m4a", ignoreCase = true) == true) "audio/mp4" else inferred
    }
}
