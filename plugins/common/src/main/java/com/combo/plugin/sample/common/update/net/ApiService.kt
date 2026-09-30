

package com.combo.plugin.sample.common.update.net

import com.combo.plugin.sample.common.update.model.PluginConfig
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {
    /**
     * 获取插件配置文件
     */
    @GET("geokori/plugins.json")
    suspend fun fetchPluginConfig(): PluginConfig

    /**
     * 下载文件
     */
    @Streaming
    @GET
    suspend fun downloadFile(@Url fileUrl: String): ResponseBody
}