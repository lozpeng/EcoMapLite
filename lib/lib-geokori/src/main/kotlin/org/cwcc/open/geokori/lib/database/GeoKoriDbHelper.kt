package org.cwcc.open.geokori.lib.database

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.j256.ormlite.android.apptools.OrmLiteSqliteOpenHelper
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.table.TableUtils
import java.io.File
import java.io.FileOutputStream
import org.cwcc.open.geokori.lib.GeoKoriConfig
import org.cwcc.open.geokori.lib.database.meta.KoriSysTableFieldMeta
import org.cwcc.open.geokori.lib.database.meta.DatabaseVersion

/**
 * 数据库操作基类
 */
abstract class GeoKoriDbHelper(val context: Context, dbName:String, dbVer:Int, private val rawId:Int,
                      var dbType:DbType = DbType.DB_BASE
) : OrmLiteSqliteOpenHelper(context, dbName,null,dbVer) {

  private val  dbPath:String
  init{
    checkDbStatus(context,databaseName,rawId,dbType)
    val dPath = "${GeoKoriConfig.databasePath}/${dbType}"
    val folder = File(dPath)
    if (!folder.exists() && !folder.isDirectory) {
      folder.mkdirs()
    }
    dbPath = "${dPath}/${dbName}"
  }
  override fun getWritableDatabase(): SQLiteDatabase {
    checkDbStatus(context, databaseName,rawId,dbType)
    val db =  SQLiteDatabase.openDatabase(dbPath,null,SQLiteDatabase.OPEN_READWRITE)
    return db
  }

  override fun getReadableDatabase(): SQLiteDatabase {
    checkDbStatus(context, databaseName,rawId,dbType)
    return SQLiteDatabase.openDatabase(dbPath,null,SQLiteDatabase.OPEN_READONLY)
  }

  /**
   * 初始化系统dao
   */
  fun initSysDaos(database: SQLiteDatabase?, connectionSource: ConnectionSource?)
  {
    TableUtils.createTableIfNotExists(connectionSource, DatabaseVersion::class.java)
    TableUtils.createTableIfNotExists(connectionSource, KoriSysTableFieldMeta::class.java)
  }


  /**
   * 获取数据库版本
   */
  fun getDbVer(): DatabaseVersion?
  {
    val dbVerDao = getDbVerDao()
    if(!dbVerDao.isTableExists)
      TableUtils.createTableIfNotExists(this.connectionSource, DatabaseVersion::class.java)
    val vers = dbVerDao.queryBuilder()
        .orderBy("version",false)
        .limit(1)
        .offset(0)
        .query()
    if(vers.isEmpty())return null
    return vers[0]
  }


  fun updateVersion(verNumber:Int):Boolean{
    try {
      var dbVer = getDbVer()
      if (dbVer == null) {
        dbVer = DatabaseVersion()
        dbVer.Id = 1
        dbVer.version = verNumber
        dbVer.dbtime = System.currentTimeMillis().toString()
      }
      dbVer.version = verNumber
      val dao = getDbVerDao()
      dao.createOrUpdate(dbVer)
      return true
    }
    catch (e:Exception){}
    return false
  }
  /**
   *
   */
  fun getDbVerDao(): Dao<DatabaseVersion, Int>
  {
    return this.getDao(DatabaseVersion::class.java)
  }

  /**
   *
   */
  fun getTableFieldInfos(tableName:String):MutableList<KoriSysTableFieldMeta>
  {
    val fieldInfoDao = getFieldMetaInfoDao()
    if(!fieldInfoDao.isTableExists)
      return mutableListOf()
    if(tableName.isEmpty())
      return  mutableListOf()
    val where = fieldInfoDao.queryBuilder().where()
        .eq(KoriSysTableFieldMeta.TAB_NAME_FIELD,tableName)

    return where.query()
  }

  /**
   * 获取表格的字段元信息
   */
  @SuppressLint("SuspiciousIndentation")
  fun getTableFieldMetaInfos(tableName:String):MutableList<KoriSysTableFieldMeta>
  {
    val fieldInfoDao = getFieldMetaInfoDao()
    if(!fieldInfoDao.isTableExists)
      return mutableListOf()
    if(tableName.isEmpty())
      return  mutableListOf()
    val qb = fieldInfoDao.queryBuilder()
    qb.orderBy(KoriSysTableFieldMeta.FIELD_ORDER,true)

    val where = qb.where()
    where.and(
        where.eq(KoriSysTableFieldMeta.TAB_NAME_FIELD,tableName),
        where.isNull(KoriSysTableFieldMeta.PARENT_FIELD)
    )
    val fldInfos =  where.query()
    val results = mutableListOf<KoriSysTableFieldMeta>()
    fldInfos.forEach{fldInfo->
      val sFields = getTableSubFiledInfos(tableName,fldInfo.name!!)
      if(sFields.size>=1)
        fldInfo.childFields.addAll(sFields)
      results.add(fldInfo)
    }
    return results
  }

  /**
   * 获取下一级子字段
   */
  private fun getTableSubFiledInfos(tableName:String,fieldName:String):MutableList<KoriSysTableFieldMeta>
  {
    val fieldInfoDao = getFieldMetaInfoDao()
    if(!fieldInfoDao.isTableExists)
      return mutableListOf()
    if(tableName.isEmpty()||fieldName.isEmpty())
      return  mutableListOf()
    val qb = fieldInfoDao.queryBuilder()
    qb.orderBy(KoriSysTableFieldMeta.FIELD_ORDER,true)

    val where = qb.where()
    where.and(
        where.eq(KoriSysTableFieldMeta.TAB_NAME_FIELD,tableName),
        where.eq(KoriSysTableFieldMeta.PARENT_FIELD,fieldName)
    )
    val subFields = where.query()
    val results = mutableListOf<KoriSysTableFieldMeta>()

    subFields.forEach { fld->
      val sFields = getTableSubFiledInfos(tableName,fld.name!!)
      if(sFields.size>=1)
        fld.childFields.addAll(sFields)
      results.add(fld)
    }
    return results
  }
  /**
   * 获取字段信息表
   */
  fun getTableFieldInfoMap(tableName:String):MutableMap<String,String>
  {
    val fields = getTableFieldInfos(tableName)
    if(fields.isEmpty())return mutableMapOf()
    val fieldMap= mutableMapOf<String,String>()

    for(field in fields)
      fieldMap[field.name!!] = field.aliasName!!

    return fieldMap
  }
  /**
   * 获取字段元信息
   */
  fun getFieldMetaInfoDao():Dao<KoriSysTableFieldMeta,Int>
  {
    return this.getDao(KoriSysTableFieldMeta::class.java)
  }

//    @Synchronized
//    @Throws(SQLException::class)
//    fun getBizDao(clazz: Class<*>): Dao<*, *>? {
//        var dao: Dao<*, *>? = null
//        val className = clazz.simpleName
//        if (daos.containsKey(className)) {
//            dao = daos.get(className)
//        }
//        if (dao == null) {
//            dao = super.getDao<Dao<Any, *>, Any>(clazz)
//            daos.a(className, dao)
//        }
//        return dao
//    }


  companion object HelperFactory {
    fun checkDbStatus(context:Context,dbName:String, rawId:Int,dbType: DbType)
    {
      val dbDir = "${GeoKoriConfig.databasePath}/${dbType}"
      if(!File(dbDir).exists())
        File(dbDir).mkdirs()
      val dbPath ="$dbDir/$dbName"
      if(rawId<=-1) //如果给定的资源号小于等于 -1 则不进行数据库文件的拷贝，直接放置在程序的数据库文件目录
      {
        if(!File(dbDir,dbName).exists())
          File(dbDir,dbName).createNewFile()
        return
      }
      //判断文件是否存在。
      if(!File(dbDir,dbName).exists())  //文件不存在，则需要从Assets文件夹里把文件复制过来
      {
        try {
          val inputStream = context.resources.openRawResource(rawId)
          val ostream = FileOutputStream(dbPath)
          val buffer = ByteArray(1024)
          var length: Int=0
          while (inputStream.read(buffer).also { length = it } != -1) {
            ostream.write(buffer, 0, length)
          }
          ostream.flush()
          ostream.close()
          inputStream.close()
        }
        catch (e:Exception)
        {
          e.printStackTrace()
        }
      }
    }

    private val DBHelpers = emptyMap<String,GeoKoriDbHelper>()
    @Synchronized
    fun getDbHelper(context: Context, dbName:String, dbVer:Int, rawId:Int):GeoKoriDbHelper
    {
      var instance:GeoKoriDbHelper?=null
      synchronized(GeoKoriDbHelper::class.java) {
        if (DBHelpers.containsKey(dbName)) {
          instance =DBHelpers[dbName]
        }
        else
        {
          //instance = EcoMapDbOpenHelper(context,dbName,rawId)

          //TODO::refactor the implmentations that make sure to one dbfile just one connection
        }
      }

      return instance!!
    }
    /**
     *
     */
    private fun String.isFileExists():Boolean{
      return try{
        File(this).exists()
      } catch (e:Exception) {
        false;
      }
    }

  }

}


enum class DbType{
  DB_BASE,        //基础数据
  DB_WINFO,       //野外监测信息
  DB_SYS,         //系统数据
  DB_GEO,         //地理数据
  DB_BIZ,         //业务数据
  DB_UNK          //未知
}
