package vip.mystery0.pixel.text.domain.spam.learning

/** 每个会话独占解释器，个人参数不写入公共模型。 */
interface SpamModelEngine : AutoCloseable {
    val identity: String
    val modelVersion: String
    val parameterCount: Int
    val epochs: Int
    fun normalize(text: String): String
    fun reset(): FloatArray
    fun train(text: String, label: Float, parameters: FloatArray): FloatArray
    fun inferPublic(text: String): Float
    fun inferPersonal(text: String, parameters: FloatArray): Float
}
