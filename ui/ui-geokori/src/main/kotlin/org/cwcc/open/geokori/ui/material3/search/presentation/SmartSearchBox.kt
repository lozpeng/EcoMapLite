package org.cwcc.open.geokori.ui.material3.search.presentation

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.cwcc.open.geokori.ui.material3.search.data.RetrofitClient
import org.cwcc.open.geokori.ui.material3.search.data.SearchDatabase
import org.cwcc.open.geokori.ui.material3.search.data.SearchRepository
import org.cwcc.open.geokori.ui.material3.search.domain.SearchSuggestion
//
//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//fun SmartSearchBox(
//    viewModel: SearchViewModel = viewModel(
//        factory = SearchViewModelFactory(provideRepository())
//    ),
//    placeholder: String = "搜索商品、文章、用户...",
//    modifier: Modifier = Modifier,
//    onSearch: ((String) -> Unit)? = null
//) {
//  val uiState by viewModel.uiState.collectAsState()
//  val searchText by viewModel.searchText.collectAsState()
//  var isFocused by remember { mutableStateOf(false) }
//  val listState = rememberLazyListState()
//
//  Column(modifier = modifier.fillMaxWidth()) {
//    // 搜索输入框
//    SearchInputField(
//        text = searchText,
//        onTextChange = viewModel::onSearchTextChanged,
//        onClear = viewModel::clearSearch,
//        onFocusChange = { isFocused = it },
//        placeholder = placeholder,
//        isLoading = uiState.isLoading,
//        onSearch = { viewModel.performSearch(searchText) }
//    )
//
//    // 搜索建议/历史/热门
//    if (isFocused || searchText.isNotEmpty()) {
//      SuggestionsDropdown(
//          uiState = uiState,
//          searchText = searchText,
//          onSuggestionClick = { suggestion ->
//            viewModel.onSuggestionSelected(suggestion)
//            onSearch?.invoke(suggestion)
//          },
//          listState = listState
//      )
//    }
//  }
//}
//
//@Composable
//private fun SearchInputField(
//    text: String,
//    onTextChange: (String) -> Unit,
//    onClear: () -> Unit,
//    onFocusChange: (Boolean) -> Unit,
//    placeholder: String,
//    isLoading: Boolean,
//    onSearch: () -> Unit
//) {
//  OutlinedTextField(
//      value = text,
//      onValueChange = onTextChange,
//      onFocusChange = onFocusChange,
//      modifier = Modifier
//          .fillMaxWidth()
//          .semantics {
//            role = Role.EditText
//            setText(placeholder)
//          },
//      placeholder = { Text(placeholder) },
//      leadingIcon = {
//        Icon(
//            Icons.Default.Search,
//            contentDescription = "搜索",
//            tint = MaterialTheme.colorScheme.primary
//        )
//      },
//      trailingIcon = {
//        when {
//          isLoading -> {
//            CircularProgressIndicator(
//                modifier = Modifier.size(20.dp),
//                strokeWidth = 2.dp
//            )
//          }
//          text.isNotEmpty() -> {
//            IconButton(onClick = onClear) {
//              Icon(Icons.Default.Close, contentDescription = "清空")
//            }
//          }
//        }
//      },
//      singleLine = true,
//      keyboardOptions = KeyboardOptions(
//          keyboardType = KeyboardType.Text,
//          imeAction = ImeAction.Search
//      ),
//      keyboardActions = KeyboardActions(
//          onSearch = { onSearch() }
//      ),
//      colors = OutlinedTextFieldDefaults.colors(
//          focusedBorderColor = MaterialTheme.colorScheme.primary,
//          unfocusedBorderColor = MaterialTheme.colorScheme.outline,
//          focusedContainerColor = Color.White,
//          unfocusedContainerColor = Color.White
//      ),
//      shape = RoundedCornerShape(12.dp)
//  )
//}
//
//@Composable
//private fun SuggestionsDropdown(
//    uiState: SearchViewModel.SearchUiState,
//    searchText: String,
//    onSuggestionClick: (String) -> Unit,
//    listState: androidx.compose.foundation.lazy.LazyListState
//) {
//  if (uiState.suggestions.isEmpty() && !uiState.showHotSearches) {
//    return
//  }
//
//  Card(
//      modifier = Modifier
//          .fillMaxWidth()
//          .padding(top = 4.dp)
//          .shadow(4.dp, RoundedCornerShape(8.dp)),
//      shape = RoundedCornerShape(8.dp)
//  ) {
//    LazyColumn(
//        state = listState,
//        modifier = Modifier
//            .fillMaxWidth()
//            .heightIn(max = 300.dp)
//    ) {
//      // 显示热们搜索
//      if (uiState.showHotSearches && uiState.hotSearches.isNotEmpty()) {
//        item {
//          Text(
//              text = "🔥 热门搜索",
//              style = MaterialTheme.typography.labelMedium,
//              color = MaterialTheme.colorScheme.onSurfaceVariant,
//              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
//          )
//        }
//
//        items(uiState.hotSearches) { suggestion ->
//          SuggestionItem(
//              suggestion = suggestion,
//              icon = Icons.Default.TrendingUp,
//              onClick = { onSuggestionClick(suggestion.keyword) }
//          )
//        }
//      }
//
//      // 显示搜索建议
//      if (!uiState.showHotSearches && uiState.suggestions.isNotEmpty()) {
//        items(uiState.suggestions) { suggestion ->
//          SuggestionItem(
//              suggestion = suggestion,
//              icon = when (suggestion.source) {
//                SearchSuggestion.Source.LOCAL -> Icons.Default.Storage
//                SearchSuggestion.Source.HISTORY -> Icons.Default.History
//                SearchSuggestion.Source.REMOTE -> Icons.Default.Cloud
//                SearchSuggestion.Source.HOT -> Icons.Default.TrendingUp
//              },
//              onClick = { onSuggestionClick(suggestion.keyword) },
//              highlightQuery = searchText
//          )
//        }
//      }
//
//      // 加载状态
//      if (uiState.isLoading && !uiState.showHotSearches) {
//        item {
//          Box(
//              modifier = Modifier
//                  .fillMaxWidth()
//                  .padding(16.dp),
//              contentAlignment = Alignment.Center
//          ) {
//            CircularProgressIndicator(
//                modifier = Modifier.size(24.dp),
//                strokeWidth = 2.dp
//            )
//          }
//        }
//      }
//
//      // 错误状态
//      if (uiState.error != null) {
//        item {
//          Text(
//              text = "加载失败: ${uiState.error}",
//              color = MaterialTheme.colorScheme.error,
//              modifier = Modifier.padding(16.dp)
//          )
//        }
//      }
//    }
//  }
//}
//
//@Composable
//private fun SuggestionItem(
//    suggestion: SearchSuggestion,
//    icon: androidx.compose.ui.graphics.vector.ImageVector,
//    onClick: () -> Unit,
//    highlightQuery: String = ""
//) {
//  Row(
//      modifier = Modifier
//          .fillMaxWidth()
//          .clickable { onClick() }
//          .padding(horizontal = 16.dp, vertical = 12.dp),
//      verticalAlignment = Alignment.CenterVertically
//  ) {
//    Icon(
//        icon,
//        contentDescription = null,
//        tint = MaterialTheme.colorScheme.primary,
//        modifier = Modifier.size(20.dp)
//    )
//    Spacer(modifier = Modifier.width(12.dp))
//
//    Column {
//      Text(
//          text = if (highlightQuery.isNotEmpty()) {
//            highlightMatch(suggestion.keyword, highlightQuery)
//          } else {
//            suggestion.keyword
//          },
//          style = MaterialTheme.typography.bodyMedium,
//          color = MaterialTheme.colorScheme.onSurfaceVariant
//      )
//
//      // 显示来源标签
//      if (suggestion.description != null) {
//        Text(
//            text = suggestion.description,
//            style = MaterialTheme.typography.labelSmall,
//            color = MaterialTheme.colorScheme.onSurfaceVariant
//        )
//      }
//    }
//
//    Spacer(modifier = Modifier.weight(1f))
//
//    // 来源标签
//    Text(
//        text = when (suggestion.source) {
//          SearchSuggestion.Source.LOCAL -> "本地"
//          SearchSuggestion.Source.HISTORY -> "历史"
//          SearchSuggestion.Source.REMOTE -> "网络"
//          SearchSuggestion.Source.HOT -> "热门"
//        },
//        style = MaterialTheme.typography.labelSmall,
//        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
//        modifier = Modifier.padding(start = 8.dp)
//    )
//  }
//
//  Divider(
//      modifier = Modifier.padding(horizontal = 16.dp)
//  )
//}
//
//// 高亮匹配文本
//@Composable
//private fun highlightMatch(text: String, query: String): AnnotatedString {
//  val builder = AnnotatedString.Builder()
//  val lowerText = text.lowercase()
//  val lowerQuery = query.lowercase()
//
//  var startIndex = 0
//  while (startIndex < text.length) {
//    val index = lowerText.indexOf(lowerQuery, startIndex)
//    if (index == -1) {
//      builder.append(text.substring(startIndex))
//      break
//    }
//    if (index > startIndex) {
//      builder.append(text.substring(startIndex, index))
//    }
//    builder.pushStyle(
//        SpanStyle(
//            color = MaterialTheme.colorScheme.primary,
//            fontWeight = FontWeight.Bold
//        )
//    )
//    builder.append(text.substring(index, index + query.length))
//    builder.pop()
//    startIndex = index + query.length
//  }
//  builder.toAnnotatedString()
//}
//
//
//class SearchViewModelFactory(
//    private val repository: SearchRepository
//) : ViewModelProvider.Factory {
//  override fun <T : ViewModel> create(modelClass: Class<T>): T {
//    if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
//      @Suppress("UNCHECKED_CAST")
//      return SearchViewModel(repository) as T
//    }
//    throw IllegalArgumentException("Unknown ViewModel class")
//  }
//}
//
//// 提供Repository实例
//fun provideRepository(
//    context: Context? = null
//): SearchRepository {
//  // 这里需要实际的Context，在Compose中可以通过LocalContext获取
//  val ctx = context ?: throw IllegalStateException("Context required")
//  val db = SearchDatabase.getInstance(ctx)
//  val api = RetrofitClient.searchApi
//  return SearchRepository(db.searchDao(), api)
//}
