package org.cwcc.open.geokori.ui.material3.search.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "search_history")
data class SearchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val category: String = "general",  // 分类
    val searchCount: Int = 1,
    val lastSearchTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_suggestions")
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val weight: Int = 0,  // 权重，用于排序
    val category: String = "general"
)


@Dao
interface SearchDao {
  // 插入或更新搜索历史
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHistory(entity: SearchEntity)

  // 获取热门搜索（按搜索次数排序）
  @Query("SELECT * FROM search_history ORDER BY searchCount DESC LIMIT 10")
  fun getHotSearches(): Flow<List<SearchEntity>>

  // 本地搜索建议（模糊匹配）
  @Query("""
        SELECT keyword, weight, category 
        FROM search_suggestions 
        WHERE keyword LIKE '%' || :query || '%' 
        ORDER BY weight DESC 
        LIMIT :limit
    """)
  suspend fun getLocalSuggestions(query: String, limit: Int = 5): List<SuggestionEntity>

  // 从搜索历史中获取建议
  @Query("""
        SELECT DISTINCT keyword, searchCount as weight, category 
        FROM search_history 
        WHERE keyword LIKE '%' || :query || '%' 
        ORDER BY searchCount DESC 
        LIMIT :limit
    """)
  suspend fun getHistorySuggestions(query: String, limit: Int = 5): List<SuggestionEntity>

  // 更新搜索次数
  @Query("UPDATE search_history SET searchCount = searchCount + 1, lastSearchTime = :time WHERE keyword = :keyword")
  suspend fun updateSearchCount(keyword: String, time: Long)

  // 清除过期历史（保留最近30天）
  @Query("DELETE FROM search_history WHERE lastSearchTime < :cutoffTime")
  suspend fun clearExpiredHistory(cutoffTime: Long)

  // 批量插入建议（用于初始化或同步）
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSuggestions(suggestions: List<SuggestionEntity>)
}
