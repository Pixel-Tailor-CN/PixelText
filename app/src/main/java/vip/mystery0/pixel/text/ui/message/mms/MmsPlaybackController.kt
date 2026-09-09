@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package vip.mystery0.pixel.text.ui.message.mms

import kotlin.time.Duration.Companion.milliseconds

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.data.source.mms.localMmsUri
import vip.mystery0.pixel.text.data.source.mms.mediaCacheKey
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository

data class MmsPlaybackState(
    val partKey: MmsPartKey? = null,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val error: String? = null,
    val isBuffering: Boolean = false,
    /** 播放或缓冲中的播放意图；空闲、结束和出错时不提供暂停动作。 */
    val playRequested: Boolean = false,
)

/** 全应用单播放器；查看页持有会话，列表卡片不创建或准备播放器。所有播放操作在主线程。 */
class MmsPlaybackController(context: Context, private val mirror: MessageMirrorRepository) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MmsPlaybackState())
    val state: StateFlow<MmsPlaybackState> = mutableState.asStateFlow()
    data class PresentationRequest(val message: SourceMessageKey, val serial: Long)
    private var requestSerial = 0L
    private var mediaRequest = 0L
    private val mutablePresentation = MutableStateFlow<PresentationRequest?>(null)
    val presentation: StateFlow<PresentationRequest?> = mutablePresentation.asStateFlow()
    fun beginPresentation(key: SourceMessageKey): PresentationRequest {
        checkMainThread()
        stop()
        return PresentationRequest(key, ++requestSerial).also { mutablePresentation.value = it }
    }
    fun ownsPresentation(request: PresentationRequest) = mutablePresentation.value == request
    fun endPresentation(request: PresentationRequest) {
        checkMainThread()
        if (ownsPresentation(request)) stop()
    }
    /** 调度只提交仍持有身份的请求；用户操作会立即撤销身份。 */
    fun playPresentation(request: PresentationRequest, part: MmsPartContent) {
        checkMainThread()
        if (ownsPresentation(request)) playCurrent(part)
    }
    fun stopPresentationMedia(request: PresentationRequest) {
        checkMainThread()
        if (ownsPresentation(request)) stopCurrent()
    }
    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()
    private var owner: Any? = null
    private var foreground = false
    private var scope: CoroutineScope? = null
    private var sourceObservation: Job? = null
    private var contentKey: String? = null

    fun acquire(session: Any) {
        checkMainThread()
        if (owner === session) return
        releaseCurrent()
        owner = session
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        mutablePlayer.value = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateState()
                override fun onPlayerError(error: PlaybackException) {
                    mutableState.value = mutableState.value.copy(isPlaying = false, isBuffering = false, playRequested = false,
                        error = "无法播放此附件：文件损坏或设备不支持其编码，可保存原件后使用其他应用打开")
                }
            })
        }
        scope?.launch { while (true) { updateState(); delay(200.milliseconds) } }
    }

    fun setForeground(session: Any, visible: Boolean) {
        checkMainThread()
        if (owner !== session) return
        foreground = visible
        if (!visible) pause()
    }

    /** 旧页面延迟销毁时不能释放新页面已取得的播放器。 */
    fun release(session: Any) {
        checkMainThread()
        if (owner === session) releaseCurrent()
    }

    fun play(part: MmsPartContent) {
        checkMainThread()
        mutablePresentation.value = null
        playCurrent(part)
    }

    private fun playCurrent(part: MmsPartContent) {
        checkMainThread()
        val player = mutablePlayer.value ?: return
        if (!foreground) return
        val uri = localMmsUri(part.localUri)
        if (uri == null || part.state != MirrorAttachmentState.READY ||
            part.kind !in setOf(MmsContentKind.AUDIO, MmsContentKind.VIDEO)) {
            stop()
            mutableState.value = MmsPlaybackState(partKey = part.key, error = "附件尚不可播放")
            return
        }
        if (contentKey != part.mediaCacheKey() || player.playerError != null) {
            stopCurrent()
            contentKey = part.mediaCacheKey()
            mutableState.value = MmsPlaybackState(partKey = part.key)
            // 仅使用渐进式本地媒体；不根据扩展名或 MIME 启用 HLS/DASH 等清单网络源。
            val source = ProgressiveMediaSource.Factory(DataSource.Factory { LocalDataSource(context) })
                .createMediaSource(MediaItem.fromUri(uri))
            player.setMediaSource(source)
            player.prepare()
            val request = mediaRequest
            sourceObservation = scope?.launch {
                mirror.observeMessage(part.key.message).collect { message ->
                    val attachment = message?.parts?.firstOrNull { it.sourceId == part.key.partId }?.attachment
                    if (request != mediaRequest) return@collect
                    if (attachment == null || attachment.state != MirrorAttachmentState.READY ||
                        attachment.localUri != part.localUri || attachment.sha256 != part.contentHash) {
                        stopForSourceChange(part.key)
                    }
                }
            }
        } else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (contentKey != part.mediaCacheKey() || !foreground) return
        player.play()
        updateState()
    }

    fun pause() { checkMainThread(); mutablePresentation.value = null; mutablePlayer.value?.pause(); updateState() }
    fun seekTo(positionMillis: Long) {
        checkMainThread()
        mutablePresentation.value = null
        mutablePlayer.value?.let { player ->
            if (player.isCurrentMediaItemSeekable) player.seekTo(positionMillis.coerceIn(0, player.duration.coerceAtLeast(0)))
        }
        updateState()
    }

    fun stop() {
        checkMainThread()
        mutablePresentation.value = null
        stopCurrent()
    }

    /** 来源更新后保留受影响身份，后续模型刷新不能把原因清掉。 */
    private fun stopForSourceChange(key: MmsPartKey) {
        stop()
        mutableState.value = MmsPlaybackState(partKey = key, error = "附件已删除或更新，请重新打开")
    }

    fun onViewerSourceChanged(part: MmsPartContent) {
        checkMainThread()
        if (state.value.partKey == part.key) {
            if (contentKey != null && (contentKey != part.mediaCacheKey() || part.state != MirrorAttachmentState.READY)) {
                stopForSourceChange(part.key)
            }
        } else stop()
    }

    private fun stopCurrent() {
        checkMainThread()
        mediaRequest++
        sourceObservation?.cancel()
        sourceObservation = null
        mutablePlayer.value?.let { it.stop(); it.clearMediaItems() }
        contentKey = null
        mutableState.value = MmsPlaybackState()
    }

    /** 镜像删除回调可来自 IO；实际停止始终排入播放器主线程。 */
    fun onMessageDeleted(key: SourceMessageKey) {
        main.post { if (state.value.partKey?.message == key) stop() }
    }

    private fun releaseCurrent() {
        mutablePresentation.value = null
        stop()
        mutablePlayer.value?.release()
        mutablePlayer.value = null
        scope?.cancel()
        scope = null
        owner = null
        foreground = false
    }

    private fun updateState() {
        val player = mutablePlayer.value ?: return
        mutableState.value = mutableState.value.copy(
            isPlaying = player.isPlaying,
            positionMillis = player.currentPosition.coerceAtLeast(0),
            durationMillis = player.duration.coerceAtLeast(0),
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            playRequested = player.playWhenReady && player.playerError == null &&
                (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY),
        )
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}

/** 查看页或 SMIL 页外壳调用一次；回到前台保持暂停，必须再次由用户按下播放。 */
@Composable
fun MmsPlaybackSession(controller: MmsPlaybackController) {
    val session = remember(controller) { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, session) {
        controller.acquire(session)
        controller.setForeground(session, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.acquire(session)
            controller.setForeground(session, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.release(session) }
    }
}

/** 明确选择文件或 ContentResolver 数据源，不存在网络回退。 */
private class LocalDataSource(private val context: Context) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val uri = localMmsUri(dataSpec.uri.toString()) ?: throw IOException("unsupported local media source")
        val source = if (uri.scheme == "file") FileDataSource() else ContentDataSource(context)
        delegate = source
        listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IOException("media source not open")
    override fun getUri(): Uri? = delegate?.uri
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}
