package com.epicenter.hifi.nativeaudio;

public class NativeAudioTrack {
  public final String id;
  public final String title;
  public final String artist;
  public final String album;
  public final long duration;
  public final String source;
  public final String artworkUri;

  public NativeAudioTrack(String id, String title, String artist, String album, long duration, String source, String artworkUri) {
    this.id = id;
    this.title = title;
    this.artist = artist;
    this.album = album;
    this.duration = duration;
    this.source = source;
    this.artworkUri = artworkUri;
  }
}
