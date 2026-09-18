package org.cwcc.open.geokori.ui.material3.search.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(
    entities = [SearchEntity::class, SuggestionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SearchDatabase : RoomDatabase() {
  abstract fun searchDao(): SearchDao

  companion object {
    @Volatile
    private var INSTANCE: SearchDatabase? = null

    fun getInstance(context: Context): SearchDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
            context.applicationContext,
            SearchDatabase::class.java,
            "search_database"
        )
            .fallbackToDestructiveMigration()
            .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
