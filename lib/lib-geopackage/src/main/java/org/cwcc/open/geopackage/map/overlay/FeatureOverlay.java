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

import android.content.Context;

import androidx.annotation.NonNull;

import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;

import java.util.ArrayList;
import java.util.List;

import mil.nga.geopackage.BoundingBox;
import mil.nga.geopackage.features.index.FeatureIndexResults;
import mil.nga.geopackage.tiles.features.FeatureTiles;

/**
 * feature overlay
 */
public class FeatureOverlay extends BoundedOverlay{
    private final FeatureTiles featureTiles;

    /**
     * Linked GeoPackage overlays
     */
    private List<GeoPackageOverlay> linkedOverlays = new ArrayList<>();

    /**
     * Constructor
     *
     * @param featureTiles feature tiles
     */
    public FeatureOverlay(FeatureTiles featureTiles, Context context, MapLibreMap libreMap) {
        super(context,libreMap);
        this.featureTiles = featureTiles;
    }
    /**
     * Get the feature tiles
     *
     * @return feature tiles
     * @since 1.1.0
     */
    public FeatureTiles getFeatureTiles() {
        return featureTiles;
    }

    @NonNull
    @Override
    public FeatureCollection getFeaturesForBounds(@NonNull LatLngBounds latLngBounds, int zoomLevel) {
        BoundingBox bbx = new BoundingBox(latLngBounds.longitudeWest
                                        ,latLngBounds.latitudeSouth
                                        ,latLngBounds.longitudeEast
                                        ,latLngBounds.latitudeNorth);
        FeatureIndexResults results = featureTiles.queryIndexedFeatures(bbx);
        FeatureCollection features = FeatureCollection.fromFeatures(new ArrayList<>());
        if(results.count() <= 0)return features;

//        //数据量太大的时候怎么显示?
//        results.forEach{
//            featureRow->
//                    val geo = featureRow.geometry
//            val attrs = featureRow.asMap
//            val attrMap:MutableMap<String,Any?> = mutableMapOf()
//            attrs.forEach {
//                item->
//                if(fieldInfos.containsKey(item.key))
//                {
//                    val fieldNameAlias = fieldInfos[item.key]!!
//                    if(item.value.value !is GeoPackageGeometryData)
//                    attrMap[fieldNameAlias] = item.value.value
//                }
//            }
//            val jsonObject = JsonObject()
//            jsonObject.addProperty("type","Feature")
//            val attrJsonObject: JsonObject = gson.fromJson(gson.toJson(attrMap), JsonObject::class.java)
//            //attrJsonObject.addProperty(SysTableInfos.SYS_TABLE_FIELD,featureTable)  //记录当前记录的表格名称
//            attrJsonObject.addProperty(SysTableInfos.SYS_ICO_FIELD,mDefaulIcoValue)
//            jsonObject.add("properties",attrJsonObject)
//            val geoJson: JsonObject = gson.fromJson(FeatureConverter.toStringValue(geo.geometry), JsonObject::class.java)
//            jsonObject.add("geometry",geoJson)
//            val f = Feature.fromJson(jsonObject.toString())
//            features.add(f)
//        }
//        //根据级别获取矢量图形要素
//        return FeatureCollection.fromFeatures(features)
        return null;
    }

    @Override
    public void close() {

    }
}
