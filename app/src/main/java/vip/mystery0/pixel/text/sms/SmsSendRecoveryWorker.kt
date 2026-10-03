package vip.mystery0.pixel.text.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SmsSendRecoveryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val coordinator: SmsSendCoordinator by inject()
    override suspend fun doWork(): Result =
        if (coordinator.recover()) Result.retry() else Result.success()
}
