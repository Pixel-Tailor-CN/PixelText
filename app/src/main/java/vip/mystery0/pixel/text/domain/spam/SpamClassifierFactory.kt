package vip.mystery0.pixel.text.domain.spam

fun interface SpamClassifierFactory {
    fun create(): SpamClassifier
}
