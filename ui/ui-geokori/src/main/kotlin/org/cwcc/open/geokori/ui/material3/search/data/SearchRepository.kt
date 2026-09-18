package org.cwcc.open.geokori.ui.material3.search.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import org.cwcc.open.geokori.ui.material3.search.domain.SearchSuggestion

class SearchRepository(
    private val dao: SearchDao,
    private val api: SearchApi
) {

  // 获取搜索建议（本地优先 + 网络补充）
  suspend fun getSuggestions(query: String, limit: Int = 5): List<SearchSuggestion> {
    if (query.isBlank()) return emptyList()

    val localSuggestions = mutableListOf<SearchSuggestion>()
    val historySuggestions = mutableListOf<SearchSuggestion>()

    // 1. 先从本地获取
    coroutineScope {
      val localDeferred = async {
        dao.getLocalSuggestions(query, limit).map { entity ->
          SearchSuggestion(
              keyword = entity.keyword,
              weight = entity.weight,
              category = entity.category,
              source = SearchSuggestion.Source.LOCAL
          )
        }
      }

      val historyDeferred = async {
        dao.getHistorySuggestions(query, limit).map { entity ->
          SearchSuggestion(
              keyword = entity.keyword,
              weight = entity.weight,
              category = entity.category,
              source = SearchSuggestion.Source.HISTORY
          )
        }
      }

      localSuggestions.addAll(localDeferred.await())
      historySuggestions.addAll(historyDeferred.await())
    }

    // 合并本地和历史建议（去重）
    val combined = (localSuggestions + historySuggestions)
        .distinctBy { it.keyword }
        .sortedByDescending { it.weight }
        .take(limit)

    // 2. 如果本地结果不够，从网络获取
    if (combined.size < limit) {
      try {
        val remoteSuggestions = fetchRemoteSuggestions(query, limit)
        // 缓存到本地
        cacheSuggestions(remoteSuggestions)
        // 合并结果
        return (combined + remoteSuggestions)
            .distinctBy { it.keyword }
            .take(limit)
      } catch (e: Exception) {
        // 网络失败，返回本地结果
        e.printStackTrace()
      }
    }

    return combined
  }

  // 网络获取建议
  private suspend fun fetchRemoteSuggestions(query: String, limit: Int): List<SearchSuggestion> {
    val response = api.getSuggestions(query, limit)
    return if (response.code == 200 && response.data != null) {
      response.data.suggestions.map { suggestion ->
        SearchSuggestion(
            keyword = suggestion.keyword,
            weight = suggestion.weight,
            category = suggestion.category,
            source = SearchSuggestion.Source.REMOTE,
            description = suggestion.description
        )
      }
    } else {
      emptyList()
    }
  }

  // 缓存网络建议到本地
  private suspend fun cacheSuggestions(suggestions: List<SearchSuggestion>) {
    val entities = suggestions.map { suggestion ->
      SuggestionEntity(
          keyword = suggestion.keyword,
          weight = suggestion.weight,
          category = suggestion.category
      )
    }
    dao.insertSuggestions(entities)
  }

  // 保存搜索记录
  suspend fun saveSearchHistory(keyword: String) {
    if (keyword.isBlank()) return

    val entity = SearchEntity(
        keyword = keyword,
        lastSearchTime = System.currentTimeMillis()
    )
    dao.insertHistory(entity)
  }

  // 获取热门搜索（本地+网络）
  suspend fun getHotSearches(limit: Int = 10): List<SearchSuggestion> {
    val localHot = dao.getHotSearches()
        .firstOrNull() // Flow转普通列表
        ?.map { entity ->
          SearchSuggestion(
              keyword = entity.keyword,
              weight = entity.searchCount,
              category = entity.category,
              source = SearchSuggestion.Source.HOT
          )
        } ?: emptyList()

    // 如果本地热门不够，从网络获取
    if (localHot.size < limit) {
      try {
        val response = api.getHotSearches(limit)
        if (response.code == 200 && response.data != null) {
          return response.data.suggestions.map { suggestion ->
            SearchSuggestion(
                keyword = suggestion.keyword,
                weight = suggestion.weight,
                category = suggestion.category,
                source = SearchSuggestion.Source.REMOTE,
                description = suggestion.description
            )
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }

    return localHot
  }

  // 清除过期数据
  suspend fun cleanExpiredData() {
    val cutoffTime = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000 // 30天
    dao.clearExpiredHistory(cutoffTime)
  }
}
