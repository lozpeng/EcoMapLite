/*
 * Copyright (C) 2024 ani@cwcc(cwcc.ani@163.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.cwcc.open.geopackage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.features.index.FeatureIndexManager
import mil.nga.geopackage.features.index.FeatureIndexType
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.geopackage.tiles.features.DefaultFeatureTiles
import mil.nga.geopackage.tiles.features.FeatureTiles
import mil.nga.sf.geojson.FeatureConverter
import org.cwcc.open.geopackage.LocalGeopackageLayer.Companion.SYS_FLD_CHN
import org.cwcc.open.geopackage.LocalGeopackageLayer.Companion.SYS_FLD_NAME
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.GeometryTileProvider
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 *
 */
class GeoPackageProvider: GeometryTileProvider {
    var geoPackage: GeoPackage
    var featureTable:String
    var context:Context
    var density:Float =60.0f
    val gson = Gson()
    var needClearUp = true
    var mDefaulIcoValue:String=SysTableInfos.SYS_ICO_DEFAULT_VALUE

    //private val WEB_MERCATOR_PROJECTION: Projection = ProjectionFactory
     //               .getProjection(ProjectionConstants.EPSG_WEB_MERCATOR.toLong())

    var fieldInfos:MutableMap<String,String> = mutableMapOf()
    /**
     *
     */
    constructor(ctxt: Context, gpk: GeoPackage, lyrName:String,defaultIcoValue:String=SysTableInfos.SYS_ICO_DEFAULT_VALUE)
    {
        geoPackage = gpk
        featureTable = lyrName
        context =ctxt
        mDefaulIcoValue = defaultIcoValue
        needClearUp = false
        initLayers()
    }

    /**
     *
     */
    constructor(ctxt: Context,pgkPath:String,lyrName:String,defaultIcoValue:String=SysTableInfos.SYS_ICO_DEFAULT_VALUE)
    {
        context =ctxt
        geoPackage= GeoPackageFactory.openExternal(pgkPath)
        featureTable = lyrName
        mDefaulIcoValue = defaultIcoValue
        initLayers()
    }

    /**
     * 用于数据库中只有一个矢量文件的
     */
    constructor(ctxt: Context,pgkPath:String,defaultIcoValue:String=SysTableInfos.SYS_ICO_DEFAULT_VALUE)
    {
        context =ctxt
        mDefaulIcoValue = defaultIcoValue
        geoPackage= GeoPackageFactory.openExternal(pgkPath)
        val features = geoPackage.featureTables
        featureTable = if(features.size>=1) features[0] else "";
        if(featureTable.isNotEmpty())
            initLayers()
    }

    fun initLayers() {
        density =context.applicationContext.resources.displayMetrics.density
        val featureDao = geoPackage.getFeatureDao(featureTable)
        fieldInfos = getTableFieldInfos(featureTable)

        val indexer = FeatureIndexManager(context, geoPackage, featureDao)
        try {
            if (!indexer.isIndexed()) {
                indexer.setIndexLocation(FeatureIndexType.GEOPACKAGE);
                indexer.index();
            }
        } finally {
            indexer.close();
        }
    }
//    fun projectBoundingBox(
//        boundingBox: BoundingBox,
//        projection: Projection?
//    ): BoundingBox {
//        val projectionTransform: GeometryTransform = GeometryTransform
//            .create(projection,WEB_MERCATOR_PROJECTION)
//        val projectedBoundingBox = boundingBox
//            .transform(projectionTransform)
//        return projectedBoundingBox
//    }
    /**
     * 根据范围获取几何数据
     */
    override fun getFeaturesForBounds(bounds: LatLngBounds, zoomLevel: Int): FeatureCollection {
        return try{
            if(bounds.isEmptySpan)return  FeatureCollection.fromFeatures(ArrayList())
            //超过一定数量 是否考虑用显示级别来限制要素显示情况?
            //在一定显示级别是考虑用点的表示要素的位置,1-6级?
            //6级以上显示矢量要素
            val featureDao = geoPackage.getFeatureDao(featureTable)
            val fbndx: BoundingBox = featureDao.boundingBox
            val intBndx = fbndx.intersects(bounds.toBndBox())
            if(!intBndx)return FeatureCollection.fromFeatures(ArrayList())

            val featureTiles: FeatureTiles =
                DefaultFeatureTiles(context, geoPackage,  featureDao, density)
            val queryBox = bounds.toBndBox()

            val results = featureTiles.queryIndexedFeatures2(queryBox)
            if(results.count() <= 0)return FeatureCollection.fromFeatures(ArrayList())

            val features: MutableList<Feature> = mutableListOf()
            //数据量太大的时候怎么显示?
            results.forEach{
                featureRow->
                val geo = featureRow.geometry
                val attrs = featureRow.asMap
                val attrMap:MutableMap<String,Any?> = mutableMapOf()
                attrs.forEach {
                   item->
                    if(fieldInfos.containsKey(item.key))
                    {
                        val fieldNameAlias = fieldInfos[item.key]!!
                        if(item.value.value !is GeoPackageGeometryData)
                            attrMap[fieldNameAlias] = item.value.value
                    }
                }
                val jsonObject = JsonObject()
                jsonObject.addProperty("type","Feature")
                val attrJsonObject: JsonObject = gson.fromJson(gson.toJson(attrMap), JsonObject::class.java)
                //attrJsonObject.addProperty(SysTableInfos.SYS_TABLE_FIELD,featureTable)  //记录当前记录的表格名称
                attrJsonObject.addProperty(SysTableInfos.SYS_ICO_FIELD,mDefaulIcoValue)
                jsonObject.add("properties",attrJsonObject)
                val geoJson: JsonObject = gson.fromJson(FeatureConverter.toStringValue(geo.geometry), JsonObject::class.java)
                jsonObject.add("geometry",geoJson)
                features.add(Feature.fromJson(jsonObject.toString()))
            }
            //根据级别获取矢量图形要素
            return FeatureCollection.fromFeatures(features)
        }
        catch (ex:Exception)
        {
            FeatureCollection.fromFeatures(ArrayList())
        }
    }
    /**
     * 获取字段信息
     */
    /**
     * 获取表格字段信息
     */
    fun getTableFieldInfos(tableName:String):MutableMap<String,String>
    {
        val fieldInfos = mutableMapOf<String,String>()
        val tableInfos = geoPackage.getUserDao(SysTableInfos.SYS_FLDINFO_TAB_NAME)
        val tbCursor = tableInfos.query("tab_name='${tableName}' order by field_order")
        tbCursor.use { _ ->
            while(tbCursor!=null&&tbCursor.moveToNext()) {
                val name = tbCursor.getValue(SYS_FLD_NAME)
                val cname= tbCursor.getValue(SYS_FLD_CHN)
                if(cname==null|| cname.toString().isEmpty())continue
                fieldInfos[name.toString()] = cname.toString()
            }
        }
        return fieldInfos
    }
}
fun LatLngBounds.toBndBox():BoundingBox
{
    return BoundingBox(
        this.longitudeWest,
        this.latitudeSouth,
        this.longitudeEast,
        this.latitudeNorth
    )
}
