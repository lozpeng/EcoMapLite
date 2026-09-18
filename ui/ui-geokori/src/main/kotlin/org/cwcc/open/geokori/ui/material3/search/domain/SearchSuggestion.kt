package org.cwcc.open.geokori.ui.material3.search.domain

data class SearchSuggestion(
    val keyword: String,
    val weight: Int = 0,
    val category: String = "general",
    val source: Source = Source.LOCAL,
    val description: String? = null
) {
  enum class Source {
    LOCAL,      // 本地数据库
    HISTORY,    // 搜索历史
    REMOTE,     // 网络
    HOT         // 热门
  }
}
