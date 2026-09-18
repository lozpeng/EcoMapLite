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

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.sources.GeometryTileProvider;

public abstract class BoundedOverlay implements GeometryTileProvider {
    protected final Context mContext;
    protected final MapLibreMap mLibreMap;
    /**
     *
     * @param context
     * @param libreMap
     */
    public BoundedOverlay(Context context,MapLibreMap libreMap)
    {
        mContext = context;
        mLibreMap = libreMap;
    }

    public abstract  void close();
}
