package com.epicenter.hifi;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "playlists")
public class PlaylistEntity {
  @PrimaryKey @NonNull public String playlistId = "";
  @NonNull public String name = "";
  public long createdAt;
  public long updatedAt;
  public String coverUrl;
}
