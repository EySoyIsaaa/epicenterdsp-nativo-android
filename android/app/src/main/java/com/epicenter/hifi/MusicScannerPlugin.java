package com.epicenter.hifi;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(
  name = "MusicScanner",
  permissions = {
    @Permission(alias = "audio33", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
    @Permission(alias = "audioLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
  }
)
public class MusicScannerPlugin extends Plugin {

  // ── Constants ────────────────────────────────────────────────────────────
  private static final String LIBRARY_PREFS          = "epicenter_library";
  private static final String LIBRARY_KEY            = "tracks_v1";
  private static final String ROOM_MIGRATED_KEY      = "room_migrated_v1";
  private static final String LAST_FULL_SCAN_COUNT_KEY = "last_full_scan_count_v1";
  private static final int    AUDIO_BUFFER_SIZE      = 512 * 1024; // 512 KB
  private static final int    MISSING_SCANS_BEFORE_UNAVAILABLE = 3;
  private static final long   MISSING_GRACE_SECONDS = 24L * 60L * 60L;

  // On-demand metadata enrichment tuning. Enrichment is NEVER a bulk sweep of
  // the whole library — only tracks the user views/plays are submitted, so the
  // bounded queue is naturally small; overflow tasks are dropped and retried
  // the next time that page/track is requested.
  private static final int  ENRICH_THREADS     = 2;
  private static final int  ENRICH_QUEUE_LIMIT = 512;
  private static final long ENRICH_FLUSH_MS    = 700L;

  // ── Executors ─────────────────────────────────────────────────────────────
  // FIX: Every @PluginMethod that does I/O or DB work dispatches to one of
  // these executors so the Capacitor bridge thread is NEVER blocked.
  private final ExecutorService dbExecutor   = Executors.newSingleThreadExecutor();
  private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

  // ── On-demand metadata enrichment (bitDepth/sampleRate) ───────────────────
  // CRITICAL FIX: enrichment is DEMAND-DRIVEN. With 5000+ songs the old
  // "enrich everything after the scan" loop did ~5000 file opens
  // (MediaMetadataRetriever + MediaExtractor) on a background pool AND emitted
  // ~5000 bridge events, freezing the whole app for minutes. Now:
  //   • A bounded queue + DiscardPolicy keeps the backlog tiny (the UI only
  //     submits the page/track it actually shows or plays).
  //   • enrichSubmitted dedupes so the same track is never enriched twice.
  //   • Completed results are COALESCED and flushed as a single
  //     "trackMetaEnrichedBatch" event instead of one event per track.
  private final java.util.Set<String> enrichSubmitted =
      Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final RejectedExecutionHandler enrichRejectHandler = (r, ex) -> {
    // Dropped before running → un-mark so it can be retried later.
    if (r instanceof EnrichTask) enrichSubmitted.remove(((EnrichTask) r).stableId);
  };
  private final ThreadPoolExecutor metaExecutor = new ThreadPoolExecutor(
      ENRICH_THREADS, ENRICH_THREADS, 30, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(ENRICH_QUEUE_LIMIT), enrichRejectHandler);
  private final List<JSObject> enrichBuffer =
      Collections.synchronizedList(new ArrayList<>());
  private volatile ScheduledExecutorService enrichFlusher;

  /** Runnable that remembers its track id so the reject handler can un-mark it. */
  private static final class EnrichTask implements Runnable {
    final String stableId;
    final Runnable body;
    EnrichTask(String stableId, Runnable body) { this.stableId = stableId; this.body = body; }
    @Override public void run() { body.run(); }
  }

  // ── Audio HTTP streaming server ───────────────────────────────────────────
  private volatile ServerSocket audioServerSocket;
  private volatile int          audioServerPort = -1;
  private final String          audioSessionToken = UUID.randomUUID().toString();

  // ── Wake lock ─────────────────────────────────────────────────────────────
  private PowerManager.WakeLock playbackWakeLock;

  // ── Audio format info (used only for manual imports + lazy enrichment) ────
  private static class AudioFormatInfo {
    Integer bitDepth;
    Integer sampleRate;
    Integer bitrate;
    Integer channels;
  }

  /**
   * Opens the audio file and reads codec-level format metadata.
   * This is intentionally NOT called during the bulk MediaStore scan.
   * It is only used:
   *   1. When the user manually imports individual files (one-off, acceptable cost).
   *   2. Lazily in the background via enrichTrackMetadataAsync after the scan.
   */
  private AudioFormatInfo getAudioFormatInfo(Uri contentUri) {
    AudioFormatInfo info = new AudioFormatInfo();
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    MediaExtractor extractor = new MediaExtractor();
    try {
      retriever.setDataSource(getContext(), contentUri);
      String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
      if (bitrateStr != null && !bitrateStr.isEmpty()) {
        try { info.bitrate = Integer.parseInt(bitrateStr); } catch (NumberFormatException ignored) {}
      }
    } catch (Exception ignored) {}
    try {
      extractor.setDataSource(getContext(), contentUri, null);
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat fmt = extractor.getTrackFormat(i);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        if (mime == null || !mime.startsWith("audio/")) continue;
        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))  info.sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (info.bitrate == null && fmt.containsKey(MediaFormat.KEY_BIT_RATE)) info.bitrate = fmt.getInteger(MediaFormat.KEY_BIT_RATE);
        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) info.channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (fmt.containsKey("bits-per-sample"))             info.bitDepth = fmt.getInteger("bits-per-sample");
        break;
      }
    } catch (Exception ignored) {
    } finally {
      try { retriever.release(); } catch (Exception ignored) {}
      try { extractor.release();  } catch (Exception ignored) {}
    }
    return info;
  }

  /**
   * FIX – LAZY METADATA ENRICHMENT
   *
   * After the fast bulk scan, tracks that still lack bitDepth/sampleRate are
   * submitted here.  Each runs on metaExecutor (background, 2 threads), updates
   * Room, and emits a "trackMetaEnriched" event so the UI can refresh the badge.
   *
   * This decouples costly per-file I/O from the scan entirely:
   *   • Scan: fast, non-blocking, returns immediately with basic MediaStore data.
   *   • Enrichment: slow, background, transparent to the user.
   */
  private void enrichTrackMetadataAsync(TrackEntity entity) {
    if (entity == null || entity.sourceUri == null || entity.sourceUri.isEmpty()) return;
    if (entity.sampleRate != null && entity.sampleRate > 0) return; // already enriched
    final String stableId = entity.stableId;
    if (stableId == null || stableId.isEmpty()) return;
    // Dedupe: never submit the same track twice. Removed again on failure so a
    // later view/play can retry.
    if (!enrichSubmitted.add(stableId)) return;
    ensureEnrichFlusher();
    metaExecutor.execute(new EnrichTask(stableId, () -> {
      try {
        Uri uri = Uri.parse(entity.sourceUri);
        AudioFormatInfo info = getAudioFormatInfo(uri);
        boolean changed = false;
        if (info.sampleRate != null) { entity.sampleRate = info.sampleRate; changed = true; }
        if (info.bitDepth  != null) { entity.bitDepth   = info.bitDepth;   changed = true; }
        if (info.bitrate   != null) { entity.bitrate    = info.bitrate;    changed = true; }
        if (info.channels  != null) { entity.channels   = info.channels;   changed = true; }
        if (changed) {
          AudioFormatInfo proxy = new AudioFormatInfo();
          proxy.sampleRate = entity.sampleRate;
          proxy.bitDepth   = entity.bitDepth;
          proxy.bitrate    = entity.bitrate;
          proxy.channels   = entity.channels;
          entity.isHiRes   = isHiResByMetadata(proxy, entity.mimeType);
          dao().updateFormatInfo(entity.stableId,
            entity.bitDepth, entity.sampleRate, entity.bitrate,
            entity.channels, entity.isHiRes,
            System.currentTimeMillis() / 1000L);
          JSObject evt = new JSObject();
          evt.put("stableId",   entity.stableId);
          evt.put("sampleRate", entity.sampleRate);
          evt.put("bitDepth",   entity.bitDepth);
          evt.put("bitrate",    entity.bitrate);
          evt.put("channels",   entity.channels);
          evt.put("isHiRes",    entity.isHiRes);
          // Coalesced: buffered and flushed as one batch (see flushEnrichBuffer).
          enrichBuffer.add(evt);
        }
      } catch (Exception e) {
        enrichSubmitted.remove(stableId); // allow retry on next view/play
        android.util.Log.w("MusicScanner", "metaEnrichFailed stableId=" + stableId
          + " reason=" + e.getMessage());
      }
    }));
  }

  /** Lazily starts the single-thread scheduler that flushes enrichment batches. */
  private void ensureEnrichFlusher() {
    if (enrichFlusher != null) return;
    synchronized (this) {
      if (enrichFlusher != null) return;
      ScheduledExecutorService f = Executors.newSingleThreadScheduledExecutor();
      f.scheduleWithFixedDelay(this::flushEnrichBuffer,
          ENRICH_FLUSH_MS, ENRICH_FLUSH_MS, TimeUnit.MILLISECONDS);
      enrichFlusher = f;
    }
  }

  /** Emits all buffered enrichment results as a single "trackMetaEnrichedBatch". */
  private void flushEnrichBuffer() {
    JSArray batch = new JSArray();
    synchronized (enrichBuffer) {
      if (enrichBuffer.isEmpty()) return;
      for (JSObject o : enrichBuffer) batch.put(o);
      enrichBuffer.clear();
    }
    JSObject evt = new JSObject();
    evt.put("tracks", batch);
    notifyListeners("trackMetaEnrichedBatch", evt);
  }

  /**
   * On-demand enrichment. The UI calls this with ONLY the stableIds it is
   * currently showing (a visible page), so we never sweep the whole library.
   * Returns immediately; lookups + submission happen off the bridge thread.
   */
  @PluginMethod
  public void enrichTracks(PluginCall call) {
    JSArray ids = call.getArray("stableIds");
    final List<String> idList = new ArrayList<>();
    if (ids != null) {
      for (int i = 0; i < ids.length(); i++) {
        String s = ids.optString(i, null);
        if (s != null && !s.isEmpty()) idList.add(s);
      }
    }
    JSObject r = new JSObject();
    r.put("queued", idList.size());
    call.resolve(r);
    if (idList.isEmpty()) return;
    dbExecutor.execute(() -> {
      for (String id : idList) {
        if (enrichSubmitted.contains(id)) continue;
        try {
          TrackEntity e = dao().findByAnyId(id);
          if (e != null) enrichTrackMetadataAsync(e);
        } catch (Throwable ignored) {}
      }
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private File getAudioCacheDir() {
    File d = new File(getContext().getFilesDir(), "audio_cache");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  private String getAudioAlias() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "audio33" : "audioLegacy";
  }

  private boolean hasAudioPermission() {
    int state = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      ? ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_AUDIO)
      : ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE);
    return state == android.content.pm.PackageManager.PERMISSION_GRANTED
      || getPermissionState(getAudioAlias()) == PermissionState.GRANTED;
  }

  private TrackDao dao() { return AppDatabase.get(getContext()).trackDao(); }
  private PlaylistDao playlistDao() { return AppDatabase.get(getContext()).playlistDao(); }

  private String sha1(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] d = md.digest(value.getBytes());
      StringBuilder sb = new StringBuilder();
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) { return String.valueOf(value.hashCode()); }
  }

  private boolean safeEq(String a, String b) {
    String la = a == null ? "" : a.trim().toLowerCase();
    String lb = b == null ? "" : b.trim().toLowerCase();
    return la.equals(lb);
  }

  // Fast Hi-Res detection from MIME type alone – no file I/O required
  private boolean isHiResByMimeType(String mime) {
    if (mime == null) return false;
    return mime.contains("flac") || mime.contains("wav") || mime.contains("aiff")
        || mime.contains("alac") || mime.contains("dsd")  || mime.contains("x-wav")
        || mime.contains("x-flac");
  }

  private boolean isHiResByMetadata(AudioFormatInfo info, String mime) {
    if (info != null && info.bitDepth != null && info.sampleRate != null)
      return info.bitDepth >= 16 && info.sampleRate >= 44100;
    return isHiResByMimeType(mime);
  }

  // ── Permission methods ────────────────────────────────────────────────────

  @PluginMethod
  public void requestAudioPermissions(PluginCall call) {
    if (hasAudioPermission()) {
      JSObject r = new JSObject(); r.put("granted", true); call.resolve(r);
    } else {
      requestPermissionForAlias(getAudioAlias(), call, "permissionsCallback");
    }
  }

  @PermissionCallback
  public void permissionsCallback(PluginCall call) {
    JSObject r = new JSObject(); r.put("granted", hasAudioPermission()); call.resolve(r);
  }

  @PluginMethod
  public void checkPermissions(PluginCall call) {
    JSObject r = new JSObject(); r.put("granted", hasAudioPermission()); call.resolve(r);
  }

  // ── scanMusic ─────────────────────────────────────────────────────────────

  @PluginMethod
  public void scanMusic(PluginCall call) {
    if (!hasAudioPermission()) { call.reject("Permission not granted"); return; }
    // FIX: dispatched to dbExecutor – never blocks Capacitor thread
    dbExecutor.execute(() -> {
      try {
        JSArray files = scanMusicFromMediaStore();
        JSObject r = new JSObject();
        r.put("files", files);
        r.put("count", files.length());
        call.resolve(r);
      } catch (Exception e) {
        call.reject("Error scanning music: " + e.getMessage(), e);
      }
    });
  }

  // ── importAutomaticLibrary – THE MAIN FIX ─────────────────────────────────
  //
  // BEFORE: ran synchronously on the Capacitor bridge thread. With a library of
  // 500+ songs and getAudioFormatInfo() called per track (2 × file I/O each),
  // this blocked the entire WebView for 30–120 seconds → app felt frozen /
  // crashed with ANR.
  //
  // AFTER (this version):
  //   1. Dispatched to dbExecutor  → Capacitor thread is free immediately.
  //   2. scanMusicFromMediaStore() no longer calls getAudioFormatInfo() per song.
  //      isHiRes is inferred from MIME type (accurate for FLAC / WAV / AIFF).
  //   3. Deduplication uses O(1) HashMap lookups instead of O(N²) nested loops.
  //   4. A single batch upsertAll() replaces N individual upsert() calls inside
  //      the transaction.
  //   5. "scanProgress" events are emitted every 50 tracks so the UI stays live.
  //   6. Lazy enrichment (bitDepth / sampleRate via getAudioFormatInfo) runs in
  //      metaExecutor after the scan returns – completely transparent to the user.
  // ─────────────────────────────────────────────────────────────────────────

  @PluginMethod
  public void importAutomaticLibrary(PluginCall call) {
    if (!hasAudioPermission()) { call.reject("Permission not granted"); return; }

    dbExecutor.execute(() -> {
      try {
        migrateSharedPrefsToRoomIfNeeded();
        long now = System.currentTimeMillis() / 1000L;
        android.util.Log.i("MusicScanner", "scanStarted");

        // ── Phase 1: emit "scanning" progress ──────────────────────────────
        emitScanProgress("scanning", 0, 0);

        // ── Phase 2: query MediaStore (fast – no file I/O per track) ───────
        JSArray scanned = scanMusicFromMediaStore();
        int scannedCount = scanned.length();
        android.util.Log.i("MusicScanner", "scanFinished scannedCount=" + scannedCount);

        emitScanProgress("syncing", 0, scannedCount);

        // ── Phase 3: determine completeness ────────────────────────────────
        List<TrackEntity> existing = dao().getAll();
        int existingCount = existing.size();
        android.content.SharedPreferences prefs =
          getContext().getSharedPreferences(LIBRARY_PREFS, android.content.Context.MODE_PRIVATE);
        int lastFullScanCount = prefs.getInt(LAST_FULL_SCAN_COUNT_KEY, -1);

        String scanCompleteness = "complete";
        String completenessReason = "ok";
        if (existingCount > 0) {
          if (scannedCount == 0) {
            scanCompleteness = "partial"; completenessReason = "empty_scan";
          } else if (scannedCount < Math.ceil(existingCount * 0.5d)) {
            scanCompleteness = "partial"; completenessReason = "low_vs_existing";
          }
        } else {
          completenessReason = "first_scan";
        }
        if ("complete".equals(scanCompleteness) && lastFullScanCount > 0
            && scannedCount < Math.ceil(lastFullScanCount * 0.5d)) {
          scanCompleteness = "partial"; completenessReason = "low_vs_baseline";
        }
        if (scannedCount < 0) { scanCompleteness = "partial"; completenessReason = "inconsistent_scan"; }
        final String finalScanCompleteness = scanCompleteness;

        // ── Phase 4: O(1) lookup maps (FIX: was O(N²) nested loop) ─────────
        Map<String, TrackEntity> byStable      = new HashMap<>(existing.size() * 2);
        Map<String, TrackEntity> byFingerprint = new HashMap<>(existing.size() * 2);
        for (TrackEntity e : existing) {
          byStable.put(e.stableId, e);
          String fp = e.duration + "|" + e.size + "|" + e.dateModified;
          byFingerprint.putIfAbsent(fp, e);
        }

        Set<String> seen = new HashSet<>(scannedCount * 2);
        Set<Long>   consumedIds = new HashSet<>();
        AtomicInteger added    = new AtomicInteger(0);
        AtomicInteger updated  = new AtomicInteger(0);
        AtomicInteger preserved= new AtomicInteger(0);
        AtomicInteger missingCandidates = new AtomicInteger(0);
        AtomicInteger unavailableMarked = new AtomicInteger(0);

        List<TrackEntity> toUpsert = new ArrayList<>(scannedCount);

        // ── Phase 5: build upsert batch ────────────────────────────────────
        for (int i = 0; i < scanned.length(); i++) {
          JSObject obj;
          try { obj = new JSObject(scanned.getJSONObject(i).toString()); }
          catch (Exception ignored) { continue; }

          TrackEntity incoming = toEntity(obj, now);

          // O(1) primary lookup
          TrackEntity old = byStable.get(incoming.stableId);

          // O(1) fingerprint fallback
          if (old == null) {
            String fp = incoming.duration + "|" + incoming.size + "|" + incoming.dateModified;
            TrackEntity cand = byFingerprint.get(fp);
            if (cand != null && !consumedIds.contains(cand.id)
                && incoming.dateModified > 0
                && safeEq(cand.title,  incoming.title)
                && safeEq(cand.album,  incoming.album)
                && safeEq(cand.artist, incoming.artist)) {
              old = cand;
            }
          }

          if (old != null) {
            incoming.id        = old.id;
            incoming.createdAt = old.createdAt;
            // Preserve previously enriched format metadata
            if (old.sampleRate != null && (incoming.sampleRate == null || incoming.sampleRate == 0)) incoming.sampleRate = old.sampleRate;
            if (old.bitDepth   != null && (incoming.bitDepth   == null || incoming.bitDepth   == 0)) incoming.bitDepth   = old.bitDepth;
            if (old.bitrate    != null && (incoming.bitrate    == null || incoming.bitrate    == 0)) incoming.bitrate    = old.bitrate;
            if (old.channels   != null && (incoming.channels   == null || incoming.channels   == 0)) incoming.channels   = old.channels;
            consumedIds.add(old.id);
            // Fingerprint matching may preserve the row under a new stableId.
            // Mark both identities as seen so the old snapshot is not treated
            // as a missing second track later in this transaction.
            seen.add(old.stableId);
            updated.incrementAndGet();
          } else {
            incoming.createdAt = now;
            added.incrementAndGet();
          }

          incoming.updatedAt         = now;
          incoming.lastSeenAt        = now;
          incoming.missingCount      = 0;
          incoming.missingSince      = null;
          incoming.unavailable       = false;
          incoming.unavailableReason = null;
          incoming.scanCompleteness  = finalScanCompleteness;
          toUpsert.add(incoming);
          seen.add(incoming.stableId);

          // Emit granular progress every 50 tracks
          if ((i + 1) % 50 == 0 || i == scanned.length() - 1) {
            emitScanProgress("syncing", i + 1, scannedCount);
          }
        }

        // ── Phase 6: single batch transaction (FIX: was upsert-per-track) ──
        AppDatabase.get(getContext()).runInTransaction(() -> {
          if (!toUpsert.isEmpty()) dao().upsertAll(toUpsert);
          for (TrackEntity e : existing) {
            if (seen.contains(e.stableId)) continue;
            preserved.incrementAndGet();
            // Manual SAF imports are not expected in MediaStore and must never
            // be invalidated by an automatic scan. A suspicious/partial scan
            // also cannot provide negative evidence.
            if (!"complete".equals(finalScanCompleteness)
                || !"media-store".equals(e.sourceType)) continue;

            dao().markMissing(e.stableId, now, finalScanCompleteness);
            missingCandidates.incrementAndGet();
            final int nextMissingCount = e.missingCount + 1;
            final long missingSince = e.missingSince == null || e.missingSince <= 0
                ? now : e.missingSince;
            if (!e.unavailable
                && nextMissingCount >= MISSING_SCANS_BEFORE_UNAVAILABLE
                && now - missingSince >= MISSING_GRACE_SECONDS) {
              dao().markUnavailable(
                  e.stableId, true, "missing_from_repeated_complete_scans", now);
              unavailableMarked.incrementAndGet();
            }
          }
        });

        if ("complete".equals(scanCompleteness))
          prefs.edit().putInt(LAST_FULL_SCAN_COUNT_KEY, scannedCount).apply();

        int total = dao().countAll();
        android.util.Log.i("MusicScanner", "scanStats scannedCount=" + scannedCount
          + " existingCount=" + existingCount
          + " syncAdded=" + added.get()
          + " syncUpdated=" + updated.get()
          + " syncPreserved=" + preserved.get()
          + " missingCandidates=" + missingCandidates.get()
          + " unavailableMarked=" + unavailableMarked.get()
          + " scanCompleteness=" + scanCompleteness);

        emitScanProgress("complete", scannedCount, scannedCount);

        // ── Phase 7: NO bulk enrichment ────────────────────────────────────
        // The old code enqueued EVERY unenriched track here, causing thousands
        // of background file opens + bridge events that froze the app on large
        // libraries. Enrichment is now strictly on-demand: triggered per-track
        // on playback (getAudioFileUrlInternal) and per visible page via the
        // enrichTracks() plugin method. Hi-Res badges already work immediately
        // from the MIME type, so nothing user-visible is lost.

        JSObject result = new JSObject();
        result.put("count", total);
        result.put("added", added.get());
        result.put("updated", updated.get());
        result.put("preserved", preserved.get());
        result.put("missingCandidates", missingCandidates.get());
        result.put("unavailableMarked", unavailableMarked.get());
        result.put("scanCompleteness", scanCompleteness);
        result.put("scanCompletenessReason", completenessReason);
        result.put("success", true);
        call.resolve(result);

      } catch (Exception e) {
        android.util.Log.e("MusicScanner", "scanFailed", e);
        JSObject r = new JSObject();
        try { r.put("count", dao().countAll()); } catch (Exception ignored) { r.put("count", 0); }
        r.put("added", 0); r.put("updated", 0); r.put("preserved", 0);
        r.put("missingCandidates", 0); r.put("unavailableMarked", 0);
        r.put("scanCompleteness", "partial");
        r.put("scanCompletenessReason", "exception");
        r.put("success", false);
        r.put("error", e.getMessage());
        call.resolve(r);
      }
    });
  }

  private void emitScanProgress(String phase, int processed, int total) {
    JSObject evt = new JSObject();
    evt.put("phase", phase);
    evt.put("processed", processed);
    evt.put("total", total);
    notifyListeners("scanProgress", evt);
  }

  // ── Manual import via system file picker ──────────────────────────────────

  @PluginMethod
  public void pickManualAudioTracks(PluginCall call) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("audio/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, intent, "pickManualAudioTracksResult");
  }

  @ActivityCallback
  private void pickManualAudioTracksResult(PluginCall call, androidx.activity.result.ActivityResult result) {
    if (call == null) return;
    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) {
      dbExecutor.execute(() -> {
        try {
          JSObject out = new JSObject();
          out.put("count", dao().countAll()); out.put("changed", 0);
          out.put("records", new JSArray()); out.put("success", true);
          call.resolve(out);
        } catch (Exception e) { call.reject("Error finishing manual picker: " + e.getMessage(), e); }
      });
      return;
    }
    Intent data = result.getData();
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    } else if (data.getData() != null) {
      uris.add(data.getData());
    }
    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
    final int finalFlags = flags;
    dbExecutor.execute(() -> {
      try {
        JSArray imported = importManualUris(uris, finalFlags);
        JSObject out = new JSObject();
        out.put("count", dao().countAll()); out.put("changed", imported.length());
        out.put("records", imported); out.put("success", true);
        call.resolve(out);
      } catch (Exception e) {
        android.util.Log.e("MusicScanner", "manualImportFailed", e);
        call.reject("Error importing picked tracks: " + e.getMessage(), e);
      }
    });
  }

  @PluginMethod
  public void importManualTracks(PluginCall call) {
    JSArray items = call.getArray("items");
    if (items == null || items.length() == 0) { call.reject("items is required"); return; }
    dbExecutor.execute(() -> {
      try {
        migrateSharedPrefsToRoomIfNeeded();
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
          try {
            JSONObject o = items.getJSONObject(i);
            String cu = o.optString("contentUri", "");
            if (!cu.isEmpty()) uris.add(Uri.parse(cu));
          } catch (Exception ignored) {}
        }
        JSArray imported = importManualUris(uris, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        JSObject r = new JSObject();
        r.put("count", dao().countAll()); r.put("changed", imported.length());
        r.put("records", imported); r.put("success", true);
        call.resolve(r);
      } catch (Exception e) {
        android.util.Log.e("MusicScanner", "manualImportFailed", e);
        call.reject("Error importing manual tracks: " + e.getMessage(), e);
      }
    });
  }

  private JSArray importManualUris(List<Uri> uris, int flags) throws Exception {
    migrateSharedPrefsToRoomIfNeeded();
    long now = System.currentTimeMillis() / 1000L;
    JSArray imported = new JSArray();
    AppDatabase.get(getContext()).runInTransaction(() -> {
      for (Uri uri : uris) {
        if (uri == null) continue;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
          try {
            getContext().getContentResolver().takePersistableUriPermission(uri, flags);
          } catch (Exception pe) {
            try { getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
          }
        }
        JSObject track = buildTrackFromUri(uri);
        if (track == null) continue;
        TrackEntity e = toEntity(track, now);
        e.sourceType = "manual-uri"; e.mediaStoreId = null;
        e.scanCompleteness = "complete";
        e.createdAt = now; e.updatedAt = now; e.lastSeenAt = now;
        dao().upsert(e);
        imported.put(toJs(e));
      }
    });
    return imported;
  }

  // ── Library management ────────────────────────────────────────────────────

  @PluginMethod
  public void deleteTrackById(PluginCall call) {
    String id = call.getString("id");
    if (id == null || id.isEmpty()) { call.reject("id is required"); return; }
    dbExecutor.execute(() -> {
      try {
        migrateSharedPrefsToRoomIfNeeded();
        TrackEntity found = dao().findByAnyId(id);
        if (found != null) {
          AppDatabase.get(getContext()).runInTransaction(() -> {
            playlistDao().removeTrackEverywhere(found.stableId);
            dao().deleteByStableId(found.stableId);
          });
        }
        JSObject r = new JSObject(); r.put("success", true); r.put("count", dao().countAll());
        call.resolve(r);
      } catch (Exception e) { call.reject("Error deleting track: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void clearNativeLibrary(PluginCall call) {
    dbExecutor.execute(() -> {
      try {
        migrateSharedPrefsToRoomIfNeeded();
        AppDatabase.get(getContext()).runInTransaction(() -> {
          playlistDao().clearAllTrackReferences();
          dao().clearAll();
        });
        JSObject r = new JSObject(); r.put("success", true); r.put("count", 0);
        call.resolve(r);
      } catch (Exception e) { call.reject("Error clearing library: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void getLibraryPage(PluginCall call) {
    int page     = call.getInt("page", 1);
    int pageSize = call.getInt("pageSize", 100);
    String search  = call.getString("search", "");
    String sortBy  = call.getString("sortBy", "title");
    String sortDir = call.getString("sortDir", "asc");
    dbExecutor.execute(() -> {
      try {
        migrateSharedPrefsToRoomIfNeeded();
        long t0 = System.currentTimeMillis();
        int safePage     = Math.max(1, page);
        // Allow larger pages so the frontend can load a big library in a few
        // round-trips instead of dozens (50 calls for 5000 songs @100/page).
        int safePageSize = Math.max(1, Math.min(1000, pageSize));
        int offset       = (safePage - 1) * safePageSize;
        String q = search == null ? "" : search.trim().toLowerCase();
        boolean desc = "desc".equalsIgnoreCase(sortDir);
        List<TrackEntity> rows = desc
          ? dao().getPageDesc(q, sortBy, safePageSize, offset)
          : dao().getPageAsc(q, sortBy, safePageSize, offset);
        JSArray records = new JSArray();
        for (TrackEntity e : rows) records.put(toJs(e));
        JSObject r = new JSObject();
        r.put("page", safePage); r.put("pageSize", safePageSize);
        r.put("total", dao().countFiltered(q)); r.put("records", records);
        r.put("queryTimeMs", System.currentTimeMillis() - t0);
        call.resolve(r);
      } catch (Exception e) { call.reject("Error querying library: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void getTrackById(PluginCall call) {
    String id = call.getString("id");
    if (id == null || id.isEmpty()) { call.reject("id is required"); return; }
    dbExecutor.execute(() -> {
      try {
        JSObject track = findPersistedTrackById(id);
        if (track == null) { call.reject("Track not found"); return; }
        JSObject r = new JSObject(); r.put("track", track); call.resolve(r);
      } catch (Exception e) { call.reject("Error getting track: " + e.getMessage(), e); }
    });
  }

  // -- Native playlists ------------------------------------------------------

  private JSObject playlistToJs(PlaylistEntity playlist) {
    JSObject result = new JSObject();
    result.put("id", playlist.playlistId);
    result.put("name", playlist.name);
    result.put("createdAt", playlist.createdAt);
    result.put("updatedAt", playlist.updatedAt);
    result.put("coverUrl", playlist.coverUrl);
    JSArray trackIds = new JSArray();
    for (String trackId : playlistDao().getTrackIds(playlist.playlistId)) trackIds.put(trackId);
    result.put("trackIds", trackIds);
    return result;
  }

  @PluginMethod
  public void createPlaylist(PluginCall call) {
    String rawName = call.getString("name", "");
    final String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty()) { call.reject("playlist_name_required"); return; }
    dbExecutor.execute(() -> {
      try {
        long now = System.currentTimeMillis();
        PlaylistEntity playlist = new PlaylistEntity();
        playlist.playlistId = "playlist-" + UUID.randomUUID();
        playlist.name = name;
        playlist.createdAt = now;
        playlist.updatedAt = now;
        playlistDao().upsert(playlist);
        JSObject result = new JSObject();
        result.put("playlist", playlistToJs(playlist));
        call.resolve(result);
      } catch (Exception e) { call.reject("create_playlist_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void getPlaylists(PluginCall call) {
    dbExecutor.execute(() -> {
      try {
        JSArray playlists = new JSArray();
        for (PlaylistEntity playlist : playlistDao().getAll()) {
          playlists.put(playlistToJs(playlist));
        }
        JSObject result = new JSObject();
        result.put("playlists", playlists);
        call.resolve(result);
      } catch (Exception e) { call.reject("get_playlists_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void getPlaylist(PluginCall call) {
    String id = call.getString("id");
    if (id == null || id.isEmpty()) { call.reject("playlist_id_required"); return; }
    dbExecutor.execute(() -> {
      try {
        PlaylistEntity playlist = playlistDao().getById(id);
        JSObject result = new JSObject();
        result.put("playlist", playlist == null ? JSONObject.NULL : playlistToJs(playlist));
        call.resolve(result);
      } catch (Exception e) { call.reject("get_playlist_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void updatePlaylist(PluginCall call) {
    JSObject input = call.getObject("playlist");
    if (input == null) { call.reject("playlist_required"); return; }
    final String id = input.optString("id", "");
    final String name = input.optString("name", "").trim();
    final JSONArray trackIds = input.optJSONArray("trackIds");
    if (id.isEmpty() || name.isEmpty()) { call.reject("invalid_playlist"); return; }
    dbExecutor.execute(() -> {
      try {
        PlaylistEntity existingPlaylist = playlistDao().getById(id);
        if (existingPlaylist == null) { call.reject("playlist_not_found"); return; }
        long now = System.currentTimeMillis();
        existingPlaylist.name = name;
        existingPlaylist.updatedAt = now;
        existingPlaylist.coverUrl = input.optString("coverUrl", null);
        AppDatabase.get(getContext()).runInTransaction(() -> {
          playlistDao().upsert(existingPlaylist);
          playlistDao().clearPlaylistTracks(id);
          if (trackIds == null) return;
          for (int i = 0; i < trackIds.length(); i++) {
            String requestedTrackId = trackIds.optString(i, "");
            TrackEntity track = dao().findByAnyId(requestedTrackId);
            if (track == null) continue;
            PlaylistTrackEntity reference = new PlaylistTrackEntity();
            reference.playlistId = id;
            reference.trackStableId = track.stableId;
            reference.position = i;
            reference.addedAt = now;
            playlistDao().insertTrack(reference);
          }
        });
        JSObject result = new JSObject();
        result.put("playlist", playlistToJs(existingPlaylist));
        call.resolve(result);
      } catch (Exception e) { call.reject("update_playlist_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void deletePlaylist(PluginCall call) {
    String id = call.getString("id");
    if (id == null || id.isEmpty()) { call.reject("playlist_id_required"); return; }
    dbExecutor.execute(() -> {
      try {
        AppDatabase.get(getContext()).runInTransaction(() -> {
          playlistDao().clearPlaylistTracks(id);
          playlistDao().deletePlaylist(id);
        });
        JSObject result = new JSObject(); result.put("success", true); call.resolve(result);
      } catch (Exception e) { call.reject("delete_playlist_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void renamePlaylist(PluginCall call) {
    String id = call.getString("id");
    String rawName = call.getString("name", "");
    final String name = rawName == null ? "" : rawName.trim();
    if (id == null || id.isEmpty() || name.isEmpty()) { call.reject("invalid_playlist"); return; }
    dbExecutor.execute(() -> {
      try {
        playlistDao().rename(id, name, System.currentTimeMillis());
        JSObject result = new JSObject(); result.put("success", true); call.resolve(result);
      } catch (Exception e) { call.reject("rename_playlist_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void addTrackToPlaylist(PluginCall call) {
    String playlistId = call.getString("playlistId");
    String requestedTrackId = call.getString("trackId");
    if (playlistId == null || requestedTrackId == null) { call.reject("ids_required"); return; }
    dbExecutor.execute(() -> {
      try {
        PlaylistEntity playlist = playlistDao().getById(playlistId);
        TrackEntity track = dao().findByAnyId(requestedTrackId);
        if (playlist == null || track == null) { call.reject("playlist_or_track_not_found"); return; }
        Integer maxPosition = playlistDao().getMaxPosition(playlistId);
        PlaylistTrackEntity reference = new PlaylistTrackEntity();
        reference.playlistId = playlistId;
        reference.trackStableId = track.stableId;
        reference.position = maxPosition == null ? 0 : maxPosition + 1;
        reference.addedAt = System.currentTimeMillis();
        AppDatabase.get(getContext()).runInTransaction(() -> {
          playlistDao().insertTrack(reference);
          playlistDao().touch(playlistId, reference.addedAt);
        });
        JSObject result = new JSObject(); result.put("success", true); call.resolve(result);
      } catch (Exception e) { call.reject("add_playlist_track_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void removeTrackFromPlaylist(PluginCall call) {
    String playlistId = call.getString("playlistId");
    String requestedTrackId = call.getString("trackId");
    if (playlistId == null || requestedTrackId == null) { call.reject("ids_required"); return; }
    dbExecutor.execute(() -> {
      try {
        TrackEntity track = dao().findByAnyId(requestedTrackId);
        String stableId = track == null ? requestedTrackId : track.stableId;
        playlistDao().removeTrack(playlistId, stableId);
        playlistDao().touch(playlistId, System.currentTimeMillis());
        JSObject result = new JSObject(); result.put("success", true); call.resolve(result);
      } catch (Exception e) { call.reject("remove_playlist_track_failed: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void cleanupPlaylistReferences(PluginCall call) {
    String requestedTrackId = call.getString("trackId");
    if (requestedTrackId == null) { call.reject("track_id_required"); return; }
    dbExecutor.execute(() -> {
      try {
        TrackEntity track = dao().findByAnyId(requestedTrackId);
        playlistDao().removeTrackEverywhere(track == null ? requestedTrackId : track.stableId);
        JSObject result = new JSObject(); result.put("success", true); call.resolve(result);
      } catch (Exception e) { call.reject("cleanup_playlist_failed: " + e.getMessage(), e); }
    });
  }

  // ── Wake lock ─────────────────────────────────────────────────────────────

  @PluginMethod
  public void acquirePlaybackWakeLock(PluginCall call) {
    String trackId = call.getString("trackId", "unknown");
    try {
      if (playbackWakeLock == null) {
        PowerManager pm = (PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
        playbackWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EpicenterHiFi:Playback");
        playbackWakeLock.setReferenceCounted(false);
      }
      boolean wasHeld = playbackWakeLock.isHeld();
      if (!wasHeld) playbackWakeLock.acquire(6 * 60 * 60 * 1000L);
      JSObject r = new JSObject();
      r.put("held", playbackWakeLock.isHeld()); r.put("wasHeld", wasHeld);
      call.resolve(r);
    } catch (Exception e) { call.reject("Error acquiring wake lock: " + e.getMessage(), e); }
  }

  @PluginMethod
  public void releasePlaybackWakeLock(PluginCall call) {
    String reason  = call.getString("reason", "unknown");
    String trackId = call.getString("trackId", "unknown");
    try {
      boolean wasHeld = releaseWakeLockInternal(reason, trackId);
      JSObject r = new JSObject();
      r.put("held", playbackWakeLock != null && playbackWakeLock.isHeld()); r.put("wasHeld", wasHeld);
      call.resolve(r);
    } catch (Exception e) { call.reject("Error releasing wake lock: " + e.getMessage(), e); }
  }

  private boolean releaseWakeLockInternal(String reason, String trackId) {
    boolean wasHeld = playbackWakeLock != null && playbackWakeLock.isHeld();
    if (wasHeld) playbackWakeLock.release();
    android.util.Log.i("MusicScanner", "wakeLockRelease reason=" + reason + " trackId=" + trackId + " wasHeld=" + wasHeld);
    return wasHeld;
  }

  @Override
  protected void handleOnDestroy() {
    releaseWakeLockInternal("plugin-destroy", "unknown");
    shutdownAudioServer();
    metaExecutor.shutdownNow();
    ScheduledExecutorService f = enrichFlusher;
    if (f != null) {
      try { f.shutdownNow(); } catch (Throwable ignored) {}
      enrichFlusher = null;
    }
    super.handleOnDestroy();
  }

  private void shutdownAudioServer() {
    try { if (audioServerSocket != null && !audioServerSocket.isClosed()) audioServerSocket.close(); }
    catch (Exception ignored) {} finally { audioServerSocket = null; audioServerPort = -1; }
    try { audioExecutor.shutdownNow(); } catch (Exception ignored) {}
    try { dbExecutor.shutdownNow();    } catch (Exception ignored) {}
  }

  // ── Audio URL resolution ──────────────────────────────────────────────────

  @PluginMethod
  public void getAudioFileUrlById(PluginCall call) {
    String id = call.getString("id");
    if (id == null || id.isEmpty()) { call.reject("id is required"); return; }
    audioExecutor.execute(() -> {
      try {
        JSObject track = findPersistedTrackById(id);
        if (track == null) { call.reject("Track not found"); return; }
        String cu = track.optString("contentUri", null);
        if (cu == null || cu.isEmpty()) { call.reject("Track has no contentUri"); return; }
        JSObject r = getAudioFileUrlInternal(cu, id, track.optString("sourceVersionKey", id), track.has("size") ? track.optLong("size") : null, false);
        call.resolve(r);
      } catch (Exception e) { call.reject("Error getting audio by id: " + e.getMessage(), e); }
    });
  }

  @PluginMethod
  public void getAudioFileUrl(PluginCall call) {
    String contentUri      = call.getString("contentUri");
    String trackId         = call.getString("trackId");
    String sourceVersionKey= call.getString("sourceVersionKey");
    Long   expectedSize    = call.getLong("expectedSize");
    boolean allowStreaming = Boolean.TRUE.equals(call.getBoolean("allowStreaming"));
    if (contentUri == null || contentUri.isEmpty()) { call.reject("contentUri is required"); return; }
    if (trackId == null || trackId.isEmpty()) trackId = String.valueOf(System.currentTimeMillis());
    final String fTrackId = trackId;
    final String fSvk     = sourceVersionKey;
    audioExecutor.execute(() -> {
      try {
        long t0 = System.currentTimeMillis();
        JSObject r = getAudioFileUrlInternal(contentUri, fTrackId, fSvk, expectedSize, allowStreaming);
        r.put("audioResolveTimeMs", System.currentTimeMillis() - t0);
        call.resolve(r);
      } catch (Exception e) {
        android.util.Log.e("MusicScanner", "getAudioFileUrl error: " + e.getMessage());
        call.reject("Error getting audio: " + e.getMessage(), e);
      }
    });
  }

  private JSObject getAudioFileUrlInternal(String contentUri, String trackId,
      String sourceVersionKey, Long expectedSize, boolean allowStreaming) throws Exception {

    Uri uri = Uri.parse(contentUri);
    ContentResolver resolver = getContext().getContentResolver();
    String mimeType = resolver.getType(uri);
    if (mimeType == null) mimeType = "audio/mpeg";

    String ext = ".mp3";
    if (mimeType.contains("flac")) ext = ".flac";
    else if (mimeType.contains("wav")) ext = ".wav";
    else if (mimeType.contains("aiff")) ext = ".aiff";
    else if (mimeType.contains("m4a") || mimeType.contains("mp4")) ext = ".m4a";
    else if (mimeType.contains("ogg")) ext = ".ogg";

    long t0 = System.currentTimeMillis();
    TrackEntity linked = dao().getByStableId(trackId);
    if (linked == null) linked = dao().getByMediaStoreId(trackId);
    if (linked == null) linked = dao().findBySourceUri(contentUri);

    String scheme = uri.getScheme() != null ? uri.getScheme() : "unknown";
    boolean uriMatches = linked != null && linked.sourceUri != null && linked.sourceUri.equals(contentUri);
    boolean idMatches  = linked != null && trackId != null
      && (trackId.equals(linked.stableId) || trackId.equals(linked.mediaStoreId));

    // Fast path: linked cache hit
    if (uriMatches && linked.cachedFilePath != null && !linked.cachedFilePath.isEmpty()) {
      File cached = new File(linked.cachedFilePath);
      boolean ok = cached.exists() && cached.length() > 0
        && (expectedSize == null || expectedSize <= 0 || cached.length() == expectedSize);
      if (ok) {
        // Trigger lazy enrichment if not done yet
        if (linked.sampleRate == null || linked.sampleRate == 0) enrichTrackMetadataAsync(linked);
        JSObject r = new JSObject();
        r.put("filePath", cached.getAbsolutePath());
        r.put("resolvedUrl", cached.getAbsolutePath() + "?v=" + sha1(linked.sourceVersionKey != null ? linked.sourceVersionKey : contentUri + ":" + cached.length()));
        r.put("mimeType", mimeType); r.put("cached", true); r.put("cacheHit", true);
        r.put("fastPath", true); r.put("playbackSource", "cache-local-linked");
        r.put("sourceUriScheme", scheme); r.put("copyTimeMs", 0); r.put("copiedBytes", 0);
        r.put("bufferSize", AUDIO_BUFFER_SIZE); r.put("rejectReason", "");
        r.put("playbackResolveTimeMs", System.currentTimeMillis() - t0);
        return r;
      }
    }

    if (linked != null && sourceVersionKey == null) sourceVersionKey = linked.sourceVersionKey;
    String stable    = uriMatches && linked != null ? linked.stableId : sha1(trackId + "|" + contentUri);
    String cacheHash = sha1(stable + "|" + contentUri + "|" + (sourceVersionKey != null ? sourceVersionKey : trackId));

    // Streaming path
    if (allowStreaming && uriMatches && idMatches && linked != null) {
      ensureAudioServerStarted();
      if (audioServerSocket != null && !audioServerSocket.isClosed() && audioServerPort > 0) {
        String url = "http://127.0.0.1:" + audioServerPort + "/audio/"
          + URLEncoder.encode(linked.stableId, "UTF-8")
          + "?token=" + URLEncoder.encode(audioSessionToken, "UTF-8")
          + "&sourceUri=" + URLEncoder.encode(contentUri, "UTF-8")
          + "&v=" + cacheHash;
        JSObject r = new JSObject();
        r.put("streamUrl", url); r.put("resolvedUrl", url); r.put("mimeType", mimeType);
        r.put("cached", false); r.put("cacheHit", false); r.put("fastPath", true);
        r.put("playbackSource", "localhost-stream"); r.put("sourceUriScheme", scheme);
        r.put("cacheKey", cacheHash); r.put("resolvedStableId", linked.stableId);
        r.put("audioServerPort", audioServerPort); r.put("copyTimeMs", 0); r.put("copiedBytes", 0);
        r.put("bufferSize", AUDIO_BUFFER_SIZE); r.put("rejectReason", "streaming_no_cache");
        r.put("playbackResolveTimeMs", System.currentTimeMillis() - t0);
        return r;
      }
    }

    // Disk cache path: copy content:// → local file
    File cacheDir  = getAudioCacheDir();
    File outputFile = new File(cacheDir, "track_" + cacheHash + ext);
    File tempFile   = new File(cacheDir, "track_" + cacheHash + ext + ".tmp");

    if (outputFile.exists()) {
      boolean valid = outputFile.length() > 0
        && (expectedSize == null || expectedSize <= 0 || outputFile.length() == expectedSize);
      if (valid) {
        if (uriMatches && linked != null)
          dao().updateCachePaths(linked.stableId, outputFile.getAbsolutePath(), contentUri, System.currentTimeMillis() / 1000L);
        JSObject r = new JSObject();
        r.put("filePath", outputFile.getAbsolutePath());
        r.put("resolvedUrl", outputFile.getAbsolutePath() + "?v=" + cacheHash);
        r.put("mimeType", mimeType); r.put("cached", true); r.put("cacheHit", true);
        r.put("fastPath", true); r.put("playbackSource", "cache-local");
        r.put("sourceUriScheme", scheme); r.put("cacheKey", cacheHash);
        r.put("resolvedStableId", stable); r.put("copyTimeMs", 0); r.put("copiedBytes", 0);
        r.put("bufferSize", AUDIO_BUFFER_SIZE); r.put("rejectReason", "");
        r.put("playbackResolveTimeMs", System.currentTimeMillis() - t0);
        return r;
      }
      outputFile.delete();
    }

    // Copy from ContentResolver
    InputStream is = resolver.openInputStream(uri);
    if (is == null) { is = resolver.openInputStream(uri); }
    if (is == null) {
      if (uriMatches && linked != null) dao().markPlaybackError(linked.stableId, true, true, "open_input_stream_failed", System.currentTimeMillis() / 1000L);
      throw new Exception("Could not open audio file");
    }
    if (tempFile.exists()) tempFile.delete();
    long copyStart = System.currentTimeMillis();
    try (InputStream bi = new BufferedInputStream(is, AUDIO_BUFFER_SIZE);
         OutputStream bo = new BufferedOutputStream(new FileOutputStream(tempFile), AUDIO_BUFFER_SIZE)) {
      byte[] buf = new byte[AUDIO_BUFFER_SIZE]; int n; long total = 0;
      while ((n = bi.read(buf)) != -1) { bo.write(buf, 0, n); total += n; }
      long copyMs = System.currentTimeMillis() - copyStart;
      if (total <= 0 || !tempFile.renameTo(outputFile)) {
        tempFile.delete(); throw new Exception("Copy failed or empty");
      }
      if (uriMatches && linked != null) {
        dao().updateCachePaths(linked.stableId, outputFile.getAbsolutePath(), contentUri, System.currentTimeMillis() / 1000L);
        dao().markPlaybackError(linked.stableId, false, false, null, System.currentTimeMillis() / 1000L);
      }
      JSObject r = new JSObject();
      r.put("filePath", outputFile.getAbsolutePath());
      r.put("resolvedUrl", outputFile.getAbsolutePath() + "?v=" + cacheHash);
      r.put("mimeType", mimeType); r.put("size", total); r.put("cached", false);
      r.put("cacheHit", false); r.put("fastPath", false);
      r.put("playbackSource", "cache-local-copy"); r.put("sourceUriScheme", scheme);
      r.put("cacheKey", cacheHash); r.put("resolvedStableId", stable);
      r.put("copyTimeMs", copyMs); r.put("copiedBytes", total); r.put("bufferSize", AUDIO_BUFFER_SIZE);
      r.put("rejectReason", "copied_to_cache");
      r.put("playbackResolveTimeMs", System.currentTimeMillis() - t0);
      return r;
    }
  }

  @PluginMethod
  public void prepareAudioFileUrl(PluginCall call) {
    String contentUri = call.getString("contentUri");
    String trackId    = call.getString("trackId");
    if (contentUri == null || contentUri.isEmpty()) { call.reject("contentUri is required"); return; }
    if (trackId == null || trackId.isEmpty())       { call.reject("trackId is required");    return; }
    JSObject queued = new JSObject(); queued.put("queued", true); call.resolve(queued);
    final String fSvk = call.getString("sourceVersionKey");
    final Long   fSz  = call.getLong("expectedSize");
    final String fTid = trackId;
    audioExecutor.execute(() -> {
      try {
        JSObject r = getAudioFileUrlInternal(contentUri, fTid, fSvk, fSz, false);
        String fp = r.getString("filePath");
        if (fp != null && !fp.isEmpty()) {
          getContext().getSharedPreferences(LIBRARY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString("next_cached_file_path", fp).apply();
        }
      } catch (Exception e) {
        android.util.Log.w("MusicScanner", "prepareAudioFileUrlFailed trackId=" + fTid + " reason=" + e.getMessage());
      }
    });
  }

  // ── Local HTTP audio streaming server ─────────────────────────────────────

  private synchronized void ensureAudioServerStarted() throws Exception {
    if (audioServerSocket != null && !audioServerSocket.isClosed() && audioServerPort > 0) return;
    final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
    audioServerSocket = server;
    audioServerPort   = server.getLocalPort();
    Thread t = new Thread(() -> {
      // CRASH FIX: accept() MUST be inside this loop — it blocks until a client
      // connects, then we spawn exactly ONE handler thread per connection.
      // The old code spawned a new thread on every loop iteration WITHOUT
      // blocking → thousands of threads per second → OutOfMemoryError + ANR +
      // libc abort (this was ~93% of all crashes in Play vitals).
      while (!server.isClosed()) {
        try {
          Socket socket = server.accept();
          new Thread(() -> handleAudioHttpClient(socket), "EpicenterAudioClient").start();
        } catch (Exception e) {
          if (server.isClosed()) break;
          android.util.Log.w("MusicScanner", "audioServerAcceptFailed " + e.getMessage());
        }
      }
    }, "EpicenterAudioServer");
    t.setDaemon(true); t.start();
    android.util.Log.i("MusicScanner", "audioServerStarted port=" + audioServerPort);
  }

  private void handleAudioHttpClient(Socket socket) {
    try {
      socket.setSoTimeout(30000);
      BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      String requestLine = reader.readLine();
      if (requestLine == null) { writeHttpError(socket, 400, "Bad Request"); return; }
      if (requestLine.startsWith("OPTIONS ")) { writeHttpOptions(socket); return; }
      if (!requestLine.startsWith("GET "))    { writeHttpError(socket, 405, "Method Not Allowed"); return; }
      String[] parts = requestLine.split(" ");
      if (parts.length < 2) { writeHttpError(socket, 400, "Bad Request"); return; }
      String target = parts[1];
      String rangeHeader = null;
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        int c = line.indexOf(':');
        if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Range"))
          rangeHeader = line.substring(c + 1).trim();
      }
      int q = target.indexOf('?');
      String path = q >= 0 ? target.substring(0, q) : target;
      Map<String, String> qmap = parseQuery(q >= 0 ? target.substring(q + 1) : "");
      if (!audioSessionToken.equals(qmap.get("token"))) { writeHttpError(socket, 403, "Forbidden"); return; }
      if (!path.startsWith("/audio/"))                  { writeHttpError(socket, 404, "Not Found");  return; }
      String stableId = URLDecoder.decode(path.substring("/audio/".length()), "UTF-8");
      String requestedUri = qmap.get("sourceUri");
      TrackEntity entity = dao().getByStableId(stableId);
      if (entity == null || entity.sourceUri == null || requestedUri == null || !entity.sourceUri.equals(requestedUri)) {
        writeHttpError(socket, 404, "Not Found"); return;
      }
      Uri uri = Uri.parse(entity.sourceUri);
      ContentResolver resolver = getContext().getContentResolver();
      String mime = resolver.getType(uri);
      if (mime == null || mime.isEmpty()) mime = entity.mimeType != null ? entity.mimeType : "audio/mpeg";
      long size = entity.size > 0 ? entity.size : -1;
      HttpRange range;
      try { range = parseRange(rangeHeader, size); }
      catch (IllegalArgumentException re) { writeRangeNotSatisfiable(socket, size); return; }
      InputStream input = new BufferedInputStream(resolver.openInputStream(uri), AUDIO_BUFFER_SIZE);
      skipFully(input, range.start);
      PrintWriter writer = new PrintWriter(socket.getOutputStream(), false);
      writer.print("HTTP/1.1 " + (range.partial ? "206 Partial Content" : "200 OK") + "\r\n");
      writer.print("Content-Type: " + mime + "\r\n");
      writeCorsHeaders(writer);
      writer.print("Accept-Ranges: bytes\r\n");
      if (range.contentLength >= 0) writer.print("Content-Length: " + range.contentLength + "\r\n");
      if (range.partial && size > 0) writer.print("Content-Range: bytes " + range.start + "-" + range.end + "/" + size + "\r\n");
      writer.print("Connection: close\r\n\r\n");
      writer.flush();
      byte[] buf = new byte[AUDIO_BUFFER_SIZE]; long rem = range.contentLength; int n;
      OutputStream out = socket.getOutputStream();
      while ((range.contentLength < 0 || rem > 0)
          && (n = input.read(buf, 0, range.contentLength < 0 ? buf.length : (int)Math.min(buf.length, rem))) != -1) {
        out.write(buf, 0, n);
        if (range.contentLength >= 0) rem -= n;
      }
      out.flush(); input.close(); socket.close();
    } catch (Exception e) {
      android.util.Log.w("MusicScanner", "audioServerClientFailed " + e.getMessage());
    }
  }

  private static class HttpRange { long start, end, contentLength; boolean partial; }

  private HttpRange parseRange(String h, long size) {
    HttpRange r = new HttpRange();
    r.start = 0; r.end = size > 0 ? size - 1 : -1; r.contentLength = size > 0 ? size : -1; r.partial = false;
    if (h == null || h.trim().isEmpty()) return r;
    String t = h.trim();
    if (!t.startsWith("bytes=") || t.indexOf(',') >= 0 || size <= 0) throw new IllegalArgumentException("invalid_range");
    String spec = t.substring(6).trim();
    int dash = spec.indexOf('-');
    if (dash < 0) throw new IllegalArgumentException("invalid_range");
    String s = spec.substring(0, dash).trim(), e = spec.substring(dash + 1).trim();
    if (s.isEmpty() && e.isEmpty()) throw new IllegalArgumentException("invalid_range");
    try {
      if (s.isEmpty()) { long suf = Long.parseLong(e); r.start = suf >= size ? 0 : size - suf; r.end = size - 1; }
      else {
        r.start = Long.parseLong(s);
        if (r.start < 0 || r.start >= size) throw new IllegalArgumentException("invalid_range");
        r.end = e.isEmpty() ? size - 1 : Long.parseLong(e);
        if (r.end < r.start) throw new IllegalArgumentException("invalid_range");
        if (r.end >= size) r.end = size - 1;
      }
    } catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid_range", ex); }
    r.contentLength = r.end - r.start + 1;
    if (r.contentLength <= 0) throw new IllegalArgumentException("invalid_range");
    r.partial = true;
    return r;
  }

  private Map<String, String> parseQuery(String q) throws Exception {
    Map<String, String> m = new HashMap<>();
    if (q == null || q.isEmpty()) return m;
    for (String part : q.split("&")) {
      int eq = part.indexOf('=');
      m.put(URLDecoder.decode(eq >= 0 ? part.substring(0, eq) : part, "UTF-8"),
            URLDecoder.decode(eq >= 0 ? part.substring(eq + 1) : "", "UTF-8"));
    }
    return m;
  }

  private void skipFully(InputStream in, long bytes) throws Exception {
    long rem = bytes;
    while (rem > 0) { long sk = in.skip(rem); if (sk <= 0) { if (in.read() == -1) throw new Exception("skip failed"); sk = 1; } rem -= sk; }
  }

  private void writeRangeNotSatisfiable(Socket s, long size) throws Exception {
    PrintWriter w = new PrintWriter(s.getOutputStream(), false);
    w.print("HTTP/1.1 416 Range Not Satisfiable\r\n"); writeCorsHeaders(w);
    w.print("Accept-Ranges: bytes\r\nContent-Range: bytes */" + (size > 0 ? size : "*") + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    w.flush();
  }
  private void writeHttpOptions(Socket s) throws Exception {
    PrintWriter w = new PrintWriter(s.getOutputStream(), false);
    w.print("HTTP/1.1 204 No Content\r\n"); writeCorsHeaders(w);
    w.print("Content-Length: 0\r\nConnection: close\r\n\r\n"); w.flush();
  }
  private void writeCorsHeaders(PrintWriter w) {
    w.print("Access-Control-Allow-Origin: *\r\n");
    w.print("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
    w.print("Access-Control-Allow-Headers: Range, Origin, Accept, Content-Type\r\n");
    w.print("Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range, Content-Type\r\n");
  }
  private void writeHttpError(Socket s, int code, String msg) throws Exception {
    PrintWriter w = new PrintWriter(s.getOutputStream(), false);
    w.print("HTTP/1.1 " + code + " " + msg + "\r\n"); writeCorsHeaders(w);
    w.print("Content-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); w.flush();
  }

  // ── Album art ─────────────────────────────────────────────────────────────

  @PluginMethod
  public void getAlbumArt(PluginCall call) {
    String albumArtUri = call.getString("albumArtUri");
    if (albumArtUri == null || albumArtUri.isEmpty()) {
      JSObject r = new JSObject(); r.put("dataUrl", (String)null); call.resolve(r); return;
    }
    try {
      InputStream is = getContext().getContentResolver().openInputStream(Uri.parse(albumArtUri));
      if (is == null) { JSObject r = new JSObject(); r.put("dataUrl", (String)null); call.resolve(r); return; }
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[4096]; int n;
      while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
      is.close();
      String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
      JSObject r = new JSObject(); r.put("dataUrl", dataUrl); call.resolve(r);
    } catch (Exception e) {
      JSObject r = new JSObject(); r.put("dataUrl", (String)null); call.resolve(r);
    }
  }

  // ── Audio cache management ────────────────────────────────────────────────

  @PluginMethod
  public void clearAudioCache(PluginCall call) {
    try {
      File cacheDir = getAudioCacheDir();
      android.content.SharedPreferences prefs = getContext().getSharedPreferences(LIBRARY_PREFS, android.content.Context.MODE_PRIVATE);
      Set<String> protect = new HashSet<>();
      String ap = prefs.getString("active_cached_file_path", ""); if (!ap.isEmpty()) protect.add(ap);
      String np = prefs.getString("next_cached_file_path", "");   if (!np.isEmpty()) protect.add(np);
      String cp = call.getString("currentFilePath"); if (cp != null && !cp.isEmpty()) protect.add(cp);
      String nx = call.getString("nextFilePath");    if (nx != null && !nx.isEmpty()) protect.add(nx);
      for (TrackEntity e : dao().getAll()) {
        if (e.cachedFilePath != null && !e.cachedFilePath.isEmpty()) protect.add(e.cachedFilePath);
        if (e.localUri       != null && e.localUri.startsWith("/"))  protect.add(e.localUri);
      }
      if (cacheDir.exists()) {
        File[] files = cacheDir.listFiles();
        if (files != null) for (File f : files) if (!protect.contains(f.getAbsolutePath())) f.delete();
      }
      JSObject r = new JSObject(); r.put("success", true); call.resolve(r);
    } catch (Exception e) { call.reject("Error clearing cache: " + e.getMessage(), e); }
  }

  // ── MediaStore scan (NO per-track I/O) ───────────────────────────────────

  /**
   * FIX: getAudioFormatInfo() is REMOVED from this loop.
   *
   * Before: 500 songs × ~200ms per call = ~100 seconds of blocking I/O.
   * After:  Pure cursor iteration, zero file opens → completes in <500ms.
   *
   * isHiRes is determined from MIME type (FLAC, WAV, AIFF → always hi-res).
   * Exact bitDepth/sampleRate are filled later by enrichTrackMetadataAsync.
   */
  private JSArray scanMusicFromMediaStore() {
    JSArray result = new JSArray();
    ContentResolver resolver = getContext().getContentResolver();
    Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
      ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
      : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

    String[] projection = {
      MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE,
      MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_MODIFIED
    };

    Cursor cursor = resolver.query(collection, projection, null, null,
        MediaStore.Audio.Media.TITLE + " ASC");
    if (cursor == null || cursor.getCount() == 0) { if (cursor != null) cursor.close(); return result; }

    try {
      int colId   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
      int colName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
      int colTitle= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
      int colArt  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
      int colAlb  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
      int colDur  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
      int colSz   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
      int colMime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
      int colAlbId= cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
      int colDate = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);

      while (cursor.moveToNext()) {
        String mime = cursor.getString(colMime);
        if (mime == null || !mime.startsWith("audio/")) continue;

        long id     = cursor.getLong(colId);
        String name = cursor.getString(colName);
        String title= cursor.getString(colTitle);
        String artist=cursor.getString(colArt);
        String album = cursor.getString(colAlb);
        long dur    = cursor.getLong(colDur);
        long sz     = cursor.getLong(colSz);
        long albId  = cursor.getLong(colAlbId);
        long dateMod= cursor.getLong(colDate);

        Uri contentUri = ContentUris.withAppendedId(collection, id);
        Uri albumArtUri= ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albId);

        JSObject obj = new JSObject();
        obj.put("id",          String.valueOf(id));
        obj.put("mediaStoreId",String.valueOf(id));
        obj.put("name",        name   != null ? name   : "Unknown");
        obj.put("title",       title  != null && !title.isEmpty()  ? title  : (name != null ? name : "Unknown"));
        obj.put("artist",      artist != null && !artist.isEmpty() ? artist : "Unknown Artist");
        obj.put("album",       album  != null && !album.isEmpty()  ? album  : "Unknown Album");
        obj.put("duration",    dur / 1000);
        obj.put("size",        sz);
        obj.put("mimeType",    mime);
        obj.put("contentUri",  contentUri.toString());
        obj.put("albumArtUri", albumArtUri.toString());
        obj.put("albumId",     albId);
        obj.put("dateModified",dateMod);
        obj.put("sourceVersionKey", id + ":" + sz + ":" + dateMod);
        // bitDepth / sampleRate / bitrate / channels: intentionally absent.
        // Filled lazily by enrichTrackMetadataAsync after the scan completes.
        obj.put("isHiRes", isHiResByMimeType(mime));
        result.put(obj);
      }
    } finally { cursor.close(); }
    return result;
  }

  // ── Entity mapping ────────────────────────────────────────────────────────

  private String stableIdFrom(JSObject t) {
    String msId = t.optString("mediaStoreId", t.optString("id", ""));
    String src  = t.optString("contentUri",   t.optString("sourceUri", ""));
    return sha1(msId + "|" + src);
  }

  private TrackEntity toEntity(JSObject t, long now) {
    TrackEntity e = new TrackEntity();
    e.stableId       = stableIdFrom(t);
    e.mediaStoreId   = t.optString("mediaStoreId", t.optString("id", null));
    e.sourceUri      = t.optString("contentUri",   t.optString("sourceUri", null));
    e.title          = t.optString("title",  t.optString("name", "Unknown"));
    e.artist         = t.optString("artist", "Unknown Artist");
    e.album          = t.optString("album",  "Unknown Album");
    e.duration       = t.optLong("duration",     0L);
    e.size           = t.optLong("size",          0L);
    e.dateModified   = t.optLong("dateModified",  0L);
    e.mimeType       = t.optString("mimeType",    "audio/mpeg");
    String name = t.optString("name", "");
    int dot = name.lastIndexOf('.'); e.extension = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    e.sourceType     = "media-store";
    e.sourceVersionKey = t.optString("sourceVersionKey",
      (e.mediaStoreId != null ? e.mediaStoreId : "") + ":" + e.size + ":" + e.dateModified);
    if (t.has("albumId"))   e.albumId = t.optLong("albumId");
    e.albumArtUri = t.optString("albumArtUri", null);
    if (t.has("bitDepth")   && !t.isNull("bitDepth"))   e.bitDepth   = t.optInt("bitDepth");
    if (t.has("sampleRate") && !t.isNull("sampleRate")) e.sampleRate = t.optInt("sampleRate");
    if (t.has("bitrate")    && !t.isNull("bitrate"))    e.bitrate    = t.optInt("bitrate");
    if (t.has("channels")   && !t.isNull("channels"))   e.channels   = t.optInt("channels");
    if (t.has("isHiRes"))   e.isHiRes = t.optBoolean("isHiRes");
    e.unavailable       = t.optBoolean("unavailable", false);
    e.unavailableReason = t.optString("unavailableReason", null);
    e.lastSeenAt = t.optLong("lastSeenAt", now);
    long ms = t.optLong("missingSince", 0L); e.missingSince = ms > 0 ? ms : null;
    e.missingCount    = t.optInt("missingCount", 0);
    e.scanCompleteness= t.optString("scanCompleteness", "complete");
    e.createdAt = t.optLong("createdAt", now);
    e.updatedAt = now;
    return e;
  }

  private JSObject toJs(TrackEntity e) {
    JSObject o = new JSObject();
    o.put("id",         e.stableId); o.put("stableId",     e.stableId);
    o.put("mediaStoreId", e.mediaStoreId);
    o.put("contentUri", e.sourceUri); o.put("sourceUri", e.sourceUri);
    o.put("sourceType", e.sourceType);
    o.put("name",       e.title);    o.put("title",   e.title);
    o.put("artist",     e.artist);   o.put("album",   e.album);
    o.put("duration",   e.duration); o.put("size",    e.size);
    o.put("mimeType",   e.mimeType); o.put("dateModified", e.dateModified);
    o.put("sourceVersionKey", e.sourceVersionKey); o.put("albumId", e.albumId);
    if (e.albumArtUri != null && !e.albumArtUri.isEmpty()) o.put("albumArtUri", e.albumArtUri);
    else if (e.albumId != null && e.albumId > 0) o.put("albumArtUri", "content://media/external/audio/albumart/" + e.albumId);
    else o.put("albumArtUri", (String)null);
    if (e.bitDepth   != null) o.put("bitDepth",   e.bitDepth);
    if (e.sampleRate != null) o.put("sampleRate", e.sampleRate);
    if (e.bitrate    != null) o.put("bitrate",    e.bitrate);
    if (e.channels   != null) o.put("channels",   e.channels);
    if (e.isHiRes    != null) o.put("isHiRes",    e.isHiRes);
    o.put("unavailable", e.unavailable); o.put("unavailableReason", e.unavailableReason);
    o.put("lastSeenAt", e.lastSeenAt);
    o.put("missingSince", e.missingSince != null ? e.missingSince : 0);
    o.put("missingCount", e.missingCount); o.put("scanCompleteness", e.scanCompleteness);
    return o;
  }

  // ── Migration helper ──────────────────────────────────────────────────────

  private synchronized void migrateSharedPrefsToRoomIfNeeded() {
    android.content.SharedPreferences prefs = getContext().getSharedPreferences(LIBRARY_PREFS, android.content.Context.MODE_PRIVATE);
    if (prefs.getBoolean(ROOM_MIGRATED_KEY, false)) return;
    try {
      android.util.Log.i("MusicScanner", "migrationStarted");
      JSArray old = loadLegacySharedPrefsLibrary();
      long now = System.currentTimeMillis() / 1000L;
      List<TrackEntity> entities = new ArrayList<>();
      for (int i = 0; i < old.length(); i++) entities.add(toEntity(new JSObject(old.getJSONObject(i).toString()), now));
      AppDatabase db = AppDatabase.get(getContext());
      db.runInTransaction(() -> { if (!entities.isEmpty()) dao().upsertAll(entities); });
      int migrated = dao().countAll();
      if (old.length() > 0 && migrated < old.length()) { android.util.Log.e("MusicScanner", "migrationFailed"); return; }
      prefs.edit().putBoolean(ROOM_MIGRATED_KEY, true).apply();
      android.util.Log.i("MusicScanner", "migrationCompleted count=" + migrated);
    } catch (Exception e) { android.util.Log.e("MusicScanner", "migrationFailed", e); }
  }

  private JSArray loadLegacySharedPrefsLibrary() throws Exception {
    String raw = getContext().getSharedPreferences(LIBRARY_PREFS, android.content.Context.MODE_PRIVATE).getString(LIBRARY_KEY, "[]");
    return new JSArray(raw != null ? raw : "[]");
  }

  private JSObject findPersistedTrackById(String id) throws Exception {
    migrateSharedPrefsToRoomIfNeeded();
    TrackEntity e = dao().findByAnyId(id);
    if (e != null) return toJs(e);
    JSArray tracks = loadLegacySharedPrefsLibrary();
    for (int i = 0; i < tracks.length(); i++) {
      JSObject t = new JSObject(tracks.getJSONObject(i).toString());
      if (id.equals(t.optString("id"))) return t;
    }
    return null;
  }

  // ── Manual import: build track from single URI ────────────────────────────
  // For manual imports we DO call getAudioFormatInfo since it's one file at a
  // time triggered by explicit user action – the cost is acceptable.

  private JSObject buildTrackFromUri(Uri uri) {
    try {
      ContentResolver resolver = getContext().getContentResolver();
      String mime = resolver.getType(uri); if (mime == null) mime = "audio/mpeg";
      String displayName = "Unknown"; long size = 0L;
      Cursor c = resolver.query(uri, new String[]{ MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE }, null, null, null);
      if (c != null) { try { if (c.moveToFirst()) { int ni = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME); int si = c.getColumnIndex(MediaStore.MediaColumns.SIZE); if (ni >= 0) displayName = c.getString(ni); if (si >= 0) size = c.getLong(si); } } finally { c.close(); } }
      String title = displayName, artist = "Unknown Artist", album = "Unknown Album"; long durSec = 0L;
      MediaMetadataRetriever mmr = new MediaMetadataRetriever();
      try {
        mmr.setDataSource(getContext(), uri);
        String mt = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        String ma = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        String mal= mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        String md = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (mt != null && !mt.trim().isEmpty()) title = mt;
        if (ma != null && !ma.trim().isEmpty()) artist = ma;
        if (mal!= null && !mal.trim().isEmpty()) album = mal;
        if (md != null && !md.trim().isEmpty()) try { durSec = Long.parseLong(md) / 1000L; } catch (NumberFormatException ignored) {}
      } catch (Exception ignored) { } finally { try { mmr.release(); } catch (Exception ignored) {} }
      AudioFormatInfo fmt = getAudioFormatInfo(uri);
      JSObject obj = new JSObject();
      String id = sha1(uri.toString());
      obj.put("id", id); obj.put("name", displayName); obj.put("title", title);
      obj.put("artist", artist); obj.put("album", album); obj.put("duration", durSec);
      obj.put("size", size); obj.put("mimeType", mime); obj.put("contentUri", uri.toString());
      obj.put("dateModified", System.currentTimeMillis() / 1000L);
      obj.put("sourceVersionKey", id + ":" + size + ":" + obj.optLong("dateModified", 0L));
      if (fmt.bitDepth   != null) obj.put("bitDepth",   fmt.bitDepth);
      if (fmt.sampleRate != null) obj.put("sampleRate", fmt.sampleRate);
      if (fmt.bitrate    != null) obj.put("bitrate",    fmt.bitrate);
      if (fmt.channels   != null) obj.put("channels",   fmt.channels);
      obj.put("isHiRes", isHiResByMetadata(fmt, mime));
      return obj;
    } catch (Exception e) { return null; }
  }
}
