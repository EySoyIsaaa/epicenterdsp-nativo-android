/**
 * Epicenter Hi-Fi - Paginación server-side de "Canciones"
 *
 * Con bibliotecas grandes (5000+), materializar toda la biblioteca en memoria y
 * volver a agruparla/ordenarla en cada render hace que la vista se sienta
 * "increíblemente tardada". Este hook sirve el listado de canciones por páginas
 * directamente desde la DB nativa (SQLite/Room) vía MusicScanner.getLibraryPage,
 * que ya soporta búsqueda/orden/paginación indexada. La primera página aparece
 * casi al instante y las siguientes se piden bajo demanda ("cargar más").
 *
 * Los IDs producidos aquí (mapNativeTrack -> stableId) son idénticos a los que
 * useAudioQueue.loadLibrary mete en queue.library al arrancar, por lo que
 * reproducir/borrar/playlists siguen refiriéndose a la misma pista.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { musicLibraryDB, type StoredTrackMetadata } from '@/lib/musicLibraryDB';
import type { Track } from '@/hooks/useAudioQueue';
import { logger } from '@/lib/logger';

const DEFAULT_PAGE_SIZE = 200;

export type SongsSortBy = 'title' | 'artist' | 'dateModified';
export type SongsSortDir = 'asc' | 'desc';

function metadataToTrack(m: StoredTrackMetadata): Track {
  // Mismo criterio de carátula que el camino de bibliotecas grandes en
  // loadLibrary: preferir el data URL guardado y, si no, usar albumArtUri tal
  // cual (evita una llamada nativa por pista, que ralentizaría cada página).
  const coverUrl = m.coverBase64 || m.albumArtUri || undefined;
  return {
    id: m.id,
    title: m.title,
    artist: m.artist,
    duration: m.duration,
    fileName: m.fileName,
    fileType: m.fileType,
    fileSize: m.fileSize,
    coverUrl,
    bitDepth: m.bitDepth,
    sampleRate: m.sampleRate,
    bitrate: m.bitrate,
    isHiRes: m.isHiRes,
    sourceUri: m.sourceUri,
    sourceType: m.sourceType,
    albumId: m.albumId,
    albumArtUri: m.albumArtUri,
    mediaStoreId: m.mediaStoreId,
    dateModified: m.dateModified,
    sourceVersionKey: m.sourceVersionKey,
    unavailable: m.unavailable,
    lastValidatedAt: m.lastValidatedAt,
  };
}

export interface PaginatedSongs {
  songs: Track[];
  total: number;
  isLoading: boolean;
  hasMore: boolean;
  /** Cuántas canciones traería realmente el próximo "cargar más" (no las que
   *  faltan en total), para que el botón no prometa 4800 y traiga 200. */
  nextPageCount: number;
  loadMore: () => void;
  reload: () => void;
  removeLocally: (id: string) => void;
}

export function usePaginatedSongs(opts: {
  enabled: boolean;
  sortBy: SongsSortBy;
  sortDir: SongsSortDir;
  search?: string;
  /** Bump para forzar recarga desde la página 1 (tras un escaneo o un borrado). */
  refreshKey?: number;
  pageSize?: number;
}): PaginatedSongs {
  const {
    enabled,
    sortBy,
    sortDir,
    search = '',
    refreshKey = 0,
    pageSize = DEFAULT_PAGE_SIZE,
  } = opts;

  const [songs, setSongs] = useState<Track[]>([]);
  const [total, setTotal] = useState(0);
  const [isLoading, setIsLoading] = useState(false);

  const pageRef = useRef(0); // última página cargada (0 = ninguna)
  const loadingRef = useRef(false);
  const reqIdRef = useRef(0);
  const totalRef = useRef(0);
  const loadedRef = useRef(0); // filas cargadas

  const fetchPage = useCallback(
    async (targetPage: number, reset: boolean) => {
      if (!enabled) return;
      if (loadingRef.current) return;
      loadingRef.current = true;
      const myReq = ++reqIdRef.current;
      setIsLoading(true);
      try {
        const batch = await musicLibraryDB.getTrackMetadataPage({
          page: targetPage,
          pageSize,
          search,
          sortBy,
          sortDir,
        });
        if (myReq !== reqIdRef.current) return; // superada por una consulta más nueva
        const mapped = batch.records.map(metadataToTrack);
        totalRef.current = batch.total;
        setTotal(batch.total);
        pageRef.current = targetPage;
        if (reset) {
          loadedRef.current = mapped.length;
          setSongs(mapped);
        } else {
          setSongs((prev) => {
            const seen = new Set(prev.map((track) => track.id));
            const next = prev.slice();
            for (const track of mapped) {
              if (!seen.has(track.id)) next.push(track);
            }
            loadedRef.current = next.length;
            return next;
          });
        }
      } catch (error) {
        logger.error('[PaginatedSongs] Error loading page:', error);
      } finally {
        // Solo la consulta vigente puede tocar los flags compartidos; una
        // superada no debe reabrir el candado que ya tomó la nueva.
        if (myReq === reqIdRef.current) {
          setIsLoading(false);
          loadingRef.current = false;
        }
      }
    },
    [enabled, pageSize, search, sortBy, sortDir],
  );

  // Reset + página 1 cada vez que cambia la forma de la consulta.
  useEffect(() => {
    if (!enabled) {
      reqIdRef.current++;
      loadingRef.current = false;
      pageRef.current = 0;
      loadedRef.current = 0;
      setSongs([]);
      setTotal(0);
      return;
    }
    reqIdRef.current++; // invalida cualquier consulta en vuelo
    loadingRef.current = false;
    pageRef.current = 0;
    loadedRef.current = 0;
    void fetchPage(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, sortBy, sortDir, search, refreshKey]);

  const loadMore = useCallback(() => {
    if (loadingRef.current) return;
    if (loadedRef.current >= totalRef.current) return;
    void fetchPage(pageRef.current + 1, false);
  }, [fetchPage]);

  const reload = useCallback(() => {
    if (!enabled) return;
    reqIdRef.current++;
    loadingRef.current = false;
    pageRef.current = 0;
    loadedRef.current = 0;
    void fetchPage(1, true);
  }, [enabled, fetchPage]);

  const removeLocally = useCallback(
    (id: string) => {
      // Importante: NO mutar refs dentro de los updaters de estado. React puede
      // invocarlos dos veces (StrictMode en desarrollo) y el decremento de total
      // no es idempotente: totalRef quedaría por debajo del total real y la
      // guarda de loadMore (loadedRef >= totalRef) cortaría la paginación antes
      // de tiempo. Se calcula fuera, una sola vez.
      if (!songs.some((track) => track.id === id)) return;
      setSongs((prev) => prev.filter((track) => track.id !== id));
      loadedRef.current = Math.max(0, loadedRef.current - 1);
      totalRef.current = Math.max(0, totalRef.current - 1);
      setTotal(totalRef.current);
    },
    [songs],
  );

  const hasMore = songs.length < total;
  const nextPageCount = Math.max(0, Math.min(pageSize, total - songs.length));

  return { songs, total, isLoading, hasMore, nextPageCount, loadMore, reload, removeLocally };
}
