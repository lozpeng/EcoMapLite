package org.cwcc.open.geokori.ui.material3.search.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.ui.material3.search.data.SearchRepository
import org.cwcc.open.geokori.ui.material3.search.domain.SearchSuggestion

class SearchViewModel(
    private val repository: SearchRepository
) : ViewModel() {

  // UI状态
  private val _uiState = MutableStateFlow(SearchUiState())
  val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

  // 搜索防抖
  private var searchJob: Job? = null
  private val debounceTime = 500L

  // 输入框文本
  private val _searchText = MutableStateFlow("")
  val searchText: StateFlow<String> = _searchText.asStateFlow()

  init {
    // 加载热门搜索
    loadHotSearches()
  }

  fun onSearchTextChanged(text: String) {
    _searchText.value = text

    // 取消之前的搜索任务
    searchJob?.cancel()

    if (text.isBlank()) {
      // 清空时显示热门搜索
      _uiState.update { it.copy(suggestions = emptyList(), showHotSearches = true) }
      loadHotSearches()
      return
    }

    // 防抖搜索
    searchJob = viewModelScope.launch {
      delay(debounceTime)
      _uiState.update { it.copy(isLoading = true) }
      try {
        val suggestions = repository.getSuggestions(text)
        _uiState.update {
          it.copy(
              suggestions = suggestions,
              isLoading = false,
              showHotSearches = false
          )
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
              error = e.message,
              isLoading = false
          )
        }
      }
    }
  }

  fun onSuggestionSelected(suggestion: String) {
    _searchText.value = suggestion
    _uiState.update {
      it.copy(
          suggestions = emptyList(),
          showHotSearches = false
      )
    }
    performSearch(suggestion)
  }

  fun performSearch(keyword: String) {
    if (keyword.isBlank()) return

    viewModelScope.launch {
      try {
        // 保存搜索历史
        repository.saveSearchHistory(keyword)
        // TODO: 执行实际搜索
        _uiState.update { it.copy(isSearching = true) }
        // 模拟搜索
        delay(1000)
        _uiState.update { it.copy(isSearching = false) }
      } catch (e: Exception) {
        _uiState.update { it.copy(error = e.message) }
      }
    }
  }

  private fun loadHotSearches() {
    viewModelScope.launch {
      try {
        val hotSearches = repository.getHotSearches()
        _uiState.update {
          it.copy(
              hotSearches = hotSearches,
              showHotSearches = true
          )
        }
      } catch (e: Exception) {
        // 忽略错误
      }
    }
  }

  fun clearSearch() {
    _searchText.value = ""
    _uiState.update {
      it.copy(
          suggestions = emptyList(),
          showHotSearches = true
      )
    }
    loadHotSearches()
  }

  data class SearchUiState(
      val suggestions: List<SearchSuggestion> = emptyList(),
      val hotSearches: List<SearchSuggestion> = emptyList(),
      val isLoading: Boolean = false,
      val isSearching: Boolean = false,
      val showHotSearches: Boolean = true,
      val error: String? = null
  )
}
