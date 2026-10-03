package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.mms.outgoing.MmsSendCoordinator

/** 重试只恢复本地阶段。已提交 attempt 永远不会再次调用传输。 */
class MmsSendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters), KoinComponent {
    private val coordinator: MmsSendCoordinator by inject()
    override suspend fun doWork(): Result = try {
        if (coordinator.recover()) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
