package local.readapp.data
import android.content.Context
import androidx.room.Room
import local.readapp.core.*

class LocalServices(context:Context,engine:TextEngine,epub:EpubEngine) {
    private val database=Room.databaseBuilder(context.applicationContext,LibraryDatabase::class.java,"library.db").addMigrations(LibraryDatabase.MIGRATION_1_2,LibraryDatabase.MIGRATION_2_3).build()
    val books:BookRepository=LocalBookRepository(context.applicationContext,database.books(),database.bookmarks(),engine,epub)
    val preferences:PreferencesRepository=LocalPreferences(context.applicationContext)
}

