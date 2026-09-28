package com.epicenter.hifi;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = {"playlistId", "trackStableId"},
    indices = {@Index("playlistId"), @Index("trackStableId")})
public class PlaylistTrackEntity {
  @NonNull public String playlistId = "";
  @NonNull public String trackStableId = "";
  public int position;
  public long addedAt;
}
