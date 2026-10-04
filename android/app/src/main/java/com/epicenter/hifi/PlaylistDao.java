package com.epicenter.hifi;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PlaylistDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void upsert(PlaylistEntity playlist);

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  long insertTrack(PlaylistTrackEntity track);

  @Query("SELECT * FROM playlists ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
  List<PlaylistEntity> getAll();

  @Query("SELECT * FROM playlists WHERE playlistId=:id LIMIT 1")
  PlaylistEntity getById(String id);

  @Query("SELECT trackStableId FROM playlist_tracks WHERE playlistId=:playlistId ORDER BY position ASC, addedAt ASC")
  List<String> getTrackIds(String playlistId);

  @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId=:playlistId")
  Integer getMaxPosition(String playlistId);

  @Query("UPDATE playlists SET name=:name, updatedAt=:now WHERE playlistId=:id")
  void rename(String id, String name, long now);

  @Query("UPDATE playlists SET updatedAt=:now WHERE playlistId=:id")
  void touch(String id, long now);

  @Query("UPDATE playlist_tracks SET position=:position WHERE playlistId=:playlistId AND trackStableId=:trackStableId")
  void updateTrackPosition(String playlistId, String trackStableId, int position);

  @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId AND trackStableId=:trackStableId")
  void removeTrack(String playlistId, String trackStableId);

  @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId")
  void clearPlaylistTracks(String playlistId);

  @Query("DELETE FROM playlist_tracks WHERE trackStableId=:trackStableId")
  void removeTrackEverywhere(String trackStableId);

  @Query("DELETE FROM playlist_tracks")
  void clearAllTrackReferences();

  @Query("DELETE FROM playlists WHERE playlistId=:id")
  void deletePlaylist(String id);
}
