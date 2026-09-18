package org.cwcc.open.geokori.ui.material3.search.data

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

import retrofit2.Retrofit

data class SearchResponse(
    val code: Int,
    val message: String,
    val data: Data?
) {
  data class Data(
      val suggestions: List<Suggestion>,
      val total: Int
  )

  data class Suggestion(
      @SerializedName("keyword")
      val keyword: String,
      @SerializedName("weight")
      val weight: Int = 0,
      @SerializedName("category")
      val category: String = "general",
      @SerializedName("description")
      val description: String? = null
  )
}
interface SearchApi {
  @GET("api/search/suggest")
  suspend fun getSuggestions(
      @Query("query") query: String,
      @Query("limit") limit: Int = 10
  ): SearchResponse

  @GET("api/search/hot")
  suspend fun getHotSearches(
      @Query("limit") limit: Int = 10
  ): SearchResponse
}

object RetrofitClient {
  private const val BASE_URL = "https://your-api.com/" // 替换为实际API地址

  val instance: Retrofit by lazy {
    Retrofit.Builder()
        .baseUrl(BASE_URL)
        //.addConverterFactory(GsonConverterFactory.create())
        .build()
  }

  val searchApi: SearchApi by lazy {
    instance.create(SearchApi::class.java)
  }
}
