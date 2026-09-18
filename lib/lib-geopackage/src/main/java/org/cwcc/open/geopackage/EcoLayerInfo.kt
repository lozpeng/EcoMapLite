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

import android.graphics.drawable.Drawable

/**
 * 图层信息
 */
data class EcoLayerInfo(
    var name:String,
    var cname:String="",
    var type:Int =0,
    var labelField:String="",
    var icoField:String="",
    var defaultIco:Drawable?=null,
    var defaultIcoValue:String= SysTableInfos.SYS_ICO_DEFAULT_VALUE
    )
{

}

class SysTableInfos {

  companion object{
    const val SYS_TABLE_FIELD="__sys__tablename"
    const val SYS_FIELD_NAME = "__sys__fieldname"

    const val SYS_ICO_FIELD = "__gis_ico_field"
    const val SYS_ICO_DEFAULT_VALUE = "__gis_default_ico"

    const val SYS_FLDINFO_TAB_NAME="sys_table_field_meta"
    const val SYS_TAINFO_TB_NAME="sys_table_info"
    const val SYS_ICO_TAB_NAME = "sys_gis_ico_info"
  }
}
