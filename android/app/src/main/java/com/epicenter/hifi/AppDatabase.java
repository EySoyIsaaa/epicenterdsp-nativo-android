package com.epicenter.hifi;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {TrackEntity.class, PlaylistEntity.class, PlaylistTrackEntity.class},
    version = 3,
    exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
  public abstract TrackDao trackDao();
  public abstract PlaylistDao playlistDao();

  private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
    @Override
    public void migrate(SupportSQLiteDatabase database) {
      database.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`playlistId` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `coverUrl` TEXT, PRIMARY KEY(`playlistId`))");
      database.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` TEXT NOT NULL, `trackStableId` TEXT NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackStableId`))");
      database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)");
      database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackStableId` ON `playlist_tracks` (`trackStableId`)");
    }
  };

  private static volatile AppDatabase INSTANCE;

  public static AppDatabase get(Context context) {
    if (INSTANCE == null) {
      synchronized (AppDatabase.class) {
        if (INSTANCE == null) {
          INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "epicenter_native_library.db")
            .addMigrations(MIGRATION_2_3)
            // Version 1 already used the historical destructive 1->2 upgrade.
            // All current/future schemas must migrate without erasing a library.
            .fallbackToDestructiveMigrationFrom(1)
            .build();
        }
      }
    }
    return INSTANCE;
  }
}
