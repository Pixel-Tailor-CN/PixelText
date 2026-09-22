package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import vip.mystery0.pixel.text.domain.backup.BackupRepository
import vip.mystery0.pixel.text.domain.backup.BackupSection

class BackupViewModel(private val repository: BackupRepository) : ViewModel() {
    val state = repository.state
    fun exportTo(uri: String, sections: Set<BackupSection>, password: CharArray?) = repository.exportTo(uri, sections, password)
    fun inspect(uri: String, password: CharArray?) = repository.inspect(uri, password)
    fun restore(token: String, sections: Set<BackupSection>) = repository.restore(token, sections)
    fun cancel() = repository.cancel()
    fun acknowledge(disableCleanup: Boolean) = repository.acknowledgeResult(disableCleanup)
}
