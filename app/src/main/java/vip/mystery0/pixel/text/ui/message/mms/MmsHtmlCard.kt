package vip.mystery0.pixel.text.ui.message.mms

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey

/** 整卡进入离线页，底部原件操作沿用统一导出链路。 */
@Composable
fun MmsHtmlCard(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    onOpenPart: (MmsPartKey) -> Unit = {},
    onFeedback: ((String) -> Unit)? = null,
) = MmsFileCard(part, modifier, isSelected, selectionMode, interactionEnabled, onMessageClick, onOpenPart, onFeedback)
