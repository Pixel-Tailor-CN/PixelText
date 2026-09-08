package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentModel

interface MmsContentRepository {
    /** 首次解析先发射准备状态；镜像更新取消旧解析；删除消息后发射 null。 */
    fun observe(key: SourceMessageKey): Flow<MmsContentModel?>
    /** 在 IO 调度器中读取完整内容；不存在或非 MMS 返回 null。 */
    suspend fun read(key: SourceMessageKey): MmsContentModel?
}
