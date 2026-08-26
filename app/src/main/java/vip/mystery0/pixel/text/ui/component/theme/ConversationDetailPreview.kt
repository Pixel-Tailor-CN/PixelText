package vip.mystery0.pixel.text.ui.component.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import vip.mystery0.pixel.text.ui.message.MessageItem
import vip.mystery0.pixel.text.ui.screen.mock.MockMessageSpec
import vip.mystery0.pixel.text.ui.theme.ResolvedConversationDetailStyle
import vip.mystery0.pixel.text.ui.theme.rememberConversationBackgroundColorScheme
import vip.mystery0.pixel.text.ui.theme.ThemeBackgroundImage
import java.io.File

val ConversationDetailPreviewMessageSpecs: List<MockMessageSpec> = listOf(
    MockMessageSpec(
        content = "宝宝，我可以摸摸你那里吗？",
        isReceived = true,
        simName = "卡1",
        parsedResultOverride = ParsedResult.None,
    ),
    MockMessageSpec(
        content = "哪里？",
        isReceived = false,
        simName = "卡1",
        parsedResultOverride = ParsedResult.None,
    ),
    MockMessageSpec(
        content = "想啥呢？我说摸摸那里的钱包",
        isReceived = true,
        simName = "卡1",
        parsedResultOverride = ParsedResult.None,
    ),
    MockMessageSpec(
        content = "🤡\n👆🤣",
        isReceived = false,
        simName = "卡1",
        parsedResultOverride = ParsedResult.None,
    ),
)

@Composable
fun ConversationDetailPreviewTheme(
    mode: ThemeMode,
    backgroundFile: File? = null,
    generateColorsFromBackgroundImage: Boolean = false,
    fallbackColorScheme: ColorScheme? = null,
    applyGeneratedColorScheme: Boolean = true,
    content: @Composable (ColorScheme, Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val fallback = fallbackColorScheme ?: when (mode) {
        ThemeMode.LIGHT -> dynamicLightColorScheme(context)
        ThemeMode.DARK -> dynamicDarkColorScheme(context)
    }
    val result = rememberConversationBackgroundColorScheme(
        file = backgroundFile,
        mode = mode,
        enabled = generateColorsFromBackgroundImage,
        fallback = fallback,
    )
    MaterialExpressiveTheme(
        colorScheme = if (applyGeneratedColorScheme) result.colorScheme else fallback,
    ) {
        content(
            result.colorScheme,
            result.usesBackgroundImageColors,
            result.isLoading,
        )
    }
}

@Composable
fun ConversationDetailPreview(
    messages: List<MessageModel>,
    style: ResolvedConversationDetailStyle,
    timeDisplayFormat: MessageTimeDisplayFormat,
    backgroundFile: File?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ThemeBackgroundImage(
                    file = backgroundFile,
                    modifier = Modifier.matchParentSize(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    userScrollEnabled = true,
                ) {
                    items(
                        items = messages,
                        key = { it.stableKey },
                    ) { message ->
                        MessageItem(
                            message = message,
                            isSelected = false,
                            textScale = style.textScale,
                            originalMessageStyle = style.originalMessage,
                            showSimInfo = style.showSimInfo,
                            interactionEnabled = false,
                            timeDisplayFormat = timeDisplayFormat,
                            onClick = {},
                            onLongClick = {},
                        )
                    }
                }
            }

            Surface(
                color = style.inputArea.backgroundColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = style.inputPlaceholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
