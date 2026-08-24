package vip.mystery0.pixel.text.domain.theme

import java.io.File

data class ThemeImageDraft(
    val draftId: String,
    val mode: ThemeMode,
)

interface ThemeAssetRepository {
    suspend fun createDraftBackground(
        mode: ThemeMode,
        sourceUri: String,
    ): Result<ThemeImageDraft>

    suspend fun commitDraft(draft: ThemeImageDraft): Result<ThemeImageReference>
    fun discardDraft(draft: ThemeImageDraft)
    fun deleteAsset(reference: ThemeImageReference)
    fun resolve(reference: ThemeImageReference): File?
    fun resolve(draft: ThemeImageDraft): File?
    suspend fun cleanStaleDrafts()
}
