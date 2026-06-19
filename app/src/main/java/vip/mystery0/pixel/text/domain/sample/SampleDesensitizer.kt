package vip.mystery0.pixel.text.domain.sample

class SampleDesensitizer(
    private val generator: FakeSampleGenerator = FakeSampleGenerator(),
) {
    fun replace(
        content: String,
        start: Int,
        end: Int,
        type: SensitiveType,
    ): String {
        if (start < 0 || end > content.length || start >= end) return content
        val source = content.substring(start, end)
        val replacement = generator.generate(type, source)
        return content.replaceRange(start, end, replacement)
    }
}
