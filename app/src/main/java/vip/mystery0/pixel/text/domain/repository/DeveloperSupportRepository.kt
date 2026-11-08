package vip.mystery0.pixel.text.domain.repository

import vip.mystery0.pixel.text.domain.model.DeveloperSupportConfig
import vip.mystery0.pixel.text.domain.model.SupportChannel

interface DeveloperSupportRepository {
    suspend fun loadConfig(): DeveloperSupportConfig
    suspend fun loadQrCode(channel: SupportChannel): ByteArray
    suspend fun saveQrCode(channel: SupportChannel, bytes: ByteArray)
}
