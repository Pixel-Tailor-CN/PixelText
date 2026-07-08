package vip.mystery0.pixel.text.domain.sample

enum class SampleReplacementError {
    INVALID_TEMPORAL_FORMAT,
}

sealed interface SampleReplacementResult {
    data class Success(val content: String) : SampleReplacementResult

    data class Failure(val error: SampleReplacementError) : SampleReplacementResult
}

class SampleDesensitizer(
    private val generator: FakeSampleGenerator = FakeSampleGenerator(),
    private val temporalGenerator: TemporalFormatPreservingGenerator =
        TemporalFormatPreservingGenerator(),
) {
    fun replace(
        content: String,
        start: Int,
        end: Int,
        type: SensitiveType,
    ): SampleReplacementResult {
        if (start < 0 || end > content.length || start >= end) {
            return SampleReplacementResult.Success(content)
        }
        val source = content.substring(start, end)
        val replacement = when (type) {
            SensitiveType.DATE,
            SensitiveType.TIME,
            SensitiveType.DATE_TIME -> {
                temporalGenerator.generate(type, source)
                    ?: return SampleReplacementResult.Failure(
                        SampleReplacementError.INVALID_TEMPORAL_FORMAT
                    )
            }

            else -> generator.generate(type, source)
        }
        return SampleReplacementResult.Success(content.replaceRange(start, end, replacement))
    }
}
