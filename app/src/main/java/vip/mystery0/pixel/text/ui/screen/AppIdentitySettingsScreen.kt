package vip.mystery0.pixel.text.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vip.mystery0.pixel.text.ui.screen.settings.SettingsPreference as Preference
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.AppDisplayName
import vip.mystery0.pixel.text.domain.settings.AppIconStyle
import vip.mystery0.pixel.text.domain.settings.AppIdentity
import vip.mystery0.pixel.text.viewmodel.AppIdentityViewModel

@Composable
fun AppIdentitySettingsPreference(
    onClick: () -> Unit,
    viewModel: AppIdentityViewModel = koinViewModel(),
) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    Preference(
        title = { Text("应用名称与图标") },
        summary = { Text("${identity.displayName.label} · ${identity.iconStyle.label}") },
        icon = { AppIdentityIcon(identity.iconStyle, Modifier.size(32.dp)) },
        onClick = onClick,
    )
}

@Composable
fun currentAppDisplayName(viewModel: AppIdentityViewModel = koinViewModel()): String {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    return identity.displayName.label
}

/** 设置页和关于页复用当前真实桌面图标，不以默认图标覆盖用户选择。 */
@Composable
internal fun CurrentAppIdentityIcon(viewModel: AppIdentityViewModel = koinViewModel()) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    AppIdentityIcon(identity.iconStyle, Modifier.size(32.dp))
}

@Composable
fun AppIdentitySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppIdentityViewModel = koinViewModel(),
) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    var nameValue by rememberSaveable { mutableStateOf(identity.displayName.storageValue) }
    var iconValue by rememberSaveable { mutableStateOf(identity.iconStyle.storageValue) }
    val displayName = AppDisplayName.entries.firstOrNull { it.storageValue == nameValue }
        ?: AppDisplayName.CHISU_FULL
    val iconStyle = AppIconStyle.selectableEntries.firstOrNull { it.storageValue == iconValue }
        ?: AppIconStyle.VIOLET
    val draft = AppIdentity(displayName, iconStyle)
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 写入期间保留页面，确保切换失败时提示仍然可见。
    BackHandler(enabled = updateState.isApplying) { }
    LaunchedEffect(updateState.applied) {
        if (updateState.applied) {
            Toast.makeText(context, "已应用，桌面显示可能稍有延迟", Toast.LENGTH_SHORT).show()
            viewModel.resetResult()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !updateState.isApplying) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("应用名称与图标") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    updateState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { viewModel.apply(draft) },
                        enabled = !updateState.isApplying && draft != identity,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(when {
                            updateState.isApplying -> "应用中…"
                            draft == identity -> "已应用"
                            else -> "应用"
                        })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().consumeWindowInsets(paddingValues).selectableGroup(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = paddingValues.calculateTopPadding() + 20.dp,
                end = 20.dp,
                bottom = paddingValues.calculateBottomPadding() + 20.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "preview", span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIdentityIcon(iconStyle, Modifier.size(80.dp))
                        Text(displayName.label, style = MaterialTheme.typography.titleLarge)
                        Text("桌面预览", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item(key = "names", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用名称", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppDisplayName.entries.forEach { name ->
                            FilterChip(
                                selected = name == displayName,
                                onClick = {
                                    nameValue = name.storageValue
                                    viewModel.resetResult()
                                },
                                enabled = !updateState.isApplying,
                                label = { Text(name.label) },
                            )
                        }
                    }
                }
            }
            item(key = "icon_heading", span = { GridItemSpan(maxLineSpan) }) {
                Text("应用图标", style = MaterialTheme.typography.titleSmall)
            }
            items(AppIconStyle.selectableEntries, key = AppIconStyle::storageValue) { style ->
                AppIconStyleCard(
                    style = style,
                    selected = style == iconStyle,
                    enabled = !updateState.isApplying,
                    onClick = {
                        iconValue = style.storageValue
                        viewModel.resetResult()
                    },
                )
            }
            item(key = "description", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "选择名称和图标后，点击「应用」保存；直接返回不会保存更改。名称同步用于桌面和应用主界面，系统通知顶部仍显示「原点尺素」。开启系统主题图标后，桌面配色由系统决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIconStyleCard(
    style: AppIconStyle,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick,
        ),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIdentityIcon(style, Modifier.size(64.dp))
                Text(style.label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
            if (selected) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Rounded.Check, "已选择", Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun AppIdentityIcon(style: AppIconStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resourceId = when (style) {
        AppIconStyle.VIOLET -> R.mipmap.ic_launcher_identity_violet
        AppIconStyle.BLUE -> R.mipmap.ic_launcher_identity_blue
        AppIconStyle.GREEN -> R.mipmap.ic_launcher_identity_green
        AppIconStyle.APRICOT -> R.mipmap.ic_launcher_identity_apricot
        AppIconStyle.CLASSIC -> R.mipmap.ic_launcher_classic
    }
    // 使用平台的自适应图标蒙版，避免 Compose 直接读取 adaptive-icon XML。
    val bitmap = remember(context, resourceId) {
        requireNotNull(ContextCompat.getDrawable(context, resourceId)).toBitmap(216, 216).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
}
