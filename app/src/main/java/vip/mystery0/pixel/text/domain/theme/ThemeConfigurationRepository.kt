package vip.mystery0.pixel.text.domain.theme

import kotlinx.coroutines.flow.StateFlow

interface ThemeConfigurationRepository {
    val configuration: StateFlow<ThemeConfiguration>

    suspend fun save(configuration: ThemeConfiguration): Result<Unit>

    suspend fun update(
        transform: (ThemeConfiguration) -> ThemeConfiguration,
    ): Result<Unit>
}
