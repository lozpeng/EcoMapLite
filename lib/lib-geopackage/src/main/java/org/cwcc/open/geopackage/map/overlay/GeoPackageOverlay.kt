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

package org.cwcc.open.geopackage.map.overlay;

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.JsonObject
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.geopackage.tiles.features.DefaultFeatureTiles
import mil.nga.geopackage.tiles.features.FeatureTiles
import mil.nga.proj.Projection
import mil.nga.proj.ProjectionConstants
import mil.nga.proj.ProjectionFactory
import mil.nga.sf.geojson.FeatureConverter
import org.cwcc.open.geopackage.SysTableInfos
import org.cwcc.open.geopackage.toBndBox

import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection;

public class GeoPackageOverlay : BoundedOverlay {
    var geoPackage: GeoPackage
    var featureTable:String
    var density:Float =60.0f
    val gson = Gson()
    var needClearUp = true

    private val WEB_MERCATOR_PROJECTION: Projection = ProjectionFactory
        .getProjection(ProjectionConstants.EPSG_WEB_MERCATOR.toLong())

    /**
     *
     */
    constructor(ctxt: Context, libreMap: MapLibreMap, gpk: GeoPackage, lyrName:String): super(ctxt,libreMap)
    {
        geoPackage = gpk
        featureTable = lyrName
        needClearUp = false
    }

    /**
     *
     */
    constructor(ctxt: Context, libreMap: MapLibreMap,pgkPath:String, lyrName:String): super(ctxt,libreMap)
    {
        geoPackage= GeoPackageFactory.openExternal(pgkPath)
        featureTable = lyrName
    }

    /**
     * 用于数据库中只有一个矢量文件的
     */
    constructor(ctxt: Context, libreMap: MapLibreMap,pgkPath:String): super(ctxt,libreMap)
    {
        geoPackage= GeoPackageFactory.openExternal(pgkPath)
        val features = geoPackage.featureTables
        featureTable = if(features.size>=1)
            features[0]
        else "";
        if(featureTable.isNotEmpty()){}
    }

    override fun getFeaturesForBounds(bounds: LatLngBounds, zoomLevel: Int): FeatureCollection {
        return try{
            if(bounds.isEmptySpan)return  FeatureCollection.fromFeatures(ArrayList())
            //根据数据类型和数据量决定显示方式




            //超过一定数量 是否考虑用显示级别来限制要素显示情况?
            //在一定显示级别是考虑用点的表示要素的位置,1-6级?
            //6级以上显示矢量要素
            val featureDao = geoPackage.getFeatureDao(featureTable)
            val fbndx: BoundingBox = featureDao.boundingBox
            val intBndx = fbndx.intersects(bounds.toBndBox())
            if(!intBndx)return FeatureCollection.fromFeatures(ArrayList())

            val featureTiles: FeatureTiles =
                DefaultFeatureTiles(mContext, geoPackage,  featureDao, density)
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
                    if(item.value.value !is GeoPackageGeometryData)
                        attrMap[item.key] = item.value.value
                }
                val jsonObject = JsonObject()
                jsonObject.addProperty("type","Feature")
                val attrJsonObject: JsonObject = gson.fromJson(gson.toJson(attrMap), JsonObject::class.java)
                attrJsonObject.addProperty(SysTableInfos.SYS_TABLE_FIELD,featureTable)  //记录当前记录的表格名称
                jsonObject.add("properties",attrJsonObject)
                val geoJson: JsonObject = gson.fromJson(FeatureConverter.toStringValue(geo.geometry), JsonObject::class.java)
                jsonObject.add("geometry",geoJson)
                val f = Feature.fromJson(jsonObject.toString())
                features.add(f)
            }
            //根据级别获取矢量图形要素
            return FeatureCollection.fromFeatures(features)
        }
        catch (ex:Exception)
        {
            FeatureCollection.fromFeatures(ArrayList())
        }
    }

    override fun close() {

    }
}
