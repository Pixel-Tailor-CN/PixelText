package vip.mystery0.pixel.text.ui.message.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel

@Composable
fun SearchResultList(
    uiState: SearchUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onResultClick: (MessageModel) -> Unit,
    selectionState: SearchSelectionState,
    onToggleSelection: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            is SearchUiState.Idle -> {
                AppEmptyState(
                    imageRes = R.drawable.illustration_search_idle,
                    text = "搜索短信和彩信",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is SearchUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            is SearchUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onRetry) {
                            Text("重试")
                        }
                    }
                }
            }

            is SearchUiState.Success -> {
                if (uiState.results.isEmpty()) {
                    val emptyText = if (uiState.incomplete) {
                        "当前未找到匹配消息，结果可能尚未完整"
                    } else {
                        "没有找到符合条件的消息"
                    }
                    AppEmptyState(
                        imageRes = R.drawable.illustration_search_empty,
                        text = emptyText,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (uiState.incomplete) {
                            item(key = "incomplete_header_indicator") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "部分消息仍在准备，结果可能不完整",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(uiState.results, key = { it.stableKey }) { message ->
                            SearchResultItem(
                                message = message,
                                query = uiState.actualQuery,
                                selectionMode = selectionState.selectedIds.isNotEmpty(),
                                selected = message.id in selectionState.selectedIds,
                                enabled = !selectionState.isDeleting,
                                onLongClick = { onToggleSelection(message.id) },
                                onClick = {
                                    if (selectionState.selectedIds.isNotEmpty()) {
                                        onToggleSelection(message.id)
                                    } else {
                                        onResultClick(message)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
