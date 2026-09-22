package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.style.LibraryActionBadges
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryActionKind
import vip.mystery0.pixel.text.R

@Composable
fun OpenSourceScreen(onNavigateBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    var licenseLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源声明") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            dialogLibrary = libraries?.libraries?.firstOrNull { it.uniqueId == licenseLibraryId },
            sheetLibrary = null,
            onDialogLibraryChange = { licenseLibraryId = it?.uniqueId },
            onSheetLibraryChange = {},
            onActionClick = { library, action ->
                if (action == LibraryActionKind.License) {
                    // 上游默认优先打开许可 URL，这里强制使用打包正文以支持离线阅读。
                    licenseLibraryId = library.uniqueId
                } else {
                    val url = when (action) {
                        LibraryActionKind.Source -> library.scm?.url
                        LibraryActionKind.Website -> library.website
                        else -> null
                    }
                    val opened = url?.takeIf {
                        it.startsWith("https://") || it.startsWith("http://")
                    }?.let { runCatching { uriHandler.openUri(it) }.isSuccess } ?: false
                    if (!opened) Toast.makeText(context, "无法打开此链接，请尝试项目主页", Toast.LENGTH_SHORT).show()
                }
                true
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            actionLabels = LibraryActionBadges(
                source = "源码", website = "项目主页", viewLicense = "查看许可",
                sponsorEnabled = false,
            ),
            licenseDialogConfirmText = "关闭",
            header = {
                item {
                    Text(
                        text = "感谢这些开源项目。此处包含应用依赖及引入源码的声明；许可正文可离线查看，项目链接将在浏览器中打开。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // 直接显示原文，避免默认 HTML 清理误删许可证内的尖括号和原始换行。
            licenseDialogBody = { library, modifier ->
                Column(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    library.licenses.forEach { license ->
                        Text(license.name, style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                text = license.licenseContent?.takeIf { it.isNotBlank() }
                                    ?: "当前组件未提供许可正文，请查看项目主页。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
        )
    }
}
