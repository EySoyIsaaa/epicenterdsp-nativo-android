package com.epicenter.hifi.nativeaudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the ordered list of tracks queued for native playback.
 *
 * The index here is intentionally kept in sync with ExoPlayer's
 * getCurrentMediaItemIndex() — callers should not read it independently;
 * instead they should query NativePlaybackController.getCurrentTrack()
 * which derives the index from the player directly.
 *
 * Thread-safety: all methods are synchronized. setQueue/clear are called
 * from the plugin thread; getTrackAtIndex may be called from the player
 * listener (main thread).
 */
public class NativeQueueManager {

    private final List<NativeAudioTrack> tracks = new ArrayList<>();

    public synchronized void setQueue(List<NativeAudioTrack> newTracks) {
        tracks.clear();
        if (newTracks != null) {
            tracks.addAll(newTracks);
        }
    }

    /** Replaces the queue with a single track (single-track playback mode). */
    public synchronized void setSingleTrack(NativeAudioTrack track) {
        tracks.clear();
        if (track != null) {
            tracks.add(track);
        }
    }

    public synchronized NativeAudioTrack getTrackAtIndex(int index) {
        if (index < 0 || index >= tracks.size()) return null;
        return tracks.get(index);
    }

    public synchronized int size() {
        return tracks.size();
    }

    /** Returns an unmodifiable snapshot — safe to iterate without holding the lock. */
    public synchronized List<NativeAudioTrack> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    // Legacy — kept for callers that haven't migrated to index-based access.
    public synchronized NativeAudioTrack getCurrentTrack() {
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    public synchronized void setCurrentTrack(NativeAudioTrack track) {
        setSingleTrack(track);
    }

    public synchronized void clear() {
        tracks.clear();
    }
}
