package local.readapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import local.readapp.core.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "books", indices = [Index(value = ["hash"], unique = true)])
data class BookRow(@PrimaryKey val id: String, val title: String, val hash: String, val uri: String,
    val copied: Boolean, val encoding: String, val chars: Long, val bytes: Long,
    val position: Long = 0, val lastRead: Long = 0, val importedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue="'txt'") val format:String="txt",@ColumnInfo(defaultValue="''") val author:String="",
    @ColumnInfo(defaultValue="''") val locator:String="", @ColumnInfo(defaultValue="''") val description:String="") {
    fun model() = Book(id,title,encoding,chars,bytes,position,lastRead,importedAt,copied,format,author,LocatorCodec.decode(locator,position).copy(format=format),description=description)
}
@Dao interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC, importedAt DESC") fun observe(): Flow<List<BookRow>>
    @Query("SELECT * FROM books WHERE id=:id") suspend fun find(id:String): BookRow?
    @Query("SELECT * FROM books WHERE hash=:hash") suspend fun byHash(hash:String): BookRow?
    @Insert suspend fun insert(row:BookRow)
    @Update suspend fun update(row:BookRow)
    @Query("UPDATE books SET position=:position,lastRead=:time WHERE id=:id") suspend fun progress(id:String,position:Long,time:Long)
    @Query("UPDATE books SET locator=:locator,lastRead=:time WHERE id=:id") suspend fun locator(id:String,locator:String,time:Long)
    @Query("DELETE FROM books WHERE id=:id") suspend fun delete(id:String)
}
@Entity(tableName="bookmarks",foreignKeys=[ForeignKey(entity=BookRow::class,parentColumns=["id"],childColumns=["bookId"],onDelete=ForeignKey.CASCADE)],indices=[Index("bookId")])
data class BookmarkRow(@PrimaryKey val id:String,val bookId:String,val title:String,val locator:String,val createdAt:Long)
@Dao interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId=:id ORDER BY createdAt DESC") fun observe(id:String):Flow<List<BookmarkRow>>
    @Insert suspend fun insert(row:BookmarkRow)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun delete(id:String)
    @Query("DELETE FROM bookmarks WHERE bookId=:id") suspend fun clear(id:String)
}
@Database(entities=[BookRow::class,BookmarkRow::class], version=3, exportSchema=true)
abstract class LibraryDatabase: RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun bookmarks():BookmarkDao
    companion object {
        val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE books ADD COLUMN description TEXT NOT NULL DEFAULT ''")}}
        val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'txt'")
            db.execSQL("ALTER TABLE books ADD COLUMN author TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE books ADD COLUMN locator TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, bookId TEXT NOT NULL, title TEXT NOT NULL, locator TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
        }}
    }
}
