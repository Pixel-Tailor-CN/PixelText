package vip.mystery0.pixel.text.ui.message.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    val domainFilter by viewModel.domainFilter.collectAsState()
    val context = LocalContext.current

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var lastScrolledGeneration by rememberSaveable { mutableStateOf(0L) }

    var inPhoneSubScreen by rememberSaveable { mutableStateOf(false) }
    var showSimSheet by rememberSaveable { mutableStateOf(false) }
    var showTransportSheet by rememberSaveable { mutableStateOf(false) }
    var showDateSheet by rememberSaveable { mutableStateOf(false) }

    var activeSims by remember { mutableStateOf(SimInfoProvider.getActiveSimList(context)) }

    fun refreshActiveSims(): List<vip.mystery0.pixel.text.util.SimInfo> {
        val fresh = SimInfoProvider.getActiveSimList(context)
        activeSims = fresh
        val activeSubIds = fresh.map { it.subscriptionId }.toSet()
        viewModel.refreshSims(activeSubIds)
        return fresh
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshActiveSims()
    }

    // 仅新代次发起查询时回到顶部，保存已消费代次；旋转、详情返回或 Room 同代次更新时不滚顶
    LaunchedEffect((uiState as? SearchUiState.Success)?.generation) {
        val currentSuccess = uiState as? SearchUiState.Success
        if (currentSuccess != null && currentSuccess.generation > lastScrolledGeneration) {
            lastScrolledGeneration = currentSuccess.generation
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(inPhoneSubScreen) {
        if (!inPhoneSubScreen) {
            focusRequester.requestFocus()
        }
    }

    val simDisplayNameMap = remember(activeSims) {
        activeSims.associate { it.subscriptionId to it.displayName }
    }

    if (inPhoneSubScreen) {
        SearchPhoneScreen(
            viewModel = viewModel,
            onNavigateBack = { inPhoneSubScreen = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = viewModel::updateQuery,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
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
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "清空搜索内容"
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.updateQuery("")
                            onNavigateBack()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
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
                    .imePadding()
            ) {
                SearchFilterBar(
                    filter = domainFilter,
                    simDisplayNameMap = simDisplayNameMap,
                    onPhoneClick = { inPhoneSubScreen = true },
                    onSimClick = {
                        refreshActiveSims()
                        showSimSheet = true
                    },
                    onTransportClick = { showTransportSheet = true },
                    onDateClick = { showDateSheet = true },
                    onUnreadClick = viewModel::toggleUnreadFilter,
                )

                vip.mystery0.pixel.text.ui.message.MirrorSyncBanner()

                SearchResultList(
                    uiState = uiState,
                    listState = listState,
                    onRetry = viewModel::retry,
                    onResultClick = onResultClick,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    if (showSimSheet) {
        val activeSubIds = activeSims.map { it.subscriptionId }.toSet()
        SimFilterSheet(
            currentSimSubIds = domainFilter.simSubIds,
            activeSims = activeSims,
            onDismissRequest = { showSimSheet = false },
            onApply = { selectedSubIds ->
                viewModel.setSimFilter(selectedSubIds, activeSubIds)
            },
            onClear = { viewModel.setSimFilter(emptySet(), activeSubIds) }
        )
    }

    if (showTransportSheet) {
        TransportFilterSheet(
            currentTransports = domainFilter.transports,
            onDismissRequest = { showTransportSheet = false },
            onApply = viewModel::setTransportFilter
        )
    }

    if (showDateSheet) {
        DateFilterSheet(
            currentDate = domainFilter.date,
            onDismissRequest = { showDateSheet = false },
            onDateSelected = viewModel::setDateFilter
        )
    }
}
