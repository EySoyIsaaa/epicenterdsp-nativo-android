import { useEffect, useRef, useState, useCallback } from 'react';
import { logger } from "@/lib/logger";

const ANDROID_FAST_STREAMING_ENABLED = true;

export interface AndroidAudioFileUrlResult {
  url: string;
  streamUrl?: string;
  fileUrl?: string;
  filePath?: string;
  playbackSource?: string;
  sourceUriScheme?: string;
  resolvedUrl?: string;
  mimeType?: string;
  cached?: boolean;
  cacheHit?: boolean;
  copyTimeMs?: number;
  copiedBytes?: number;
  playbackResolveTimeMs?: number;
  resolveDurationMs: number;
  fastPath?: boolean;
  rejectReason?: string;
  resolvedStableId?: string;
  cacheKey?: string;
}

export interface AndroidMusicFile {
  id: string;
  stableId?: string;
  mediaStoreId?: string;
  name: string;
  title?: string;
  artist?: string;
  album?: string;
  albumId?: number;
  contentUri: string;
  path?: string;
  size: number;
  mimeType: string;
  duration?: number;
  albumArtUri?: string;
  bitDepth?: number;
  sampleRate?: number;
  bitrate?: number;
  isHiRes?: boolean;
  dateModified?: number;
  sourceVersionKey?: string;
  sourceType?: 'file' | 'media-store' | 'manual-uri';
  unavailable?: boolean;
  unavailableReason?: string;
  missingCount?: number;
  missingSince?: number;
  lastSeenAt?: number;
  scanCompleteness?: 'partial' | 'complete';
}

export interface ScanProgress {
  isScanning: boolean;
  current: number;
  total: number;
  status: 'idle' | 'requesting-permission' | 'scanning' | 'syncing' | 'complete' | 'error';
}

export interface NativeLibraryPage {
  page: number;
  pageSize: number;
  total: number;
  records: AndroidMusicFile[];
}

/**
 * useAndroidMusicLibrary
 *
 * Hook for accessing Android native music library via MusicScanner plugin.
 *
 * FIX: now subscribes to "scanProgress" events emitted by the fixed
 * importAutomaticLibrary — progress updates flow to the UI in real time
 * while the scan runs in background (no more frozen app).
 *
 * FIX: subscribes to "trackMetaEnriched" events so the library updates
 * automatically when background enrichment completes bitDepth/sampleRate.
 */
export function useAndroidMusicLibrary() {
  const [musicFiles, setMusicFiles] = useState<AndroidMusicFile[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isAndroid, setIsAndroid] = useState(false);
  const [scanProgress, setScanProgress] = useState<ScanProgress>({
    isScanning: false,
    current: 0,
    total: 0,
    status: 'idle',
  });

  // Listeners registered with Capacitor — cleaned up on unmount
  const scanProgressListenerRef     = useRef<{ remove: () => Promise<void> } | null>(null);
  const trackMetaEnrichedListenerRef = useRef<{ remove: () => Promise<void> } | null>(null);
  const trackMetaBatchListenerRef    = useRef<{ remove: () => Promise<void> } | null>(null);

  useEffect(() => {
    const ua = navigator.userAgent.toLowerCase();
    setIsAndroid(/android/.test(ua));
  }, []);

  const getPlugin = useCallback(() => {
    if (typeof (window as any).Capacitor === 'undefined') return null;
    return (window as any).Capacitor.Plugins.MusicScanner || null;
  }, []);

  // Subscribe to native events once on mount
  useEffect(() => {
    const plugin = getPlugin();
    if (!plugin?.addListener) return;

    // Capacitor's plugin.addListener() on Android returns the handle synchronously,
    // NOT a Promise. Using .then() on it crashes at runtime with
    // "TypeError: N.addListener(...).then is not a function".
    // We call it synchronously and assign the handle immediately.

    let scanProgressHandle: { remove: () => any } | null = null;
    let trackMetaHandle: { remove: () => any } | null = null;
    let trackMetaBatchHandle: { remove: () => any } | null = null;

    try {
      scanProgressHandle = plugin.addListener(
        'scanProgress',
        (evt: { phase: string; processed: number; total: number }) => {
          const phase = evt.phase;
          if (phase === 'scanning' || phase === 'syncing') {
            setScanProgress({
              isScanning: true,
              current: evt.processed,
              total: evt.total,
              status: phase === 'scanning' ? 'scanning' : 'syncing',
            });
          } else if (phase === 'complete') {
            setScanProgress(prev => ({
              ...prev,
              isScanning: false,
              current: evt.total,
              total: evt.total,
              status: 'complete',
            }));
          }
        }
      );
      if (scanProgressHandle) scanProgressListenerRef.current = scanProgressHandle;
    } catch (err) {
      logger.warn('[MusicLibrary] Could not subscribe to scanProgress:', err);
    }

    try {
      trackMetaHandle = plugin.addListener(
        'trackMetaEnriched',
        (evt: {
          stableId: string; sampleRate?: number; bitDepth?: number;
          bitrate?: number; channels?: number; isHiRes?: boolean;
        }) => {
          setMusicFiles(prev => prev.map(f => {
            if (f.id !== evt.stableId && f.stableId !== evt.stableId) return f;
            return {
              ...f,
              sampleRate: evt.sampleRate ?? f.sampleRate,
              bitDepth:   evt.bitDepth   ?? f.bitDepth,
              bitrate:    evt.bitrate    ?? f.bitrate,
              isHiRes:    evt.isHiRes    ?? f.isHiRes,
            };
          }));
        }
      );
      if (trackMetaHandle) trackMetaEnrichedListenerRef.current = trackMetaHandle;
    } catch (err) {
      logger.warn('[MusicLibrary] Could not subscribe to trackMetaEnriched:', err);
    }

    // Batched enrichment: the native side coalesces many per-track results into
    // ONE event (~every 700ms). We apply them in a single pass (one map over the
    // list per batch) instead of one O(N) re-render per track — this is what
    // keeps the UI responsive while large libraries fill in their metadata.
    try {
      trackMetaBatchHandle = plugin.addListener(
        'trackMetaEnrichedBatch',
        (evt: {
          tracks?: Array<{
            stableId: string; sampleRate?: number; bitDepth?: number;
            bitrate?: number; channels?: number; isHiRes?: boolean;
          }>;
        }) => {
          const updates = evt?.tracks;
          if (!updates || updates.length === 0) return;
          const byId = new Map<string, (typeof updates)[number]>();
          for (const u of updates) {
            if (u?.stableId) byId.set(u.stableId, u);
          }
          if (byId.size === 0) return;
          setMusicFiles(prev => {
            let mutated = false;
            const next = prev.map(f => {
              const u = byId.get(f.stableId ?? '') ?? byId.get(f.id);
              if (!u) return f;
              mutated = true;
              return {
                ...f,
                sampleRate: u.sampleRate ?? f.sampleRate,
                bitDepth:   u.bitDepth   ?? f.bitDepth,
                bitrate:    u.bitrate    ?? f.bitrate,
                isHiRes:    u.isHiRes    ?? f.isHiRes,
              };
            });
            return mutated ? next : prev;
          });
        }
      );
      if (trackMetaBatchHandle) trackMetaBatchListenerRef.current = trackMetaBatchHandle;
    } catch (err) {
      logger.warn('[MusicLibrary] Could not subscribe to trackMetaEnrichedBatch:', err);
    }

    return () => {
      try { scanProgressListenerRef.current?.remove(); } catch (_) {}
      try { trackMetaEnrichedListenerRef.current?.remove(); } catch (_) {}
      try { trackMetaBatchListenerRef.current?.remove(); } catch (_) {}
      scanProgressListenerRef.current = null;
      trackMetaEnrichedListenerRef.current = null;
      trackMetaBatchListenerRef.current = null;
    };
  }, [getPlugin]);

  // On-demand enrichment: ask native to fill bitDepth/sampleRate for the given
  // tracks (e.g. the ones currently visible on screen). Bounded + deduped on the
  // native side, so it's safe to call repeatedly while scrolling.
  const enrichTracks = useCallback(async (stableIds: string[]): Promise<void> => {
    try {
      const plugin = getPlugin();
      if (!plugin?.enrichTracks || !stableIds || stableIds.length === 0) return;
      await plugin.enrichTracks({ stableIds });
    } catch (err) {
      logger.warn('[MusicLibrary] enrichTracks failed:', err);
    }
  }, [getPlugin]);


  // ── Scan + persist library ────────────────────────────────────────────────
  const scanMusicLibrary = async (): Promise<AndroidMusicFile[]> => {
    if (!isAndroid) {
      const msg = 'Esta funcionalidad solo está disponible en Android';
      setError(msg);
      throw new Error(msg);
    }

    setIsLoading(true);
    setError(null);
    setScanProgress({ isScanning: true, current: 0, total: 0, status: 'scanning' });

    try {
      const MusicScanner = getPlugin();
      if (!MusicScanner) throw new Error('Plugin MusicScanner no encontrado');

      logger.info('🎵 Solicitando permisos...');
      const permResult = await MusicScanner.requestAudioPermissions();
      if (!permResult.granted) throw new Error('Permisos no concedidos');

      logger.info('🎵 Iniciando importAutomaticLibrary en background...');
      // FIX: this call returns quickly now (dispatched to dbExecutor on the
      // native side). Progress is delivered via "scanProgress" events above.
      await MusicScanner.importAutomaticLibrary();

      logger.info('🎵 Scan complete, loading pages from Room...');
      // Larger pages → far fewer bridge round-trips on big libraries (native
      // caps pageSize at 1000). 5000 songs = ~10 calls instead of ~50.
      const PAGE = 500;
      const first = await MusicScanner.getLibraryPage({
        page: 1, pageSize: PAGE, search: '', sortBy: 'title', sortDir: 'asc',
      });
      const total  = Number(first?.total || 0);
      const pages  = Math.max(1, Math.ceil(total / PAGE));
      const records: any[] = [...(first?.records || [])];

      for (let page = 2; page <= pages; page++) {
        const next = await MusicScanner.getLibraryPage({
          page, pageSize: PAGE, search: '', sortBy: 'title', sortDir: 'asc',
        });
        records.push(...(next?.records || []));
      }

      const files: AndroidMusicFile[] = records.map((file: any) => ({
        id:             file.id || String(Date.now() + Math.random()),
        stableId:       file.stableId,
        mediaStoreId:   file.mediaStoreId,
        name:           file.name      || 'Unknown',
        title:          file.title     || file.name || 'Unknown',
        artist:         file.artist    || 'Unknown Artist',
        album:          file.album     || 'Unknown Album',
        contentUri:     file.contentUri || '',
        path:           file.contentUri || '',
        size:           file.size       || 0,
        mimeType:       file.mimeType   || 'audio/mpeg',
        duration:       file.duration   || 0,
        albumArtUri:    file.albumArtUri || '',
        albumId:        typeof file.albumId      === 'number' ? file.albumId      : undefined,
        bitDepth:       typeof file.bitDepth     === 'number' ? file.bitDepth     : undefined,
        sampleRate:     typeof file.sampleRate   === 'number' ? file.sampleRate   : undefined,
        bitrate:        typeof file.bitrate      === 'number' ? file.bitrate      : undefined,
        isHiRes:        typeof file.isHiRes      === 'boolean'? file.isHiRes      : undefined,
        dateModified:   typeof file.dateModified === 'number' ? file.dateModified : undefined,
        sourceVersionKey: typeof file.sourceVersionKey === 'string' ? file.sourceVersionKey : undefined,
        sourceType:     file.sourceType === 'manual-uri' ? 'manual-uri' : 'media-store',
        unavailable:    !!file.unavailable,
        unavailableReason: typeof file.unavailableReason === 'string' ? file.unavailableReason : undefined,
        missingCount:    typeof file.missingCount === 'number' ? file.missingCount : 0,
        missingSince:    typeof file.missingSince === 'number' ? file.missingSince : undefined,
        lastSeenAt:      typeof file.lastSeenAt === 'number' ? file.lastSeenAt : undefined,
        scanCompleteness: file.scanCompleteness === 'partial' ? 'partial' : 'complete',
      }));

      logger.info('🎵 Archivos cargados:', files.length);
      setMusicFiles(files);
      setScanProgress({ isScanning: false, current: files.length, total: files.length, status: 'complete' });
      return files;

    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Error desconocido';
      logger.error('❌ Error scanning music library:', err);
      setError(msg);
      setScanProgress({ isScanning: false, current: 0, total: 0, status: 'error' });
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const getLibraryPage = async (params?: {
    page?: number; pageSize?: number; search?: string;
    sortBy?: 'title' | 'artist' | 'dateModified'; sortDir?: 'asc' | 'desc';
  }): Promise<NativeLibraryPage> => {
    const plugin = getPlugin();
    if (!plugin) return { page: 1, pageSize: 0, total: 0, records: [] };
    const result = await plugin.getLibraryPage({
      page:     params?.page     ?? 1,
      pageSize: params?.pageSize ?? 100,
      search:   params?.search   ?? '',
      sortBy:   params?.sortBy   ?? 'title',
      sortDir:  params?.sortDir  ?? 'asc',
    });
    return {
      page:     result?.page     ?? 1,
      pageSize: result?.pageSize ?? 0,
      total:    result?.total    ?? 0,
      records:  (result?.records ?? []) as AndroidMusicFile[],
    };
  };

  const requestPermissions = async (): Promise<boolean> => {
    setScanProgress({ isScanning: false, current: 0, total: 0, status: 'requesting-permission' });
    try {
      const plugin = getPlugin();
      if (!plugin) { setScanProgress({ isScanning: false, current: 0, total: 0, status: 'error' }); return false; }
      const result = await plugin.requestAudioPermissions();
      const granted = !!result?.granted;
      setScanProgress({ isScanning: false, current: 0, total: 0, status: granted ? 'idle' : 'error' });
      return granted;
    } catch (err) {
      logger.error('Error requesting permissions:', err);
      setScanProgress({ isScanning: false, current: 0, total: 0, status: 'error' });
      return false;
    }
  };

  const checkPermissions = async (): Promise<boolean> => {
    try {
      const plugin = getPlugin();
      if (!plugin) return false;
      const result = await plugin.checkPermissions();
      return !!result?.granted;
    } catch (err) {
      logger.error('Error checking permissions:', err);
      return false;
    }
  };

  const getAudioFileUrlResult = async (
    contentUri: string,
    trackId: string,
    options?: { expectedSize?: number; sourceVersionKey?: string; allowStreaming?: boolean },
  ): Promise<AndroidAudioFileUrlResult | null> => {
    const t0 = performance.now();
    try {
      const plugin = getPlugin();
      if (!plugin) { logger.error('Plugin MusicScanner no disponible'); return null; }
      const allowStreaming  = options?.allowStreaming ?? ANDROID_FAST_STREAMING_ENABLED;
      const sourceUriScheme = contentUri.includes(':') ? contentUri.split(':', 1)[0] : 'unknown';
      const result = await plugin.getAudioFileUrl({
        contentUri, trackId,
        expectedSize:    options?.expectedSize,
        sourceVersionKey: options?.sourceVersionKey,
        allowStreaming,
      });
      const resolveDurationMs  = performance.now() - t0;
      const nativePlaybackSource = result?.playbackSource || (result?.streamUrl ? 'localhost-stream' : result?.filePath ? 'cache-local' : 'unknown');

      if (allowStreaming && result?.streamUrl) {
        return { ...result, url: result.streamUrl, streamUrl: result.streamUrl,
          cacheHit: result.cacheHit ?? result.cached ?? false, copyTimeMs: result.copyTimeMs ?? 0,
          resolveDurationMs, playbackSource: nativePlaybackSource, sourceUriScheme: result?.sourceUriScheme || sourceUriScheme };
      }
      if (result?.filePath) {
        const baseUrl  = (window as any).Capacitor.convertFileSrc(result.filePath);
        const cacheBuster = typeof result?.resolvedUrl === 'string' && result.resolvedUrl.includes('?')
          ? result.resolvedUrl.substring(result.resolvedUrl.indexOf('?')) : '';
        return { ...result, url: `${baseUrl}${cacheBuster}`, fileUrl: `${baseUrl}${cacheBuster}`,
          filePath: result.filePath, cacheHit: result.cacheHit ?? result.cached ?? false,
          copyTimeMs: result.copyTimeMs ?? 0, resolveDurationMs,
          playbackSource: nativePlaybackSource, sourceUriScheme: result?.sourceUriScheme || sourceUriScheme };
      }
      return null;
    } catch (err) {
      logger.error('Error obteniendo URL de archivo:', err);
      return null;
    }
  };

  const getAudioFileUrl = async (
    contentUri: string, trackId: string,
    options?: { expectedSize?: number; sourceVersionKey?: string; allowStreaming?: boolean },
  ): Promise<string | null> => {
    const result = await getAudioFileUrlResult(contentUri, trackId, options);
    return result?.url ?? null;
  };

  const prepareAudioFileUrl = async (
    contentUri: string, trackId: string,
    options?: { expectedSize?: number; sourceVersionKey?: string },
  ): Promise<boolean> => {
    try {
      const plugin = getPlugin();
      if (!plugin?.prepareAudioFileUrl) return false;
      await plugin.prepareAudioFileUrl({ contentUri, trackId, expectedSize: options?.expectedSize, sourceVersionKey: options?.sourceVersionKey });
      return true;
    } catch (err) { logger.warn('Error preparando URL de audio:', err); return false; }
  };

  const clearAudioCache = async (): Promise<boolean> => {
    try {
      const plugin = getPlugin();
      if (!plugin) return false;
      const result = await plugin.clearAudioCache();
      return result?.success || false;
    } catch (err) { logger.error('Error limpiando caché:', err); return false; }
  };

  const getAlbumArt = async (albumArtUri: string): Promise<string | null> => {
    try {
      const plugin = getPlugin();
      if (!plugin || !albumArtUri) return null;
      const result = await plugin.getAlbumArt({ albumArtUri });
      return result?.dataUrl || null;
    } catch (err) { logger.warn('No se pudo obtener carátula:', err); return null; }
  };

  return {
    musicFiles,
    isLoading,
    error,
    isAndroid,
    scanProgress,
    scanMusicLibrary,
    requestPermissions,
    checkPermissions,
    getAudioFileUrl,
    getAudioFileUrlResult,
    prepareAudioFileUrl,
    getLibraryPage,
    getAlbumArt,
    clearAudioCache,
    enrichTracks,
  };
}
