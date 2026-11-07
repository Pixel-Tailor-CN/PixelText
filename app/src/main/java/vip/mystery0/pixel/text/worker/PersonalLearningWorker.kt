package vip.mystery0.pixel.text.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningCoordinator

class PersonalLearningWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val coordinator: PersonalLearningCoordinator by inject()
    override suspend fun doWork(): Result = if (coordinator.runLearning()) Result.success() else Result.failure()
}
