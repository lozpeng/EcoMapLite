package org.cwcc.open.geokori.lib.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import com.j256.ormlite.android.apptools.OrmLiteSqliteOpenHelper
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.field.DataType
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.stmt.SelectArg
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.table.DatabaseTable
import com.j256.ormlite.table.TableUtils
import org.cwcc.open.geokori.lib.GeoKoriConfig
import org.cwcc.open.geokori.lib.R
import org.cwcc.open.geokori.lib.database.meta.SysTableInfos
import org.cwcc.open.geokori.lib.utils.ImageUtils

class KoriIconDbHelper(val context:Context) {
  companion object {
    lateinit var icoDao: Dao<KoriIcoInfo, Int>
    @JvmStatic
    lateinit var defaultIco: Drawable
      private set
  }

  init{
    val daoHelper = AniIconDbHelper(context)
    icoDao = daoHelper.getDao(KoriIcoInfo::class.java)
    defaultIco = AppCompatResources.getDrawable(context, R.drawable.ic_default_ico)!!
  }

  fun queryByName(name:String?):MutableList<KoriIcoInfo>
  {
    if(name.isNullOrEmpty())return mutableListOf()
    val selectArg = SelectArg()
    selectArg.setValue(name)
    return icoDao.queryBuilder().where().eq(KoriIcoInfo.NAME_FIELD,selectArg).query()
  }

  /**
   * 获取一个图标
   */
  fun getIco(name:String): Drawable
  {
    if(name.isEmpty())return defaultIco

    val icoInfos = queryByName(name)
    return if(icoInfos.isEmpty())
      defaultIco
    else {
      try{
        val icoByteArray = icoInfos[0].icoData
        if(icoByteArray?.isEmpty() == true)
          return defaultIco
        else
        {
          val sizeInDp = 30f
          val drawable = ImageUtils.bytes2Drawable(context,icoByteArray)?.mutate()
          val px = TypedValue.applyDimension(
              TypedValue.COMPLEX_UNIT_DIP,
              sizeInDp,
              context.resources.displayMetrics
          )
          val sizeInPx = Math.round(px)
          drawable?.setBounds(0,0,sizeInPx,sizeInPx)
          return drawable!!
        }
      } catch (ex:Exception) {
        defaultIco
      }
    }
  }
}

/**
 *
 */
class AniIconDbHelper(context:Context,dbName:String="ani_sys_gis_icos")
  : OrmLiteSqliteOpenHelper(context, dbName,null,1) {
  private val dbPath = "${GeoKoriConfig.icoPath}/${dbName}"

  override fun getWritableDatabase(): SQLiteDatabase {
    return SQLiteDatabase.openDatabase(dbPath,null, SQLiteDatabase.OPEN_READWRITE)
  }

  override fun getReadableDatabase(): SQLiteDatabase {
    return SQLiteDatabase.openDatabase(dbPath,null, SQLiteDatabase.OPEN_READONLY)
  }

  override fun onCreate(database: SQLiteDatabase?, connectionSource: ConnectionSource?) {
    TableUtils.createTableIfNotExists(connectionSource, KoriIcoInfo::class.java)
  }

  override fun onUpgrade(
      database: SQLiteDatabase?,
      connectionSource: ConnectionSource?,
      oldVersion: Int,
      newVersion: Int
  ) {

  }


}
@DatabaseTable(tableName = SysTableInfos.SYS_ICO_TAB_NAME)
class KoriIcoInfo {
  @DatabaseField(columnName = "id", generatedId = true)
  var Id:Int? = null

  /**
   * 字段名称
   */
  @DatabaseField(columnName = NAME_FIELD)
  var name:String? = ""

  @DatabaseField(columnName = ICON_FIELD,dataType= DataType.BYTE_ARRAY )
  var icoData:ByteArray?=null


  companion object{
    const val NAME_FIELD:String = "name"

    const val ICON_FIELD:String = "ico"
  }
}
