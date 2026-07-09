package vip.mystery0.pixel.text.ui.message.search

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel

@Composable
fun SearchResultList(
    uiState: SearchUiState,
    query: String,
    onResultClick: (MessageModel) -> Unit
) {
    when (uiState) {
        is SearchUiState.Idle -> {
            SearchPlaceholder(
                imageRes = R.drawable.illustration_search_idle,
                text = "输入关键词，或直接按联系人筛选短信"
            )
        }

        is SearchUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        is SearchUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "搜索失败：${uiState.message}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        is SearchUiState.Success -> {
            if (uiState.results.isEmpty()) {
                SearchPlaceholder(
                    imageRes = R.drawable.illustration_search_empty,
                    text = "没有找到匹配的短信"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(uiState.results, key = { it.stableKey }) { message ->
                        SearchResultItem(
                            message = message,
                            query = query,
                            onClick = { onResultClick(message) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPlaceholder(
    @DrawableRes imageRes: Int,
    text: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .padding(bottom = 20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
