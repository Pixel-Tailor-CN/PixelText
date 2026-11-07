package vip.mystery0.pixel.text.domain.spam

/** 持久化的最终判定来源；数值属于数据库协议，不能随枚举顺序变化。 */
object MessageSpamSource {
    const val UNKNOWN = 0
    const val MODEL = 1
    const val WHITELIST = 2
    const val RELEASED = 3
    const val KEYWORD = 4
    const val CORRECTION = 5
    const val THRESHOLD = 0.7f
}
