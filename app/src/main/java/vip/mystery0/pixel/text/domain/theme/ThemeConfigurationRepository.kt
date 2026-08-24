package vip.mystery0.pixel.text.domain.theme

import kotlinx.coroutines.flow.StateFlow

interface ThemeConfigurationRepository {
    val configuration: StateFlow<ThemeConfiguration>

    /**
     * Persists [configuration].
     *
     * Once invoked, the write is non-cancellable: cancellation of the caller job does not
     * abort mutex acquisition or disk commit. Failed commits leave [configuration] unchanged.
     */
    suspend fun save(configuration: ThemeConfiguration): Result<Unit>

    /**
     * Applies [transform] to the latest configuration and persists the result under a mutex.
     *
     * Once invoked, the transaction is non-cancellable with the same guarantee as [save].
     * Failed commits leave [configuration] unchanged.
     */
    suspend fun update(
        transform: (ThemeConfiguration) -> ThemeConfiguration,
    ): Result<Unit>
}
