package vip.mystery0.pixel.text.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class SettingsCategory(val title: String, val icon: ImageVector, val group: Int) {
    MESSAGES("消息与验证码", Icons.Rounded.ChatBubbleOutline, 0),
    SPAM("骚扰识别与拦截", Icons.Rounded.Shield, 0),
    DISPLAY("显示与外观", Icons.Rounded.Palette, 0),
    RESOURCES("模型与资源", Icons.Rounded.Memory, 1),
    BACKUP("备份与维护", Icons.Rounded.UploadFile, 1),
    PERMISSIONS("权限与默认应用", Icons.Rounded.Security, 2),
    ABOUT("关于与帮助", Icons.Rounded.Info, 2),
}

@Composable
fun SettingsPage(
    category: SettingsCategory?,
    wide: Boolean,
    sections: List<SettingsSection>,
    states: SaveableStateHolder,
    summary: (SettingsCategory) -> String,
    onSelect: (SettingsCategory) -> Unit,
    notice: String?,
    onNoticeClick: () -> Unit,
    targetSection: String?,
    onTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (wide || category == null) {
                states.SaveableStateProvider("home") {
                    CategoryList(
                        category, wide, summary, onSelect, notice, onNoticeClick,
                        if (wide) Modifier.width(320.dp) else Modifier.fillMaxSize(),
                    )
                }
            }
            if (wide) VerticalDivider()
            if (category != null) {
                states.SaveableStateProvider(category.name) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(targetSection) {
                        if (targetSection != null) {
                            val index = sections.indexOfFirst { it.key == targetSection }
                            if (index >= 0) {
                                listState.scrollToItem(sections.take(index).sumOf { it.rows.size + 1 } + if (wide) 1 else 0)
                            }
                            onTargetConsumed()
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f), state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        if (wide) {
                            item(key = "category_title", contentType = "heading") {
                                Text(category.title, Modifier.padding(top = 16.dp).semantics { heading() },
                                    style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                        sections.forEach { section ->
                            item(key = section.key, contentType = "heading") {
                                Text(
                                    section.title.orEmpty(),
                                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                                        .semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            section.rows.forEachIndexed { index, row ->
                                item(key = row.key, contentType = "setting") {
                                    Box(Modifier.padding(bottom = if (index == section.rows.lastIndex) 0.dp else 2.dp)) {
                                        SettingsRowContainer(index == 0, index == section.rows.lastIndex, content = row.content)
                                    }
                                }
                            }
                        }
                        if (category == SettingsCategory.SPAM) {
                            item(key = "local_processing_note") {
                                Text("本地识别、个人学习与规则匹配均在设备上完成。贡献样本需另行编辑并确认上传。",
                                    Modifier.padding(top = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (wide) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("选择左侧分类以调整设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    selectedCategory: SettingsCategory?,
    wide: Boolean,
    summary: (SettingsCategory) -> String,
    onSelect: (SettingsCategory) -> Unit,
    notice: String?,
    onNoticeClick: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        state = rememberLazyListState(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (notice != null) {
            item(key = "notice") {
                SettingsRowContainer(true, true) {
                    SettingsPreference(
                        title = { Text("完成短信应用设置") }, summary = { Text(notice) },
                        icon = { Icon(Icons.Rounded.Security, null) }, onClick = onNoticeClick,
                    )
                }
            }
        }
        SettingsCategory.entries.groupBy { it.group }.forEach { (group, categories) ->
            item(key = group) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    categories.forEachIndexed { index, category ->
                        Box(Modifier.semantics { selected = category == selectedCategory }) {
                            SettingsRowContainer(index == 0, index == categories.lastIndex,
                                active = wide && category == selectedCategory) {
                                SettingsPreference(
                                    title = { Text(category.title, color = if (category == selectedCategory)
                                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                    summary = { Text(summary(category)) },
                                    icon = {
                                        val colors = MaterialTheme.colorScheme
                                        val container = when (group) {
                                            0 -> colors.primaryContainer
                                            1 -> colors.secondaryContainer
                                            else -> colors.tertiaryContainer
                                        }
                                        val tint = when (group) {
                                            0 -> colors.onPrimaryContainer
                                            1 -> colors.onSecondaryContainer
                                            else -> colors.onTertiaryContainer
                                        }
                                        Box(Modifier.size(32.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(category.icon, null, Modifier.size(22.dp), tint = tint)
                                        }
                                    },
                                    onClick = { onSelect(category) },
                                    trailing = if (wide) ({
                                        if (category == selectedCategory) Icon(Icons.Rounded.Check, "当前分类")
                                    }) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
