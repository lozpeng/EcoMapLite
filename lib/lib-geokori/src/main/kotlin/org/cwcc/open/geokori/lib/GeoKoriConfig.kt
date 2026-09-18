package org.cwcc.open.geokori.lib

import android.os.Environment
import java.io.File


object GeoKoriConfig {
  @JvmStatic
  val storagePath:String
    get(){
      val sysPath = if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
        "${Environment.getExternalStorageDirectory().absolutePath}${File.separator}EcoMap"
      else "${Environment.getDataDirectory().absolutePath}${File.separator}EcoMap"

      if(!File(sysPath).exists())
        File(sysPath).mkdirs()
      return sysPath
    }

  @JvmStatic
  val databasePath:String
    get(){
      val str =  "${storagePath}${File.separator}databases"
      if(!File(str).exists())
        File(str).mkdirs()
      return str
    }
  @JvmStatic
  val wildLifeDyDbPath:String
    get(){
      val str =  "${storagePath}${File.separator}wdb"
      if(!File(str).exists())
        File(str).mkdirs()
      return str
    }

  @JvmStatic
  val icoPath:String
    get(){
      val str =  "${storagePath}${File.separator}icos"
      if(!File(str).exists())
        File(str).mkdirs()
      return str
    }

  @JvmStatic
  val configPath:String
    get(){
      val str =  "${storagePath}${File.separator}configs"
      if(!File(str).exists())
        File(str).mkdirs()
      return str
    }
}
