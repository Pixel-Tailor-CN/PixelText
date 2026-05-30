package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.util.SimInfoProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onResultClick: (MessageModel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchFilter by viewModel.searchFilter.collectAsState()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val simList = remember { SimInfoProvider.getActiveSimList(context).take(2) }
    val firstSim = simList.getOrNull(0)
    val secondSim = simList.getOrNull(1)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateQuery(it) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索短信",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.updateQuery("")
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                FilterChip(
                    selected = searchFilter.unreadOnly,
                    onClick = viewModel::toggleUnreadFilter,
                    label = { Text("未读") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.simSubId == firstSim?.subscriptionId,
                    onClick = {
                        firstSim?.let { viewModel.toggleSimFilter(it.subscriptionId) }
                    },
                    enabled = firstSim != null,
                    label = { Text(firstSim?.displayName ?: "卡一") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.simSubId == secondSim?.subscriptionId,
                    onClick = {
                        secondSim?.let { viewModel.toggleSimFilter(it.subscriptionId) }
                    },
                    enabled = secondSim != null,
                    label = { Text(secondSim?.displayName ?: "卡二") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = searchFilter.mmsOnly,
                    onClick = viewModel::toggleMmsFilter,
                    label = { Text("彩信") }
                )
            }

            // Results
            SearchResultList(
                uiState = uiState,
                query = searchQuery,
                onResultClick = onResultClick
            )

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
