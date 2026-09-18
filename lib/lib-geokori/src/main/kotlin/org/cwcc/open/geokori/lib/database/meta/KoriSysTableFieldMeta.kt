package org.cwcc.open.geokori.lib.database.meta

import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable


@DatabaseTable(tableName = SysTableInfos.SYS_FLDINFO_TAB_NAME)
class KoriSysTableFieldMeta {
  /**
   * 序号
   */
  @DatabaseField(columnName = "id", generatedId = true)
  var Id:Int? = null

  /**
   * 字段名称
   */
  @DatabaseField(columnName = "name")
  var name:String? = ""

  /**
   * 字段别名
   */
  @DatabaseField(columnName = "chn_name")
  var aliasName:String? = ""

  @DatabaseField(columnName = TAB_NAME_FIELD)
  var tabName:String? = ""

  @DatabaseField(columnName = FIELD_ORDER)
  var field_order:Int? = 0;

  /**
   * 父字段，如果父字段为空则表示为顶层字段
   */
  @DatabaseField(columnName = PARENT_FIELD)
  var field_parent:String?=""

  /**
   * 字段类型
   */
//    @DatabaseField(columnName = "fldtype")
//    var fieldType:FieldType=FieldType.fldAny

  /**
   * 子字段
   */
  var childFields:MutableList<KoriSysTableFieldMeta> = mutableListOf()


  companion object{
    const val TAB_NAME_FIELD = "tab_name"
    const val FIELD_ORDER = "field_order"
    const val PARENT_FIELD = "field_parent"
  }
}

/**
 * 字段类型
 */
enum class FieldType{
  fldInt,  //整型
  fldDobule, //浮点型
  fldString,  //字符串
  fldGeo,    //地理
  fldTime,   //时间
  fldAny     //任意类型
}
