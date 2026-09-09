package vip.mystery0.pixel.text.mms

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/** 私有同步提交日志；每条记录独立隔离，升级不重建既有镜像数据库。 */
internal class MmsReceptionJournal(context: Context, name: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun get(key: String): JSONObject? = synchronized(lock) {
        (preferences.all[key] as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    fun entries(): List<Pair<String, JSONObject>> = synchronized(lock) {
        preferences.all.mapNotNull { (key, value) ->
            (value as? String)?.let { runCatching { key to JSONObject(it) }.getOrNull() }
        }
    }
    // 必须检查同步写入结果；KTX edit 不返回 commit 的成功状态。
    @SuppressLint("UseKtx")
    fun put(key: String, value: JSONObject) = synchronized(lock) {
        check(preferences.edit().putString(key, value.put("schema", 1).toString()).commit()) { "mms journal unavailable" }
    }
    // 删除日志同样必须确认已同步持久化。
    @SuppressLint("UseKtx")
    fun remove(key: String) = synchronized(lock) {
        check(preferences.edit().remove(key).commit()) { "mms journal unavailable" }
    }
    companion object {
        private val lock = Any()
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
