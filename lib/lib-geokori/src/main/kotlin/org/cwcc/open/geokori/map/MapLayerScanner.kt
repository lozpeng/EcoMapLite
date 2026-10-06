package org.cwcc.open.geokori.map

import android.util.Log
import com.combo.core.runtime.PluginManager
import timber.log.Timber
import java.lang.reflect.Modifier

/**
 * 插件图层扫描器：找出插件中所有 [LibreMapLayer] 非抽象子类。
 *
 * 类名来源：ComboLite 类索引 [PluginManager.getClassIndexSnapshot]
 * （插件加载时已扫好，零反射、不遍历 dex，直接按 pluginId 过滤）。
 * 索引拿不到条目时返回空并打警告 —— 自动注册不可用，但手动
 * MapLayerManager.register 仍可兜底，不影响运行。
 *
 * 扫描在 IO 线程执行（见 MapLayerManager.onPluginSessionBound）。
 */
internal object MapLayerScanner {

    private const val TAG = "MapLayerScanner"

    /** 返回 (layerId, factory) 列表 */
    fun scan(pluginId: String, classLoader: ClassLoader): List<Pair<String, () -> LibreMapLayer>> {
        val classNames = runCatching {
            PluginManager.getClassIndexSnapshot()
                .filterValues { it == pluginId }
                .keys
                .toList()
        }.getOrElse { t ->
            Timber.w(t, "插件[$pluginId] 类索引读取失败")
            emptyList()
        }
        if (classNames.isEmpty()) {
            Timber.w("插件[$pluginId] 类索引无条目，自动注册跳过（可用手动 register 兜底）")
            return emptyList()
        }

        val result = mutableListOf<Pair<String, () -> LibreMapLayer>>()
        for (className in classNames) {
            val cls = try {
                Class.forName(className, false, classLoader)
            } catch (t: Throwable) {
                continue   // 依赖缺失的类直接跳过
            }
            if (cls.isInterface) continue
            if (Modifier.isAbstract(cls.modifiers)) continue
            if (!LibreMapLayer::class.java.isAssignableFrom(cls)) continue

            val ann = cls.getAnnotation(GeoKoriLayer::class.java)
            // 默认 id = 全限定类名：包名按插件隔离，跨插件重名从根上消除；
            // 如需短 id（如 "illegal-events"），用 @GeoKoriLayer 显式指定
            val layerId = ann?.id?.takeIf { it.isNotBlank() } ?: cls.name
            @Suppress("UNCHECKED_CAST")
            val factory = {
                cls.getDeclaredConstructor().newInstance() as LibreMapLayer
            }
            result.add(layerId to factory)
        }
        Timber.i("插件[$pluginId] 自动注册图层 ${result.size} 个（候选类 ${classNames.size}）")
        return result
    }
}