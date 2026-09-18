package org.cwcc.open.geokori.lib.database.meta

import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.table.DatabaseTable

/**
 * 数据库的版本，
 */
@DatabaseTable(tableName = "eco_db_version")
class DatabaseVersion{
  /**
   * 序号
   */
  @DatabaseField(columnName = "id", generatedId = true)
  var Id:Int? = null

  /**
   * 版本号
   */
  @DatabaseField(columnName = "version")
  var version:Int? = 0

  /**
   * 数据库版本日期
   */
  @DatabaseField(columnName = "dbtime")
  var dbtime:String? = ""

  /**
   * 备注
   */
  @DatabaseField(columnName = "memo")
  var memo:String? = ""
}
