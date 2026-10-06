package org.cwcc.open.geokori.map

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.features.user.FeatureCursor
import mil.nga.geopackage.features.user.FeatureRow
import mil.nga.sf.Geometry
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream

/**
 * GeoPackage 通用矢量图层（框架级 · 多实例版）。
 *
 * ★ 不使用 @GeoKoriLayer 注解 —— 按"每个 gpkg 文件一个实例"运行时注册：
 *   - 插件明确已知文件：[GeoPackageLayers.open] 懒注册即开；
 *   - 用户经文件选择器导入：[GeoPackageLayers.importAndOpen]（硬链接优先，零额外存储）；
 *   - filesDir 兜底扫描：[GeoPackageLayers.sync]（文件来源不明时可选）。
 *
 * 按几何类型自动组装渲染层：点→Circle、线→Line、面→Fill，共用一个 source。
 *
 * @param instanceId  实例 id（注册时与 MapLayerManager 的 id 一致，文件名派生）
 * @param gpkgFile    本地 gpkg 文件
 * @param tableFilter 表名过滤，null = 全部要素表
 */
class GeoPackageLayer(
    private val instanceId: String,
    private val gpkgFile: File,
    private val tableFilter: ((String) -> Boolean)? = null,
) : BaseBizeLibreLayer() {

    companion object {
        /** 实例 id 前缀（注册 id / 业务 action id 均用它拼接文件名） */
        const val ID_PREFIX = "gpkg-"

        // ---- 样式配置（全部实例共享；如需每文件定制，移到构造参数） ----
        private const val POINT_COLOR = "#1E88E5"
        private const val POINT_RADIUS = 6f
        private const val POINT_STROKE_COLOR = "#FFFFFF"
        private const val POINT_STROKE_WIDTH = 1.5f

        private const val LINE_COLOR = "#FB8C00"
        private const val LINE_WIDTH = 2.5f

        private const val FILL_COLOR = "#43A047"
        private const val FILL_OPACITY = 0.35f
        private const val FILL_OUTLINE_COLOR = "#1B5E20"

        private const val LABEL_FIELD = "name"
        private const val LABEL_MIN_ZOOM = 12f
    }

    // ---- 每实例唯一的资源 id（多图层共存不互踩） ----
    private val sourceId = "__gpkg_${instanceId}_src"
    private val fillLayerId = "__gpkg_${instanceId}_fill"
    private val lineLayerId = "__gpkg_${instanceId}_line"
    private val circleLayerId = "__gpkg_${instanceId}_circle"
    private val labelLayerId = "__gpkg_${instanceId}_label"

    override val displayName: String = gpkgFile.nameWithoutExtension

    override val cacheDataFile: String = "gpkg_${instanceId}_cache.geojson"
    override val cacheMetaFile: String = "gpkg_${instanceId}_cache.meta"

    /** gpkg 本身在本地，禁止派生 GeoJSON 缓存，避免"源文件 + 缓存"双份存储。
     *  依赖基类 [BaseBizeLibreLayer.enableLocalCache] 开关（默认 true，此处关闭） */
    override val enableLocalCache: Boolean = false

    // =========================================================================================
    // 数据读取：gpkg → GeoJSON（IO 线程，基类 launchLoad 保证）
    // =========================================================================================

    override fun fetchRemoteGeoJson(): String {
        val gpkg = GeoPackageFactory.openExternal(gpkgFile)
        try {
            val features = mutableListOf<Feature>()
            var hasLabelField = false

            for (table in gpkg.featureTables) {
                if (tableFilter != null && tableFilter?.invoke(table) != true) continue

                val dao = gpkg.getFeatureDao(table)
                val cursor: FeatureCursor = dao.queryForAll()
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val row: FeatureRow = c.row
                        val geomData = row.geometry ?: continue
                        val sfGeom = geomData.geometry ?: continue
                        val mlGeom = toMapLibreGeometry(sfGeom) ?: continue
                        features.add(Feature.fromGeometry(mlGeom, rowProps(row)))
                    }
                }
                if (LABEL_FIELD.isNotBlank() && dao.columnNames.contains(LABEL_FIELD)) {
                    hasLabelField = true
                }
            }

            hasLabelColumn = hasLabelField
            return FeatureCollection.fromFeatures(features).toJson()
        } finally {
            gpkg.close()
        }
    }

    private fun rowProps(row: FeatureRow): JsonObject {
        val o = JsonObject()
        val names = row.columnNames
        for (i in names.indices) {
            val name = names[i]
            val value = row.getValue(i) ?: continue
            if (value is mil.nga.geopackage.geom.GeoPackageGeometryData) continue
            when (value) {
                is Number -> o.addProperty(name, value)
                is Boolean -> o.addProperty(name, value)
                else -> o.addProperty(name, value.toString())
            }
        }
        o.addProperty("__table", row.table.tableName)
        return o
    }

    // =========================================================================================
    // 几何转换：mil.nga.sf → org.maplibre.geojson（全类型递归）
    // =========================================================================================

    private fun toMapLibreGeometry(g: Geometry?): org.maplibre.geojson.Geometry? =
        when (g) {
            is mil.nga.sf.Point ->
                Point.fromLngLat(g.x, g.y)

            is mil.nga.sf.LineString ->
                org.maplibre.geojson.LineString.fromLngLats(g.points.map { Point.fromLngLat(it.x, it.y) })

            is mil.nga.sf.Polygon ->
                org.maplibre.geojson.Polygon.fromLngLats(
                    g.rings.map { ring -> ring.points.map { Point.fromLngLat(it.x, it.y) } },
                )

            is mil.nga.sf.MultiPoint ->
                org.maplibre.geojson.MultiPoint.fromLngLats(g.points.map { Point.fromLngLat(it.x, it.y) })

            is mil.nga.sf.MultiLineString ->
                org.maplibre.geojson.MultiLineString.fromLngLats(
                    g.lineStrings.map { ls -> ls.points.map { Point.fromLngLat(it.x, it.y) } },
                )

            is mil.nga.sf.MultiPolygon ->
                org.maplibre.geojson.MultiPolygon.fromLngLats(
                    g.polygons.map { poly ->
                        poly.rings.map { ring -> ring.points.map { Point.fromLngLat(it.x, it.y) } }
                    },
                )

            // mil.nga.sf 6.x 中 GeometryCollection 已泛型化，需声明星投影
            is mil.nga.sf.GeometryCollection<*> ->
                org.maplibre.geojson.GeometryCollection.fromGeometries(
                    g.geometries
                        .filterIsInstance<Geometry>()
                        .mapNotNull { toMapLibreGeometry(it) },
                )

            else -> null
        }

    private var hasLabelColumn = LABEL_FIELD.isNotBlank()

    // =========================================================================================
    // 图层组装：按几何类型三（四）层共用一源
    // =========================================================================================

    override fun onCollectionLoaded(collection: FeatureCollection) {
        removeMapObjects()
        if (collection.features().isNullOrEmpty()) return

        val session = currentSession() ?: return
        session.addSource(
            GeoJsonSource(
                sourceId, collection,
                GeoJsonOptions().withCluster(false).withTolerance(0.5f).withBuffer(512),
            ),
        )

        // 面（最底）
        session.addLayer(
            fillLayer(fillLayerId, sourceId, FILL_COLOR.toColorInt(), FILL_OUTLINE_COLOR.toColorInt())
                .apply { setProperties(PropertyFactory.fillOpacity(FILL_OPACITY)) },
        )
        // 线
        session.addLayer(
            lineLayer(lineLayerId, sourceId, LINE_COLOR.toColorInt(), LINE_WIDTH)
                .apply { setProperties(PropertyFactory.lineOpacity(0.9f)) },
        )
        // 点
        session.addLayer(
            circleLayer(circleLayerId, sourceId)
                .apply {
                    setProperties(
                        PropertyFactory.circleRadius(POINT_RADIUS),
                        PropertyFactory.circleColor(Expression.color(POINT_COLOR.toColorInt())),
                        PropertyFactory.circleStrokeColor(Expression.color(POINT_STROKE_COLOR.toColorInt())),
                        PropertyFactory.circleStrokeWidth(POINT_STROKE_WIDTH),
                        PropertyFactory.circleOpacity(0.9f),
                    )
                },
        )
        // 注记（可选）
        if (hasLabelColumn) {
            session.addLayer(
                symbolLayer(labelLayerId, sourceId, minZoom = LABEL_MIN_ZOOM)
                    .apply {
                        setProperties(
                            PropertyFactory.textField(Expression.get(LABEL_FIELD)),
                            PropertyFactory.textSize(12f),
                            PropertyFactory.textColor(Expression.color("#212121".toColorInt())),
                            PropertyFactory.textHaloColor(Expression.color("#FFFFFF".toColorInt())),
                            PropertyFactory.textHaloWidth(1f),
                            PropertyFactory.textOffset(arrayOf(0f, 1f)),
                            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                            PropertyFactory.textAllowOverlap(false),
                        )
                    },
            )
        }

        onAlphaChanged(mAlpha)
        applyLayerVisibility()
    }

    override fun onClearMapObjects() = removeMapObjects()

    private fun removeMapObjects() {
        currentSession()?.removeLayer(fillLayerId)
        currentSession()?.removeLayer(lineLayerId)
        currentSession()?.removeLayer(circleLayerId)
        currentSession()?.removeLayer(labelLayerId)
        currentSession()?.removeSource(sourceId)
    }

    // =========================================================================================
    // 透明度 / 显隐
    // =========================================================================================

    override fun onAlphaChanged(alpha: Float) {
        val s = map?.style ?: return
        s.getLayer(fillLayerId)?.setProperties(PropertyFactory.fillOpacity(FILL_OPACITY * alpha))
        s.getLayer(lineLayerId)?.setProperties(PropertyFactory.lineOpacity(0.9f * alpha))
        s.getLayer(circleLayerId)?.setProperties(PropertyFactory.circleOpacity(0.9f * alpha))
        s.getLayer(labelLayerId)?.setProperties(PropertyFactory.textOpacity(alpha))
    }

    override fun applyLayerVisibility() {
        val s = map?.style ?: return
        val v = if (mVisible) Property.VISIBLE else Property.NONE
        listOf(fillLayerId, lineLayerId, circleLayerId, labelLayerId)
            .forEach { id -> s.getLayer(id)?.setProperties(PropertyFactory.visibility(v)) }
    }

    // =========================================================================================
    // 生命周期 / 点击
    // =========================================================================================

    override fun onMapReady(map: MapLibreMap) {
        registerListeners(map)
        mountSheetHost()
        if (isLoaded) applyLayerVisibility()
    }

    override fun onDetach() {
        super.onDetach()
        GpkgAttrSheet.dismiss()
        unmountSheetHost()
    }

    private fun registerListeners(map: MapLibreMap) {
        trackMapListener(
            MapLibreMap.OnMapClickListener { latLng -> onMapClicked(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        val m = map ?: return false
        val screen = m.projection.toScreenLocation(latLng)
        val feature = m.queryRenderedFeatures(screen, circleLayerId, fillLayerId, lineLayerId)
            .firstOrNull() ?: return false
        GpkgAttrSheet.show(feature)
        return true
    }

    // =============================================================================================
    // 弹窗宿主
    // =============================================================================================

    private var sheetHostView: ComposeView? = null

    private fun mountSheetHost() {
        if (sheetHostView != null) return
        val parent = MapRuntime.currentMapView ?: return
        val view = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { GpkgAttrSheet.Host() }
        }
        parent.addView(
            view,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sheetHostView = view
    }

    private fun unmountSheetHost() {
        sheetHostView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        sheetHostView = null
    }
}

// =================================================================================================
// 多实例注册器（框架级；注册归属打开它的插件 —— pluginId 经 currentPluginId 从 Session 取）
// =================================================================================================

object GeoPackageLayers {

    private const val MANIFEST = "gpkg_manifest.json"

    /** 已导入文件信息（插件构建动态按钮用） */
    data class Imported(val label: String, val fileName: String, val layerId: String)

    /** 清单条目：文件名 + 属主插件（按钮归属插件；注册仍全局单实例） */
    private data class Entry(val file: String, val plugin: String)

    /** 清单：filesDir/gpkg_manifest.json（JSONArray，元素为 {file, plugin}） */
    private fun manifest(context: Context): List<Entry> {
        val f = File(context.filesDir, MANIFEST)
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = org.json.JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Entry(o.optString("file"), o.optString("plugin"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 供插件构建按钮：按属主插件过滤清单 × 磁盘存在性 → Imported 列表。
     * 文件被删则自动消失；其他插件导入的文件不出现在本插件面板。
     */
    fun imported(context: Context, pluginId: String): List<Imported> =
        manifest(context)
            .filter { it.plugin == pluginId }
            .mapNotNull { e ->
                val f = File(context.filesDir, e.file)
                if (f.exists()) Imported(f.nameWithoutExtension, e.file, idOf(f)) else null
            }

    /** 文件名 → 实例 id（非法字符替换为 _；同名不同大小文件以长度散列区分） */
    fun idOf(file: File): String =
        GeoPackageLayer.ID_PREFIX +
                file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_") +
                "_${file.length() % 100000}"

    /**
     * 打开指定文件：未注册则补注册（挂在调用方插件名下）再 toggle。
     * 适用于插件预置/明确已知文件 —— 此时无需 [sync]。
     *
     * @param pluginId 调用方插件 id（Composable 内用 [currentPluginId] 获取，勿硬编码）
     */
    fun open(context: Context, file: File, pluginId: String) {
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在：${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        registerIfAbsent(file, pluginId)
        MapLayerManager.toggle(idOf(file), context)
    }

    /** 与清单同步：文件按清单记录的属主插件注册。启动时调用一次 */
    fun sync(context: Context) {
        manifest(context)
            .map { File(context.filesDir, it.file) to it.plugin }
            .filter { it.first.exists() }
            .forEach { (file, owner) -> registerIfAbsent(file, owner) }
    }

    /**
     * 导入并打开：SAF Uri → filesDir（硬链接优先，零额外存储；失败回退拷贝）
     * → 注册 → toggle 自动加载 → 清单记录（含属主插件，按钮归属调用方插件面板）。
     *
     * @param pluginId 调用方插件 id（清单归属依据；注册本身仍是全局单实例）
     */
    suspend fun importAndOpen(context: Context, uri: Uri, pluginId: String) =
        withContext(Dispatchers.IO) {
            val src = resolveRealFile(context, uri)
            val destName = (src?.name ?: queryDisplayName(context, uri) ?: "import.gpkg")
                .let { if (it.endsWith(".gpkg", true)) it else "$it.gpkg" }
            val file = when {
                src != null -> linkOrCopy(src, uniqueDest(context.filesDir, destName))
                else -> copyToFilesDir(context, uri, destName)
            }
            withContext(Dispatchers.Main) {
                registerIfAbsent(file, pluginId)   // 注册挂调用方插件（其 Session 已加载，attach 成功）
                MapLayerManager.toggle(idOf(file), context)
                appendManifest(context, file.name, pluginId)
            }
        }

    // ---- 内部 ----

    /** 注册（幂等）：挂在指定插件名下，attach 走该插件的 MapSession */
    fun registerIfAbsent(file: File, pluginId: String) {
        val id = idOf(file)
        if (!MapLayerManager.isRegistered(id)) {
            // ★ 对齐 MapLayerManager 的手动注册 API（注解扫描同源注册表）
            MapLayerManager.register(pluginId, id) { GeoPackageLayer(id, file) }
        }
    }

    /** 清单追加（按 file+plugin 去重持久化） */
    private fun appendManifest(context: Context, fileName: String, pluginId: String) {
        val list = (manifest(context) + Entry(fileName, pluginId)).distinct()
        writeManifest(context, list)
    }

    /** 清单除名 */
    private fun removeFromManifest(context: Context, fileName: String, pluginId: String) {
        val list = manifest(context) - Entry(fileName, pluginId)
        writeManifest(context, list)
    }

    private fun writeManifest(context: Context, list: List<Entry>) {
        val a = org.json.JSONArray()
        list.forEach { e ->
            a.put(org.json.JSONObject().put("file", e.file).put("plugin", e.plugin))
        }
        File(context.filesDir, MANIFEST).writeText(a.toString())
    }

    /**
     * 移除已导入文件：反注册（若存活先关闭）+ 清单除名。
     * 文件本体保留在 filesDir（数据不随按钮误删）。
     *
     * ★ 依赖 MapLayerManager.unregister(id) —— 若框架尚无此 API 需补：
     *   从 registrations 移除并（若存活）执行 detach。
     */
    fun remove(context: Context, fileName: String, pluginId: String) {
        val id = idOf(File(context.filesDir, fileName))
        MapLayerManager.unregister(id)
        removeFromManifest(context, fileName, pluginId)
    }

    /**
     * SAF Uri → 真实文件（覆盖常见 provider；API 29+ 作用域存储下
     * 外置存储 raw path 可能不可读，canRead() 拦截后回退拷贝分支）
     */
    private fun resolveRealFile(context: Context, uri: Uri): File? = runCatching {
        when {
            uri.scheme == "file" -> File(requireNotNull(uri.path))
            uri.authority == "com.android.externalstorage.documents" -> {
                val docId = DocumentsContract.getDocumentId(uri)   // "primary:Documents/roads.gpkg"
                val (volume, path) = docId.split(":", limit = 2)
                val base = if (volume == "primary")
                    Environment.getExternalStorageDirectory().path
                else "/storage/$volume"
                File(base, path)
            }
            uri.authority == "com.android.providers.downloads.documents" -> {
                val id = DocumentsContract.getDocumentId(uri)      // 可能 "raw:/sdcard/..."
                if (id.startsWith("raw:")) File(id.removePrefix("raw:")) else null
            }
            else -> null
        }?.takeIf { it.exists() && it.canRead() }
    }.getOrNull()

    /** 硬链接优先（同盘零成本）；跨盘/权限不足回退拷贝 */
    private fun linkOrCopy(src: File, dest: File): File {
        try {
            android.system.Os.link(src.absolutePath, dest.absolutePath)
            return dest
        } catch (e: Exception) {
            // EXDEV（跨盘）/ EPERM（权限）→ 回退
        }
        src.copyTo(dest, overwrite = false)
        return dest
    }

    private fun uniqueDest(dir: File, name: String): File {
        val stem = name.removeSuffix(".gpkg")
        var dest = File(dir, name)
        var i = 1
        while (dest.exists()) { dest = File(dir, "${stem}_$i.gpkg"); i++ }
        return dest
    }

    private fun copyToFilesDir(context: Context, uri: Uri, destName: String): File {
        val dest = uniqueDest(context.filesDir, destName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
}

// =================================================================================================
// 当前插件 id 助手
// =================================================================================================

/**
 * 当前 Composable 所属插件的 id。
 *
 * 数据源：入口类 Content() 的 CompositionLocalProvider(LocalMapSession provides session)，
 * session.pluginId 即框架分配的插件 id —— 单一事实源，插件无需另存常量。
 *
 * 仅限 Composable 内使用；Composition 外（Service/纯类）请改用
 * MapRuntime.pluginSession(knownId)?.pluginId 或持有的 session 引用直取。
 */
val currentPluginId: String
    @Composable
    get() = LocalMapSession.current?.pluginId
        ?: error("LocalMapSession 未提供：该 Composable 不在插件 Content() 的 Provider 内")

// =================================================================================================
// 一键式导入状态（框架侧消化 Composition 桥接，调用方无感 pluginId）
// =================================================================================================

/** gpkg 导入控制状态：按钮列表 + 选择器入口 + 打开/移除动作 */
class GpkgImportState(
    /** 本插件已导入文件（驱动动态按钮） */
    val imported: List<GeoPackageLayers.Imported>,
    /** SpeedDial"打开"按钮接这里 */
    val launchPicker: () -> Unit,
    /** GPKG 按钮点击：传 action.payload（文件名） */
    val open: (payload: String) -> Unit,
    /** 移除按钮：反注册 + 清单除名（文件本体保留）；完成后自动刷新 imported */
    val remove: (payload: String) -> Unit,
)

/**
 * 记住 gpkg 导入状态：启动恢复清单 + 选择器导入 + 懒注册打开。
 * pluginId 经 [currentPluginId] 内部获取，调用方无感；
 * 需要 activity-compose 依赖（rememberLauncherForActivityResult）。
 */
@Composable
fun rememberGpkgImport(): GpkgImportState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pluginId = currentPluginId

    var imported by remember { mutableStateOf(GeoPackageLayers.imported(context, pluginId)) }

    LaunchedEffect(Unit) {
        GeoPackageLayers.sync(context)
        imported = GeoPackageLayers.imported(context, pluginId)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { GeoPackageLayers.importAndOpen(context, uri, pluginId) }
                .onSuccess { imported = GeoPackageLayers.imported(context, pluginId) }
                .onFailure {
                    Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    return GpkgImportState(
        imported = imported,
        launchPicker = { picker.launch(arrayOf("*/*")) },
        open = { payload ->
            GeoPackageLayers.open(context, File(context.filesDir, payload), pluginId)
        },
        remove = { payload ->
            GeoPackageLayers.remove(context, payload, pluginId)
            imported = GeoPackageLayers.imported(context, pluginId)
        },
    )
}

// =================================================================================================
// 通用属性弹窗：列出要素全部字段
// 关闭为两段式（先 hide() 播收起动画、动画完才除名卸载），避免残影/闪断。
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
object GpkgAttrSheet {

    private val _feature = MutableStateFlow<Feature?>(null)

    /** 显示标记：与数据分离，关闭动画期间数据保留，内容不闪空 */
    private val _visible = MutableStateFlow(false)

    fun show(feature: Feature) {
        _feature.value = feature
        _visible.value = true
    }

    /** 关闭：只标记隐藏（动画播完才真正卸载） */
    fun dismiss() {
        _visible.value = false
    }

    @Composable
    fun Host() {
        val feature by _feature.collectAsState()
        val visible by _visible.collectAsState()
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()

        val f = feature
        // 隐藏且动画不在跑 → 收起动画已播完，允许真正卸载；中途状态保持挂载
        if (f == null || (!visible && !sheetState.isVisible && !sheetState.isAnimationRunning)) {
            return
        }

        val entries = remember(f) {
            val o = f.properties()?.asJsonObject
            o?.entrySet()?.map { it.key to (it.value?.let { v -> if (v.isJsonNull) "" else v.asString } ?: "") }
                ?: emptyList()
        }

        ModalBottomSheet(
            onDismissRequest = {
                // 手势下滑 / 点 scrim / 返回键 统一入口：先播收起动画，再除名
                scope.launch {
                    sheetState.hide()
                    _feature.value = null
                }
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = f.getStringProperty("__table").ifBlank { "要素属性" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    items(entries.filter { it.first != "__table" }) { (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(120.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = v,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}