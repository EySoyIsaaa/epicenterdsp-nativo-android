/**
 * Epicenter Hi-Fi - Apple Music Style Player
 * Diseño minimalista, monocromático y premium
 * Con biblioteca de música organizada, playlists y cola interactiva
 *
 * v1.1.3 - Splash screen + Last track memory
 */

import { useState, useCallback, useEffect, useMemo, useRef } from "react";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  useIntegratedAudioProcessor,
  type EpicenterMode,
  type StreamingParams,
} from "@/hooks/useIntegratedAudioProcessor";
import {
  analyzeSpectrumAndSelectPreset,
  applyPresetSmooth,
  suggestDspFromScores,
} from "@/audio/autoPresetSelector";
import { useAudioQueue, type Track } from "@/hooks/useAudioQueue";
import { usePlaylists, type Playlist } from "@/hooks/usePlaylists";
import { usePresetPersistence } from "@/hooks/usePresetPersistence";
import { useMediaSession } from "@/hooks/useMediaSession";
import { useMediaNotification } from "@/hooks/useMediaNotification";
import { useNotificationPermission } from "@/hooks/useNotificationPermission";
import { useCrossfade } from "@/hooks/useCrossfade";
import { useLastTrack } from "@/hooks/useLastTrack";
import { useTheme } from "@/contexts/ThemeContext";
import { BottomNavigation } from "@/components/BottomNavigation";
import { PremiumMiniPlayer } from "@/components/PremiumMiniPlayer";
import {
  AddSongsToPlaylistModal,
  AddToPlaylistModal,
  DeletePlaylistModal,
  DuplicatesModal,
  OnboardingModal,
  PlaylistContextMenu,
  PlaylistNameModal,
  TrackContextMenu,
} from "@/components/home/HomeOverlays";
import { HomeDspView } from "@/components/home/HomeDspView";
import { HomeEqView } from "@/components/home/HomeEqView";
import { HomeFxView } from "@/components/home/HomeFxView";
import { HomeImportProgressOverlay } from "@/components/home/HomeImportProgressOverlay";
import { LibraryPreparingOverlay } from "@/components/home/LibraryPreparingOverlay";
import { ReviewPrompt } from "@/components/ReviewPrompt";
import { useReviewPrompt } from "@/hooks/useReviewPrompt";
import { HomeLibraryView } from "@/components/home/HomeLibraryView";
import { usePaginatedSongs } from "@/hooks/usePaginatedSongs";
import {
  autoTuneEpicenter,
  autoTuneEq,
  suggestPreampDb,
  type SpectrumProfile,
} from "@/audio/autoTuneFromSpectrum";
import { HomePlayerView } from "@/components/home/HomePlayerView";
import { HomeSearchView } from "@/components/home/HomeSearchView";
import { HomeSettingsView } from "@/components/home/HomeSettingsView";
import {
  type DspParamConfig,
  type HomeLibraryView as LibraryView,
  type HomeTabType as TabType,
} from "@/components/home/types";
import { useLanguage } from "@/hooks/useLanguage";
import {
  useAndroidMusicLibrary,
  type AndroidAudioFileUrlResult,
  type AndroidMusicFile,
} from "@/hooks/useAndroidMusicLibrary";
import { hiresAudioBadgeUrl, hiresLogoUrl } from "@/lib/assetUrls";
import { toast } from "sonner";

type HomeNavigationSnapshot = {
  activeTab: TabType;
  libraryView: LibraryView;
  showQueue: boolean;
  showEqAutoModal: boolean;
  showDspAutoModal: boolean;
  showCreatePlaylist: boolean;
  showRenamePlaylist: boolean;
  showDeletePlaylist: boolean;
  showAddToPlaylist: boolean;
  showAddSongsToPlaylist: boolean;
  showOnboarding: boolean;
  onboardingStep: number;
  selectedPlaylistId: string | null;
  contextMenuOpen: boolean;
  playlistMenuOpen: boolean;
  duplicatesModalOpen: boolean;
};

type ResolvedTrackPlaybackSource = {
  source: File | string;
  resolveResult?: AndroidAudioFileUrlResult;
};

const HOME_NAVIGATION_STATE_KEY = "__epicenterHomeNav";

const clampDspParam = (key: keyof StreamingParams, value: number): number => {
  switch (key) {
    case "sweepFreq":
      return Math.max(27, Math.min(63, value));
    case "width":
    case "intensity":
    case "balance":
    case "volume":
      return Math.max(0, Math.min(100, value));
    default:
      return value;
  }
};

const ANDROID_PREFETCH_ENABLED = true;
const LIBRARY_STABILIZATION_BASE_MS = 2500;
const LIBRARY_STABILIZATION_PER_TRACK_MS = 350;
const LIBRARY_STABILIZATION_MAX_MS = 18000;


const estimateLibraryStabilizationDelayMs = (importedCount: number): number => {
  if (importedCount <= 0) return 0;

  const baseMs = 5000;
  const perSongMs = 400;
  const maxMs = 90000;
  return Math.min(baseMs + importedCount * perSongMs, maxMs);
};

const resolveStabilizationCount = (counts: Array<number | undefined>): number => {
  const validCounts = counts
    .filter((value): value is number => typeof value === "number" && Number.isFinite(value) && value > 0)
    .map((value) => Math.floor(value));

  if (validCounts.length === 0) return 0;
  return Math.max(...validCounts);
};

const clampDspParams = (params: StreamingParams): StreamingParams => ({
  sweepFreq: clampDspParam("sweepFreq", params.sweepFreq),
  width: clampDspParam("width", params.width),
  intensity: clampDspParam("intensity", params.intensity),
  balance: clampDspParam("balance", params.balance),
  volume: clampDspParam("volume", params.volume),
});

export default function Home() {
  const audioProcessor = useIntegratedAudioProcessor();
  const queue = useAudioQueue();
  const presetManager = usePresetPersistence();
  const mediaSession = useMediaSession();
  const mediaNotification = useMediaNotification();
  const crossfade = useCrossfade();
  const lastTrack = useLastTrack();
  const { t, language, setLanguage } = useLanguage();
  const { theme, toggleTheme, switchable } = useTheme();
  const playlistManager = usePlaylists(queue.library);
  const androidMusicLibrary = useAndroidMusicLibrary();

  // Solicitar permiso de notificaciones en Android 13+
  useNotificationPermission();

  const [activeTab, setActiveTab] = useState<TabType>("player");
  const [libraryView, setLibraryView] = useState<LibraryView>("main");
  const [songSort, setSongSort] = useState<"default" | "name" | "artist">(
    "default",
  );
  const [visibleSongsCount, setVisibleSongsCount] = useState(250);
  // Caps how many artists render at once. The Artists view inlines every track
  // of every artist, so without a cap a 5000-song library mounts thousands of
  // rows the instant the tab opens. "Load more" reveals the rest on demand.
  const [visibleArtistsCount, setVisibleArtistsCount] = useState(30);
  const [showQueue, setShowQueue] = useState(false);
  const [isLibraryStabilizing, setIsLibraryStabilizing] = useState(false);
  // Recordatorio de reseña. Se habilita solo con la app "en calma": nunca sobre
  // la pantalla de preparando biblioteca ni durante una importación.
  const reviewPrompt = useReviewPrompt(
    !isLibraryStabilizing && !queue.importProgress.isImporting,
  );
  // True only once the library has REALLY settled after a scan (not when the
  // native DB scan emits 'complete', which is well before the frontend finishes
  // ingesting tracks). Drives the success checkmark.
  const [libraryPrepDone, setLibraryPrepDone] = useState(false);
  const [pendingTrack, setPendingTrack] = useState<Track | null>(null);
  const [nowPlayingTrack, setNowPlayingTrack] = useState<Track | null>(null);
  const [activeAudioSource, setActiveAudioSource] = useState("");
  const [globalSearchQuery, setGlobalSearchQuery] = useState("");
  const [dspParams, setDspParams] = useState<StreamingParams>({
    sweepFreq: 45,
    width: 50,
    intensity: 100,
    balance: 100,
    volume: 100,
  });
  const epicenterEnabled = audioProcessor.epicenterEnabled;
  // Motor de graves activo. Solo el processor nativo de Android lo expone; en
  // otras plataformas cae en "car", que es el comportamiento histórico.
  const epicenterMode = audioProcessor.epicenterMode ?? "car";
  const [eqAutoEnabled, setEqAutoEnabled] = useState(false);
  const [dspAutoEnabled, setDspAutoEnabled] = useState(false);
  const [showEqAutoModal, setShowEqAutoModal] = useState(false);
  const [showDspAutoModal, setShowDspAutoModal] = useState(false);
  const [contextMenu, setContextMenu] = useState<{
    track: Track;
    x: number;
    y: number;
  } | null>(null);
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);

  // Playlist states
  const [selectedPlaylist, setSelectedPlaylist] = useState<Playlist | null>(
    null,
  );
  const [showCreatePlaylist, setShowCreatePlaylist] = useState(false);
  const [showRenamePlaylist, setShowRenamePlaylist] = useState(false);
  const [showDeletePlaylist, setShowDeletePlaylist] = useState(false);
  const [showAddToPlaylist, setShowAddToPlaylist] = useState<Track | null>(
    null,
  );
  const [showAddSongsToPlaylist, setShowAddSongsToPlaylist] = useState(false); // New: modal to add songs from library
  const [showDuplicatesModal, setShowDuplicatesModal] = useState<string[]>([]);
  const stabilizationTimerRef = useRef<number | null>(null);

  const clearLibraryStabilizationTimer = useCallback(() => {
    if (stabilizationTimerRef.current !== null) {
      window.clearTimeout(stabilizationTimerRef.current);
      stabilizationTimerRef.current = null;
    }
    setIsLibraryStabilizing(false);
  }, []);

  const startLibraryStabilizationWait = useCallback((params: {
    source: string;
    selectedCount?: number;
    processedCount?: number;
    addedCount?: number;
    importerRecordCount?: number;
  }) => {
    const importedCount = resolveStabilizationCount([
      params.selectedCount,
      params.processedCount,
      params.addedCount,
      params.importerRecordCount,
    ]);
    const delayMs = estimateLibraryStabilizationDelayMs(importedCount);

    console.info("[LibraryStabilization] counts", {
      source: params.source,
      selectedCount: params.selectedCount ?? null,
      processedCount: params.processedCount ?? null,
      addedCount: params.addedCount ?? null,
      importerRecordCount: params.importerRecordCount ?? null,
      importedCount,
      delayMs,
    });

    if (delayMs <= 0) return;

    console.info("[LibraryStabilization] modal-open", { source: params.source, importedCount, delayMs, openedAt: Date.now() });
    setIsLibraryStabilizing(true);
    if (stabilizationTimerRef.current !== null) {
      window.clearTimeout(stabilizationTimerRef.current);
    }

    // TODO: Reemplazar por una señal nativa real cuando exista evento de finalización definitivo.
    stabilizationTimerRef.current = window.setTimeout(() => {
      stabilizationTimerRef.current = null;
      setIsLibraryStabilizing(false);
      console.info("[LibraryStabilization] modal-close", { source: params.source, importedCount, delayMs, closedAt: Date.now() });
    }, delayMs);
  }, []);

  const [showOnboarding, setShowOnboarding] = useState(false);
  const [onboardingStep, setOnboardingStep] = useState(0);
  const [newPlaylistName, setNewPlaylistName] = useState("");
  const [playlistMenu, setPlaylistMenu] = useState<{
    playlist: Playlist;
    x: number;
    y: number;
  } | null>(null);

  // Ref para evitar recargar el archivo cuando cambian los params
  const currentTrackRef = useRef<string | null>(null);
  const initialLoadRef = useRef(true);
  const lastAutoPresetTrackRef = useRef<string | null>(null);
  const lastAutoPresetTimeRef = useRef(0);
  const trackLoadRequestRef = useRef(0);
  const playbackReasonRef = useRef("queue-change");
  const currentTrackIdRef = useRef<string | null>(null);
  const playTimeoutRef = useRef<number | null>(null);
  const autoOptimizationTimeoutRef = useRef<number | null>(null);
  const failedQueueTrackIdsRef = useRef<Set<string>>(new Set());
  const nextPrefetchKeyRef = useRef<string | null>(null);
  const mediaStoreReconciledRef = useRef(false);
  const scanModalSafetyRef = useRef<number | null>(null);
  const librarySettleTimerRef = useRef<number | null>(null);
  const lastPositionSyncRef = useRef(0);
  const stabilizationTimeoutRef = useRef<number | null>(null);

  const hiResTracks = useMemo(
    () => queue.library.filter((track) => track.isHiRes),
    [queue.library],
  );

  const sortedSongs = useMemo(() => {
    if (songSort === "default") return queue.library;
    const copy = [...queue.library];
    if (songSort === "name") {
      copy.sort((a, b) =>
        a.title.localeCompare(b.title, language === "es" ? "es" : "en", {
          sensitivity: "base",
        }),
      );
      return copy;
    }
    copy.sort((a, b) =>
      a.artist.localeCompare(b.artist, language === "es" ? "es" : "en", {
        sensitivity: "base",
      }),
    );
    return copy;
  }, [queue.library, songSort, language]);

  useEffect(() => {
    setVisibleSongsCount(250);
    setVisibleArtistsCount(30);
  }, [songSort, queue.library.length]);

  // === Paginación server-side de "Canciones" (Android) ===
  // Con bibliotecas grandes, esperar a que queue.library termine de cargar y
  // re-ordenarla en memoria es lo que hacía sentir la vista "increíblemente
  // tardada". Aquí el listado se sirve por páginas desde la DB nativa, que ya
  // ordena/pagina indexado. queue.library se sigue cargando en segundo plano
  // para el resto de vistas (artistas/álbumes/hi-res/playlists/aleatorio).
  const songsPaginated = androidMusicLibrary.isAndroid;

  // Señal de refresco DISCRETA: solo al terminar un escaneo, que es cuando de
  // verdad pueden haber aparecido canciones nuevas. Deliberadamente NO se usa
  // queue.library.length: durante la carga progresiva crece por páginas (la
  // lista se reiniciaría varias veces y el usuario perdería el scroll), y
  // compararlo contra paginatedSongs.total se cicla, porque las pistas efímeras
  // viven en queue.library pero no en la DB nativa y los conteos nunca cuadran.
  const [songsRefreshKey, setSongsRefreshKey] = useState(0);
  const lastScanStatusRef = useRef<string>("");
  useEffect(() => {
    const status = androidMusicLibrary.scanProgress.status;
    if (status === "complete" && lastScanStatusRef.current !== "complete") {
      setSongsRefreshKey((key) => key + 1);
    }
    lastScanStatusRef.current = status;
  }, [androidMusicLibrary.scanProgress.status]);

  const { sortBy: songsSortBy, sortDir: songsSortDir } = useMemo(() => {
    if (songSort === "name") return { sortBy: "title" as const, sortDir: "asc" as const };
    if (songSort === "artist") return { sortBy: "artist" as const, sortDir: "asc" as const };
    // "default" = orden de llegada, igual que loadLibrary
    return { sortBy: "dateModified" as const, sortDir: "desc" as const };
  }, [songSort]);

  const paginatedSongs = usePaginatedSongs({
    enabled: songsPaginated,
    sortBy: songsSortBy,
    sortDir: songsSortDir,
    refreshKey: songsRefreshKey,
  });

  // Fuente única del listado de canciones: la usan tanto el render como el
  // efecto de enriquecimiento, para que no se desincronicen.
  const songsForDisplay = songsPaginated ? paginatedSongs.songs : sortedSongs;

  // On-demand metadata enrichment: only the songs ACTUALLY visible on screen.
  // This is what replaced the old "enrich the entire library after scan" sweep
  // that froze large collections. The native side is bounded + deduped, so it's
  // safe to re-run on every scroll/sort. Hi-Res badges already work instantly
  // from the MIME type; this just fills in exact bitDepth/sampleRate numbers.
  useEffect(() => {
    if (!androidMusicLibrary.isAndroid) return;
    if (libraryView !== "songs" && libraryView !== "hires") return;
    const source = libraryView === "hires" ? hiResTracks : songsForDisplay;
    // En modo paginado ya solo tenemos en memoria las páginas cargadas, así que
    // todas son "visibles"; en el modo clásico se recorta por visibleSongsCount.
    const limit =
      libraryView === "songs" && songsPaginated ? source.length : visibleSongsCount;
    const ids: string[] = [];
    for (const t of source.slice(0, limit)) {
      if (t.sampleRate && t.sampleRate > 0) continue; // already has exact specs
      if (t.sourceType === "file") continue; // ephemeral / not in native DB
      const stableId = t.id.startsWith("media-")
        ? t.id.slice("media-".length)
        : t.mediaStoreId || t.id;
      if (stableId) ids.push(stableId);
    }
    if (ids.length === 0) return;
    const handle = window.setTimeout(() => {
      void androidMusicLibrary.enrichTracks(ids);
    }, 400);
    return () => window.clearTimeout(handle);
  }, [
    androidMusicLibrary,
    libraryView,
    songsForDisplay,
    songsPaginated,
    hiResTracks,
    visibleSongsCount,
  ]);

  // Drive the "preparing library" modal from the REAL native scan progress
  // instead of a fixed timer: open while the scan/sync runs, show its true %,
  // and close the instant the native reports 'complete'. A safety timeout makes
  // sure it can never get stuck if an event is ever missed.
  useEffect(() => {
    const status = androidMusicLibrary.scanProgress.status;
    // Open the spinner while the native scan runs. It STAYS open through
    // 'complete' (and beyond) until the library actually settles — see the
    // settle effect below. Only 'error' closes immediately. 90s safety backstop.
    if (status === "scanning" || status === "syncing") {
      setIsLibraryStabilizing(true);
      setLibraryPrepDone(false);
      // HARD CAP: arm the auto-close timer only ONCE (when the overlay first
      // opens). Do NOT re-arm it on later scan events — repeated scans kept
      // resetting it, so the "preparing" overlay could stay stuck for hours
      // (e.g. right after an app update). This guarantees it disappears within
      // ~20s no matter what. onClose/the timer itself null it, so a later scan
      // can re-arm.
      if (scanModalSafetyRef.current == null) {
        scanModalSafetyRef.current = window.setTimeout(() => {
          setIsLibraryStabilizing(false);
          setLibraryPrepDone(false);
          scanModalSafetyRef.current = null;
        }, 20000);
      }
    } else if (status === "error") {
      setIsLibraryStabilizing(false);
      setLibraryPrepDone(false);
      if (scanModalSafetyRef.current) {
        window.clearTimeout(scanModalSafetyRef.current);
        scanModalSafetyRef.current = null;
      }
    }
  }, [androidMusicLibrary.scanProgress.status]);

  // The REAL "done" signal: after the native scan reports 'complete', keep the
  // spinner until the library has stopped growing/loading for ~1.2s. This covers
  // the frontend ingestion (page loads + reconcile) that happens AFTER the native
  // DB write, so the checkmark only appears when everything is truly settled.
  useEffect(() => {
    if (!isLibraryStabilizing) return;
    if (androidMusicLibrary.scanProgress.status !== "complete") return;
    if (librarySettleTimerRef.current) {
      window.clearTimeout(librarySettleTimerRef.current);
    }
    librarySettleTimerRef.current = window.setTimeout(() => {
      setLibraryPrepDone(true);
      librarySettleTimerRef.current = null;
    }, 1200);
    return () => {
      if (librarySettleTimerRef.current) {
        window.clearTimeout(librarySettleTimerRef.current);
        librarySettleTimerRef.current = null;
      }
    };
  }, [
    isLibraryStabilizing,
    androidMusicLibrary.scanProgress.status,
    queue.library.length,
    queue.isLoading,
  ]);

  const closeLibraryPreparing = useCallback(() => {
    setIsLibraryStabilizing(false);
    setLibraryPrepDone(false);
    if (scanModalSafetyRef.current) {
      window.clearTimeout(scanModalSafetyRef.current);
      scanModalSafetyRef.current = null;
    }
    if (librarySettleTimerRef.current) {
      window.clearTimeout(librarySettleTimerRef.current);
      librarySettleTimerRef.current = null;
    }
  }, []);

  const startLibraryStabilization = useCallback((importedCount: number) => {
    if (importedCount <= 0) return;

    if (stabilizationTimeoutRef.current) {
      window.clearTimeout(stabilizationTimeoutRef.current);
    }

    setIsLibraryStabilizing(true);

    // TODO: Reemplazar por un evento real cuando Android termine de preparar
    // internamente los archivos recién importados.
    const waitMs = Math.min(
      LIBRARY_STABILIZATION_BASE_MS +
        importedCount * LIBRARY_STABILIZATION_PER_TRACK_MS,
      LIBRARY_STABILIZATION_MAX_MS,
    );

    stabilizationTimeoutRef.current = window.setTimeout(() => {
      setIsLibraryStabilizing(false);
      stabilizationTimeoutRef.current = null;
    }, waitMs);
  }, []);

  useEffect(() => {
    return () => {
      if (stabilizationTimeoutRef.current) {
        window.clearTimeout(stabilizationTimeoutRef.current);
      }
    };
  }, []);

  const normalizedGlobalQuery = globalSearchQuery.trim().toLowerCase();

  const globalResults = useMemo(() => {
    if (!normalizedGlobalQuery) return [];
    return queue.library.filter((track) =>
      `${track.title} ${track.artist}`
        .toLowerCase()
        .includes(normalizedGlobalQuery),
    );
  }, [queue.library, normalizedGlobalQuery]);

  useEffect(() => {
    const dismissed = localStorage.getItem("epicenter-onboarding-dismissed");
    const legacyDismissed = localStorage.getItem("epicenter-welcome-dismissed");
    if (!dismissed && !legacyDismissed) {
      setShowOnboarding(true);
    } else if (!dismissed && legacyDismissed) {
      localStorage.setItem("epicenter-onboarding-dismissed", "true");
    }
  }, []);

  const onboardingSteps = useMemo(
    () => [
      {
        title: t("onboarding.step1Title"),
        description: t("onboarding.step1Description"),
      },
      {
        title: t("onboarding.step2Title"),
        description: t("onboarding.step2Description"),
      },
      {
        title: t("onboarding.step3Title"),
        description: t("onboarding.step3Description"),
      },
    ],
    [t],
  );

  const dismissOnboarding = useCallback(() => {
    localStorage.setItem("epicenter-onboarding-dismissed", "true");
    setShowOnboarding(false);
    setOnboardingStep(0);
  }, []);

  // Actualizar selectedPlaylist cuando cambien los playlists
  useEffect(() => {
    if (selectedPlaylist) {
      const updated = playlistManager.playlists.find(
        (p) => p.id === selectedPlaylist.id,
      );
      if (
        updated &&
        updated.trackIds.length !== selectedPlaylist.trackIds.length
      ) {
        setSelectedPlaylist(updated);
      }
    }
  }, [playlistManager.playlists, selectedPlaylist]);

  // Cargar última configuración
  useEffect(() => {
    const lastConfig = presetManager.getLastConfig();
    if (lastConfig) {
      setDspParams(clampDspParams(lastConfig.dspParams));
      audioProcessor.eqBands.forEach((_, index) => {
        audioProcessor.setEqBandGain(index, lastConfig.eqBands[index] || 0);
      });
    }
    initialLoadRef.current = false;
  }, []);

  // Configurar crossfade en el procesador de audio
  useEffect(() => {
    audioProcessor.setCrossfadeConfig({
      enabled: crossfade.enabled,
      duration: crossfade.duration,
    });
  }, [crossfade.enabled, crossfade.duration, audioProcessor]);

  // Configurar handlers de Media Session y Notificaciones Nativas
  useEffect(() => {
    mediaSession.setHandlers({
      onPlay: () => audioProcessor.play(),
      onPause: () => audioProcessor.pause(),
      onNextTrack: () => {
        playbackReasonRef.current = "next";
        queue.nextTrack();
      },
      onPreviousTrack: () => {
        playbackReasonRef.current = "previous";
        queue.previousTrack();
      },
      onSeekTo: (time) => audioProcessor.seek(time),
      onSeekBackward: (offset) => {
        audioProcessor.seek(Math.max(0, audioProcessor.currentTime - offset));
      },
      onSeekForward: (offset) => {
        audioProcessor.seek(
          Math.min(
            audioProcessor.duration,
            audioProcessor.currentTime + offset,
          ),
        );
      },
    });

    mediaNotification.setHandlers({
      onPlay: () => audioProcessor.play(),
      onPause: () => audioProcessor.pause(),
      onNext: () => {
        playbackReasonRef.current = "next";
        queue.nextTrack();
      },
      onPrevious: () => {
        playbackReasonRef.current = "previous";
        queue.previousTrack();
      },
      onSeek: (time) => audioProcessor.seek(time),
    });
  }, [audioProcessor, queue, mediaSession, mediaNotification]);

  // Actualizar metadatos en Media Session cuando cambia el track
  useEffect(() => {
    if (nowPlayingTrack) {
      mediaSession.updateMetadata({
        title: nowPlayingTrack.title,
        artist: nowPlayingTrack.artist,
        artwork: nowPlayingTrack.coverUrl,
      });

      mediaNotification.updateMetadata({
        title: nowPlayingTrack.title,
        artist: nowPlayingTrack.artist,
        album: "Epicenter Hi-Fi",
        artwork: nowPlayingTrack.coverUrl,
      });
    }
  }, [nowPlayingTrack, mediaSession, mediaNotification]);

  // Actualizar estado de reproducción
  useEffect(() => {
    mediaSession.updatePlaybackState(
      audioProcessor.isPlaying ? "playing" : "paused",
    );
    mediaNotification.updatePlaybackState(audioProcessor.isPlaying);

    if (audioProcessor.isPlaying && nowPlayingTrack) {
      mediaNotification.start();
    }
  }, [
    audioProcessor.isPlaying,
    mediaSession,
    mediaNotification,
    nowPlayingTrack,
  ]);

  // Mantener CPU/IO despiertos sólo mientras realmente hay reproducción activa.
  useEffect(() => {
    const MusicScanner = (window as any).Capacitor?.Plugins?.MusicScanner;
    const shouldHoldWakeLock = audioProcessor.isPlaying && !!nowPlayingTrack?.id;
    const trackId = nowPlayingTrack?.id ?? "none";

    if (shouldHoldWakeLock) {
      console.info("[PlaybackWakeLock] acquire", { trackId });
      void MusicScanner?.acquirePlaybackWakeLock?.({ trackId }).catch((error: unknown) => {
        console.warn("[PlaybackWakeLock] acquire failed", { trackId, error });
      });
    } else {
      console.info("[PlaybackWakeLock] release", { reason: "not-playing", trackId });
      void MusicScanner?.releasePlaybackWakeLock?.({ reason: "not-playing", trackId }).catch((error: unknown) => {
        console.warn("[PlaybackWakeLock] release failed", { trackId, error });
      });
    }

    return () => {
      if (!shouldHoldWakeLock) return;
      console.info("[PlaybackWakeLock] release", { reason: "effect-cleanup", trackId });
      void MusicScanner?.releasePlaybackWakeLock?.({ reason: "effect-cleanup", trackId }).catch((error: unknown) => {
        console.warn("[PlaybackWakeLock] cleanup release failed", { trackId, error });
      });
    };
  }, [audioProcessor.isPlaying, nowPlayingTrack?.id]);

  // Actualizar posición sin saturar el bridge nativo durante reproducción.
  useEffect(() => {
    if (audioProcessor.duration <= 0) return;
    const now = performance.now();
    if (now - lastPositionSyncRef.current < 1000) return;
    lastPositionSyncRef.current = now;
    mediaSession.updatePosition(
      audioProcessor.currentTime,
      audioProcessor.duration,
    );
    mediaNotification.updatePosition(
      audioProcessor.currentTime,
      audioProcessor.duration,
    );
  }, [
    audioProcessor.currentTime,
    audioProcessor.duration,
    mediaSession,
    mediaNotification,
  ]);

  // Guardar configuración (debounced)
  useEffect(() => {
    if (initialLoadRef.current) return;
    const timer = setTimeout(() => {
      presetManager.saveLastConfig(
        audioProcessor.eqBands.map((b) => b.gain),
        dspParams,
      );
    }, 500);
    return () => clearTimeout(timer);
  }, [dspParams, audioProcessor.eqBands]);


  useEffect(() => {
    currentTrackIdRef.current = queue.currentTrack?.id ?? null;
  }, [queue.currentTrack?.id]);

  const clearPendingPlaybackTimers = useCallback(() => {
    if (playTimeoutRef.current !== null) {
      window.clearTimeout(playTimeoutRef.current);
      playTimeoutRef.current = null;
    }
    if (autoOptimizationTimeoutRef.current !== null) {
      window.clearTimeout(autoOptimizationTimeoutRef.current);
      autoOptimizationTimeoutRef.current = null;
    }
  }, []);

  const resolveTrackSource = useCallback(
    async (
      track: Track,
      context?: { requestId?: number; reason?: string },
    ): Promise<ResolvedTrackPlaybackSource> => {
      if (track.sourceUri && track.sourceType !== "file") {
        if (track.unavailable) {
          throw new Error("Track source not available");
        }

        const resolveStartMs = performance.now();
        if (androidMusicLibrary.isAndroid) {
          // ExoPlayer's ContentDataSource can seek the original MediaStore URI.
          // Routing the same file through localhost added copying/threading and
          // was especially fragile for large 192 kHz lossless files.
          const result: AndroidAudioFileUrlResult = {
            url: track.sourceUri,
            playbackSource: "content-uri-direct",
            sourceUriScheme: track.sourceUri.split(":", 1)[0] || "unknown",
            resolveDurationMs: performance.now() - resolveStartMs,
            fastPath: true,
          };
          return { source: track.sourceUri, resolveResult: result };
        }

        const stableLibraryTrack =
          queue.library.find((libraryTrack) => libraryTrack.id === track.id) ??
          (track.sourceTrackId
            ? queue.library.find(
                (libraryTrack) => libraryTrack.id === track.sourceTrackId,
              )
            : null) ??
          queue.library.find(
            (libraryTrack) => libraryTrack.sourceUri === track.sourceUri,
          );

        const resolvedTrackId = stableLibraryTrack?.id ?? track.sourceTrackId ?? track.id;
        const result = await androidMusicLibrary.getAudioFileUrlResult(
          track.sourceUri,
          resolvedTrackId,
          {
            expectedSize: stableLibraryTrack?.fileSize ?? track.fileSize,
            sourceVersionKey: stableLibraryTrack?.sourceVersionKey ?? track.sourceVersionKey,
            allowStreaming: true,
          },
        );
        const fileUrl = result?.url ?? null;
        console.info("[RESOLVE_RESULT]", {
          requestId: context?.requestId ?? trackLoadRequestRef.current,
          reason: context?.reason,
          source: fileUrl,
          sourceType: track.sourceType,
          sourceUriScheme: result?.sourceUriScheme,
          playbackSource: result?.playbackSource,
          streamUrlUsed: !!result?.streamUrl,
          localhostUsed: !!fileUrl?.startsWith("http://127.0.0.1"),
          filePathUsed: !!result?.filePath || (!!fileUrl && !fileUrl.startsWith("http://127.0.0.1")),
          cacheHit: result?.cacheHit ?? result?.cached ?? false,
          copyTimeMs: result?.copyTimeMs ?? 0,
          resolveDurationMs: result?.resolveDurationMs ?? performance.now() - resolveStartMs,
        });
        console.info("[LOAD_DEBUG_SOURCE]", {
          requestedTrackId: track.id,
          requestedTrackSourceTrackId: track.sourceTrackId,
          requestedTrackTitle: track.title,
          requestedTrackSourceType: track.sourceType,
          requestedTrackSourceUri: track.sourceUri,
          requestedTrackMediaStoreId: track.mediaStoreId,
          requestedTrackFileSize: track.fileSize,
          stableLibraryTrackId: stableLibraryTrack?.id,
          resolvedTrackId,
          sourceFinal: fileUrl,
        });
        const resolveEndMs = performance.now();
        console.info("[PlaybackLatency] resolveTrackSource", {
          trackId: track.id,
          sourceTrackId: track.sourceTrackId,
          sourceUri: track.sourceUri,
          resolveStartMs,
          resolveEndMs,
          resolveDurationMs: resolveEndMs - resolveStartMs,
        });

        if (!fileUrl) {
          throw new Error("No se pudo obtener el audio del dispositivo");
        }

        return { source: fileUrl, resolveResult: result ?? undefined };
      }

      const trackFile = track.file ?? (await queue.getTrackFile(track));
      if (!trackFile) {
        throw new Error("Track source not available");
      }

      return { source: trackFile };
    },
    [androidMusicLibrary, queue.getTrackFile, queue.library],
  );


  useEffect(() => {
    if (!ANDROID_PREFETCH_ENABLED || androidMusicLibrary.isAndroid) return;
    const nextTrack = queue.queue[queue.currentTrackIndex + 1];
    if (!nextTrack || !nextTrack.sourceUri || nextTrack.sourceType === "file") {
      return;
    }

    const prefetchKey = `${nextTrack.sourceTrackId ?? nextTrack.id}:${nextTrack.sourceUri}:${nextTrack.sourceVersionKey ?? ""}`;
    if (nextPrefetchKeyRef.current === prefetchKey) {
      return;
    }
    nextPrefetchKeyRef.current = prefetchKey;

    const stableLibraryTrack =
      queue.library.find((libraryTrack) => libraryTrack.id === nextTrack.id) ??
      (nextTrack.sourceTrackId
        ? queue.library.find((libraryTrack) => libraryTrack.id === nextTrack.sourceTrackId)
        : undefined) ??
      queue.library.find(
        (libraryTrack) => libraryTrack.sourceUri === nextTrack.sourceUri,
      );

    void androidMusicLibrary.prepareAudioFileUrl?.(
      nextTrack.sourceUri,
      stableLibraryTrack?.id ?? nextTrack.sourceTrackId ?? nextTrack.id,
      {
        expectedSize: stableLibraryTrack?.fileSize ?? nextTrack.fileSize,
        sourceVersionKey: stableLibraryTrack?.sourceVersionKey ?? nextTrack.sourceVersionKey,
      },
    );
  }, [
    androidMusicLibrary,
    queue.currentTrackIndex,
    queue.library,
    queue.queue,
  ]);

  const playNextAvailableTrackAfterFailure = useCallback(
    (failedQueueTrackId: string) => {
      if (queue.queue.length <= 1) {
        return false;
      }

      const startIndex = queue.currentTrackIndex;
      for (let offset = 1; offset < queue.queue.length; offset += 1) {
        const candidateIndex = (startIndex + offset) % queue.queue.length;
        const candidateTrack = queue.queue[candidateIndex];

        if (!candidateTrack || candidateTrack.id === failedQueueTrackId) {
          continue;
        }

        if (failedQueueTrackIdsRef.current.has(candidateTrack.id)) {
          continue;
        }

        playbackReasonRef.current = "failure-skip";
        queue.playTrack(candidateIndex);
        return true;
      }

      return false;
    },
    [queue.currentTrackIndex, queue.playTrack, queue.queue],
  );

  // Configurar callbacks cuando termina o falla una canción.
  useEffect(() => {
    audioProcessor.setOnTrackEnded(() => {
      if (
        queue.queue.length > 0 &&
        queue.currentTrackIndex < queue.queue.length - 1
      ) {
        playbackReasonRef.current = "autoplay";
        queue.nextTrack();
      }
    });

    audioProcessor.setOnTrackError((error) => {
      const failedTrackId = queue.currentTrack?.id;
      if (!failedTrackId) {
        return;
      }

      failedQueueTrackIdsRef.current.add(failedTrackId);
      clearPendingPlaybackTimers();
      audioProcessor.resetAfterError();
      currentTrackRef.current = null;
      console.error("Playback runtime error:", error);

      const movedToNextTrack =
        playNextAvailableTrackAfterFailure(failedTrackId);
      if (movedToNextTrack) {
        toast.error(t("actions.errorLoadingTrackSkipped"));
      } else {
        // Evitar "bloqueo" permanente por lista de fallos acumulada.
        failedQueueTrackIdsRef.current.clear();
        toast.error(t("actions.errorLoadingTrackNoFallback"));
      }
    });

    return () => {
      audioProcessor.setOnTrackEnded(null);
      audioProcessor.setOnTrackError(null);
    };
  }, [
    audioProcessor,
    clearPendingPlaybackTimers,
    playNextAvailableTrackAfterFailure,
    queue,
    t,
  ]);

  useEffect(() => {
    if (audioProcessor.isPlaying && queue.currentTrack?.id) {
      failedQueueTrackIdsRef.current.delete(queue.currentTrack.id);
    }
  }, [audioProcessor.isPlaying, queue.currentTrack?.id]);

  // Push the full queue to ExoPlayer ONLY when track IDs change (not on index
  // changes). Triggering on index changes causes an infinite loop:
  // setQueue → onMediaItemTransition → trackChanged → playTrack → index change → setQueue…
  const lastNativeQueueIdsRef = useRef<string>('');
  useEffect(() => {
    if (queue.queue.length === 0 || queue.currentTrackIndex < 0) return;
    const ids = queue.queue.map((t) => t.id).join(',');
    if (ids === lastNativeQueueIdsRef.current) return;
    lastNativeQueueIdsRef.current = ids;
    audioProcessor.setNativeQueue?.(queue.queue, queue.currentTrackIndex);
  }, [queue.queue, audioProcessor]); // intentionally omit queue.currentTrackIndex

  // Sync JS currentTrackIndex when ExoPlayer auto-advances in native queue mode.
  useEffect(() => {
    audioProcessor.setOnNativeTrackAdvanced?.((nativeIndex, nativeTrackId) => {
      const jsIndex = nativeTrackId
        ? queue.queue.findIndex((track) => track.id === nativeTrackId)
        : nativeIndex;
      if (jsIndex >= 0 && jsIndex !== queue.currentTrackIndex) {
        queue.playTrack(jsIndex);
      }
    });
    return () => audioProcessor.setOnNativeTrackAdvanced?.(null);
  }, [audioProcessor, queue]);

  // Notification skip buttons → drive the JS queue (same as the in-app buttons).
  useEffect(() => {
    audioProcessor.setOnNotificationCommand?.((action) => {
      if (action === "next") queue.nextTrack();
      else if (action === "previous") queue.previousTrack();
    });
    return () => audioProcessor.setOnNotificationCommand?.(null);
  }, [audioProcessor, queue]);

  useEffect(() => {
    return () => {
      trackLoadRequestRef.current += 1;
      clearPendingPlaybackTimers();
      clearLibraryStabilizationTimer();
    };
  }, [clearLibraryStabilizationTimer, clearPendingPlaybackTimers]);

  useEffect(() => {
    // BUG 3 FIX: do NOT trigger a full MediaStore re-import on EVERY app
    // restart. The Room DB already has the tracks (with their content://
    // sourceUri values, which remain valid across restarts). Re-importing
    // every time made the user perceive the library as "lost / rebuilding"
    // and was also re-extracting metadata for every file on each launch.
    //
    // We now reconcile at most once every 24h, and only when needed. The
    // user can still trigger a manual rescan from the library/import UI
    // (MusicScanner + AndroidMusicImporter).
    const RECONCILE_INTERVAL_MS = 24 * 60 * 60 * 1000;
    const RECONCILE_TS_KEY = "epicenter-last-media-store-reconcile";

    const reconcileMediaStore = async () => {
      if (queue.isLoading || !androidMusicLibrary.isAndroid) return;

      const hasMediaStoreTracks = queue.library.some(
        (track) => track.sourceType === "media-store",
      );

      if (!hasMediaStoreTracks) return;
      if (mediaStoreReconciledRef.current) return;

      // Cooldown gate (localStorage). If we reconciled recently, skip.
      let lastReconcileTs = 0;
      try {
        const raw = window.localStorage.getItem(RECONCILE_TS_KEY);
        if (raw) lastReconcileTs = Number(raw) || 0;
      } catch {
        // localStorage may not be available; treat as never-reconciled.
      }
      const elapsed = Date.now() - lastReconcileTs;
      if (lastReconcileTs > 0 && elapsed < RECONCILE_INTERVAL_MS) {
        mediaStoreReconciledRef.current = true;
        console.info("[Library] Skipping MediaStore reconcile, last run", {
          ageHours: Math.round((elapsed / 3600000) * 10) / 10,
          cooldownHours: 24,
        });
        return;
      }

      const hasPermission = await androidMusicLibrary.checkPermissions();
      if (!hasPermission) return;

      // Set the guard BEFORE the async work. This effect depends on queue.library,
      // which keeps changing during the progressive load, so it re-fires several
      // times on startup — without this early guard each re-fire launched ANOTHER
      // concurrent scan (a scan storm) that kept the "preparing" overlay stuck.
      mediaStoreReconciledRef.current = true;

      try {
        console.info("[Library] Running MediaStore reconcile (cooldown expired)");
        const scannedTracks = await androidMusicLibrary.scanMusicLibrary();
        const result = await queue.reconcileMediaStoreTracks(scannedTracks);
        mediaStoreReconciledRef.current = true;
        try {
          window.localStorage.setItem(RECONCILE_TS_KEY, String(Date.now()));
        } catch {
          // Persisting cooldown failed; non-fatal.
        }
        if (result.updated > 0 || result.missing > 0) {
          console.info("[Library] MediaStore reconciled", result);
        }
      } catch (error) {
        console.warn("[Library] MediaStore reconciliation failed", error);
      }
    };

    void reconcileMediaStore();
  }, [
    androidMusicLibrary,
    queue.isLoading,
    queue.library,
    queue.reconcileMediaStoreTracks,
  ]);

  // Cargar última canción al iniciar (sin autoplay)
  const lastTrackLoadedRef = useRef(false);
  useEffect(() => {
    const loadLastTrack = async () => {
      if (
        !queue.isLoading &&
        lastTrack.isLoaded &&
        lastTrack.lastTrackId &&
        !queue.currentTrack &&
        !lastTrackLoadedRef.current
      ) {
        const track = queue.library.find((t) => t.id === lastTrack.lastTrackId);
        // With progressive library load the last track may live in a page that
        // hasn't streamed in yet — don't mark "done" until we actually find it,
        // so this effect retries as more pages arrive (queue.library is a dep).
        if (!track) return;
        lastTrackLoadedRef.current = true;

        const requestId = ++trackLoadRequestRef.current;
        clearPendingPlaybackTimers();
        currentTrackRef.current = track.id;

        console.log("[LastTrack] Loading last track:", track.title);
        queue.addToQueue(track);
        queue.playTrack(0);

        try {
          const { source } = await resolveTrackSource(track, {
            requestId,
            reason: "restore_last_track",
          });

          if (trackLoadRequestRef.current !== requestId) {
            return;
          }

          const loaded = await audioProcessor.loadFile(
            source,
            dspParams,
            {
              requestId,
              isCurrentRequest: () => trackLoadRequestRef.current === requestId,
            },
            {
              id: track.id,
              title: track.title,
              artist: track.artist,
              duration: track.duration,
              artworkUri: track.albumArtUri || track.coverUrl,
            },
          );

          if (!loaded || trackLoadRequestRef.current !== requestId) {
            return;
          }
        } catch (error) {
          if (trackLoadRequestRef.current === requestId) {
            currentTrackRef.current = null;
            console.error("[LastTrack] Error loading last track:", error);
          }
        }
      }
    };

    void loadLastTrack();
  }, [
    audioProcessor,
    clearPendingPlaybackTimers,
    dspParams,
    lastTrack.isLoaded,
    lastTrack.lastTrackId,
    queue.addToQueue,
    queue.isLoading,
    queue.library,
    queue.playTrack,
    resolveTrackSource,
  ]);

  const requestTrackPlayback = useCallback(
    (requestedTrack: Track, reason: string) => {
      if (!requestedTrack) return;

      const requestId = ++trackLoadRequestRef.current;
      clearPendingPlaybackTimers();
      currentTrackRef.current = requestedTrack.id;
      setPendingTrack(requestedTrack);
      console.info("[PLAYBACK_REQUEST]", {
        reason,
        requestId,
        requestedTrackId: requestedTrack.id,
        title: requestedTrack.title,
        sourceUri: requestedTrack.sourceUri,
        sourceType: requestedTrack.sourceType,
        currentNowPlayingId: nowPlayingTrack?.id,
        pendingTrackId: requestedTrack.id,
        activeAudioSource,
      });

      const loadTrack = async () => {
        const libraryTrack = queue.library.find(
          (track) => track.id === requestedTrack.id,
        ) ?? (requestedTrack.sourceTrackId
          ? queue.library.find((track) => track.id === requestedTrack.sourceTrackId)
          : undefined) ?? queue.library.find(
          (track) =>
            !!requestedTrack.sourceUri && track.sourceUri === requestedTrack.sourceUri,
        );

        if (libraryTrack) {
          lastTrack.saveLastTrack(libraryTrack.id);
        }

        try {
          const playbackRequestStartMs = performance.now();
          const resolveStartMs = playbackRequestStartMs;
          const { source, resolveResult } = await resolveTrackSource(requestedTrack, {
            requestId,
            reason,
          });
          const resolveEndMs = performance.now();

          if (
            trackLoadRequestRef.current !== requestId ||
            currentTrackIdRef.current !== requestedTrack.id
          ) {
            console.info("[PLAYBACK_CANCELLED]", { requestId, reason: "after_resolve" });
            return;
          }

          const loadFileStartMs = performance.now();
          const loaded = await audioProcessor.loadFile(
            source,
            dspParams,
            {
              requestId,
              isCurrentRequest: () =>
                trackLoadRequestRef.current === requestId &&
                currentTrackIdRef.current === requestedTrack.id,
            },
            {
              id: requestedTrack.id,
              title: requestedTrack.title,
              artist: requestedTrack.artist,
              duration: requestedTrack.duration,
              artworkUri: requestedTrack.albumArtUri || requestedTrack.coverUrl,
            },
          );
          const loadFileEndMs = performance.now();
          const isCurrentAfterLoad =
            trackLoadRequestRef.current === requestId &&
            currentTrackIdRef.current === requestedTrack.id;
          console.info("[LOAD_FILE_RESULT]", {
            requestId,
            loaded,
            source,
            isCurrentRequest: isCurrentAfterLoad,
          });

          if (!loaded || !isCurrentAfterLoad) {
            console.info("[PLAYBACK_CANCELLED]", {
              requestId,
              reason: loaded ? "stale_after_load" : "load_failed_or_cancelled",
            });
            return;
          }

          const activeSrc = audioProcessor.getActiveSource();
          if (!activeSrc) {
            console.info("[PLAYBACK_CANCELLED]", { requestId, reason: "missing_active_source" });
            return;
          }
          setNowPlayingTrack(requestedTrack);
          setPendingTrack(null);
          setActiveAudioSource(activeSrc);
          console.info("[NOW_PLAYING_COMMIT]", {
            requestId,
            reason,
            title: requestedTrack.title,
            activeSrc,
            totalTimeToCommitMs: loadFileEndMs - playbackRequestStartMs,
            streamUrlUsed: !!resolveResult?.streamUrl,
            playbackSource: resolveResult?.playbackSource,
            sourceUriScheme: resolveResult?.sourceUriScheme,
          });

          const playCallMs = performance.now();
          console.info("[PlaybackLatency] playRequest", {
            trackId: requestedTrack.id,
            sourceTrackId: requestedTrack.sourceTrackId,
            playbackRequestStartMs,
            resolveStartMs,
            resolveEndMs,
            loadFileStartMs,
            loadFileEndMs,
            playCallMs,
            resolveDurationMs: resolveEndMs - resolveStartMs,
            loadFileDurationMs: loadFileEndMs - loadFileStartMs,
            totalBeforePlayMs: playCallMs - playbackRequestStartMs,
          });
          audioProcessor.play();

          autoOptimizationTimeoutRef.current = window.setTimeout(() => {
            if (
              trackLoadRequestRef.current !== requestId ||
              currentTrackIdRef.current !== requestedTrack.id
            ) {
              return;
            }

            void runAutoOptimization();
          }, 1400);
        } catch (error) {
          if (
            trackLoadRequestRef.current === requestId &&
            currentTrackIdRef.current === requestedTrack.id
          ) {
            failedQueueTrackIdsRef.current.add(requestedTrack.id);
            audioProcessor.resetAfterError();
            currentTrackRef.current = null;
            console.error("[PLAYBACK_ERROR]", {
              requestId,
              error,
              activeSrc: audioProcessor.getActiveSource(),
              requestedTrackId: requestedTrack.id,
            });
            setPendingTrack(null);
            const movedToNextTrack = playNextAvailableTrackAfterFailure(
              requestedTrack.id,
            );

            if (movedToNextTrack) {
              toast.error(t("actions.errorLoadingTrackSkipped"));
            } else {
              failedQueueTrackIdsRef.current.clear();
              toast.error(t("actions.errorLoadingTrackNoFallback"));
            }
          }
        }
      };

      void loadTrack();
    },
    [
      audioProcessor,
      clearPendingPlaybackTimers,
      dspParams,
      lastTrack.saveLastTrack,
      queue.library,
      resolveTrackSource,
      playNextAvailableTrackAfterFailure,
      activeAudioSource,
      nowPlayingTrack,
      t,
    ],
  );

  // Cargar track cuando cambia (y guardar como último track)
  useEffect(() => {
    const requestedTrack = queue.currentTrack;

    if (!requestedTrack || requestedTrack.id === currentTrackRef.current) {
      return;
    }

    const reason = playbackReasonRef.current || "queue-change";
    playbackReasonRef.current = "queue-change";
    requestTrackPlayback(requestedTrack, reason);
  }, [queue.currentTrack, requestTrackPlayback]);

  const handleFileSelect = useCallback(async () => {
    const capacitor = (window as any).Capacitor;
    const musicScanner = capacitor?.Plugins?.MusicScanner;
    const platform = typeof capacitor?.getPlatform === "function" ? capacitor.getPlatform() : "";
    const isAndroidNative = !!musicScanner && (platform === "android" || /android/.test(navigator.userAgent.toLowerCase()));

    const handleImportResult = (result: {
      added: number;
      duplicates: string[];
      selectedCount?: number;
      processedCount?: number;
      importerRecordCount?: number;
    }) => {
      if (result.added > 0) {
        const msg =
          result.added > 1
            ? t("actions.songsAddedPlural", { count: result.added })
            : t("actions.songsAdded", { count: result.added });
        toast.success(msg);
      }

      startLibraryStabilizationWait({
        source: "manual-import-result",
        selectedCount: result.selectedCount,
        processedCount: result.processedCount,
        addedCount: result.added,
        importerRecordCount: result.importerRecordCount,
      });

      if (result.duplicates.length > 0) {
        setShowDuplicatesModal(result.duplicates);
      }
    };

    try {
      if (isAndroidNative) {
        console.info("[ManualImport] using native Android picker", { platform });
        const result = await queue.importManualTracksFromNativePicker();
        handleImportResult(result);
        startLibraryStabilization(result.added);
        return;
      }
      console.info("[ManualImport] using Web file picker fallback", { platform, hasMusicScanner: !!musicScanner });

      const input = document.createElement("input");
      input.type = "file";
      input.accept = "audio/*,.mp3,.wav,.flac,.ogg,.m4a,.aac";
      input.multiple = true;
      input.onchange = async (e) => {
        const files = Array.from((e.target as HTMLInputElement).files || []);
        if (files.length === 0) return;
        try {
          const result = await queue.addToLibrary(files);
          console.info("[ManualImport] web-fallback-selection", { selectedCount: files.length });
          handleImportResult({ ...result, selectedCount: files.length, processedCount: files.length });
        } catch (error) {
          console.error("[ManualImport] Web fallback failed", error);
          toast.error(t("actions.errorAddingSongs"), {
            description: error instanceof Error ? error.message : undefined,
          });
        }
      };
      input.click();
    } catch (error) {
      console.error("[ManualImport] Native import failed", error);
      toast.error(t("actions.errorAddingSongs"), {
        description: error instanceof Error ? error.message : undefined,
      });
    }
  }, [queue, startLibraryStabilizationWait, t]);

  const handleMediaStoreImport = useCallback(
    async (tracks: AndroidMusicFile[]) => {
      console.info("[MediaStoreImport] scan-selection", { selectedCount: tracks.length });
      const result = await queue.addMediaStoreTracks(
        tracks,
        androidMusicLibrary.getAlbumArt,
      );
      // No timer here: the "preparing library" modal is driven by the real
      // native scanProgress events (see the scanProgress effect above), so it
      // shows the true % during the scan and closes itself on 'complete'.

      if (result.added > 0) {
        const msg =
          result.added > 1
            ? t("actions.songsAddedPlural", { count: result.added })
            : t("actions.songsAdded", { count: result.added });
        toast.success(msg);
      }

      if (result.duplicates.length > 0) {
        setShowDuplicatesModal(result.duplicates);
      }

      return result;
    },
    [queue, t],
  );

  const updateDspParam = useCallback(
    (key: keyof StreamingParams, value: number) => {
      const clampedValue = clampDspParam(key, value);
      setDspParams((prev) => ({ ...prev, [key]: clampedValue }));
      if (key === "volume" || epicenterEnabled) {
        audioProcessor.setDspParam(key, clampedValue);
      }
    },
    [audioProcessor, epicenterEnabled],
  );

  const toggleEq = useCallback(
    (enabled: boolean) => {
      audioProcessor.setEqEnabled(enabled);

      // Epicenter debe poder seguir activo de forma independiente aunque el EQ se apague.
    },
    [audioProcessor, epicenterEnabled],
  );

  const changeEpicenterMode = useCallback(
    (mode: EpicenterMode) => {
      if (mode === epicenterMode) return;
      // El nativo resetea ambos motores al cambiar, así que no arrastra estado
      // de filtros/fase entre modos.
      audioProcessor.setEpicenterMode?.(mode);
    },
    [audioProcessor, epicenterMode],
  );

  const toggleEpicenter = useCallback(() => {
    const newEnabled = !epicenterEnabled;
    audioProcessor.setEpicenterEnabled(newEnabled);
    if (newEnabled) {
      Object.entries(dspParams).forEach(([key, value]) => {
        audioProcessor.setDspParam(key as keyof StreamingParams, value);
      });
    }
  }, [epicenterEnabled, audioProcessor, dspParams]);

  /**
   * Ajuste automático en Android, calculado del espectro REAL de la canción.
   * Devuelve true si llegó a aplicar algo.
   */
  async function runNativeAutoTune(): Promise<boolean> {
    const plugin = (window as any).Capacitor?.Plugins?.EpicenterNative;
    if (!plugin?.getSpectrumProfile) return false;

    let profile: SpectrumProfile | null = null;
    try {
      // Measure independently of the Epicenter toggle, and pay the FFT cost
      // only during this short automatic-tuning window.
      await plugin.setSpectrumAnalysisEnabled?.({ enabled: true });
      await plugin.resetSpectrumProfile?.();
      for (let attempt = 0; attempt < 8; attempt++) {
        const raw = await plugin.getSpectrumProfile().catch(() => null);
        if (raw?.ready && Array.isArray(raw.bands) && raw.bands.length > 0) {
          profile = raw as SpectrumProfile;
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 450));
      }
    } finally {
      await plugin.setSpectrumAnalysisEnabled?.({ enabled: false }).catch(() => undefined);
    }
    if (!profile) {
      console.warn("[AutoTune] sin espectro medido; no se aplica nada");
      return false;
    }

    let applied = false;

    if (eqAutoEnabled) {
      const freqs = audioProcessor.eqBands.map((band) => band.frequency);
      const targetGains = autoTuneEq(profile, freqs, 1.5, 3);
      const currentGains = audioProcessor.eqBands.map((band) => band.gain);
      await applyPresetSmooth({
        currentGains,
        targetGains,
        setEqBandGain: audioProcessor.setEqBandGain,
        durationMs: 800,
        stepMs: 100,
        maxDeltaPerStep: 0.5,
      });
      audioProcessor.setEqPreampDb(suggestPreampDb(targetGains));
      audioProcessor.setEqEnabled(true);
      applied = true;
      console.log("[AutoTune] EQ personalizado", {
        maxBoost: Math.max(...targetGains).toFixed(2),
        maxCut: Math.min(...targetGains).toFixed(2),
      });
    }

    // El motor de Audífonos solo usa Intensity y su calibración ya está
    // validada por medición: el ajuste automático NO lo toca.
    if (dspAutoEnabled && epicenterMode !== "headphones") {
      if (!epicenterEnabled) audioProcessor.setEpicenterEnabled(true);
      const tuned = autoTuneEpicenter(profile);
      // Balance y Volume se preservan a propósito: son del usuario.
      const next = clampDspParams({
        ...dspParams,
        intensity: tuned.intensity,
        sweepFreq: tuned.sweepFreq,
        width: tuned.width,
      });
      setDspParams(next);
      (["intensity", "sweepFreq", "width"] as const).forEach((key) => {
        audioProcessor.setDspParam(key, next[key]);
      });
      applied = true;
      console.log("[AutoTune] Epicenter", tuned.reason);
    }

    return applied;
  }

  async function runAutoOptimization(force = false) {
    if (!eqAutoEnabled && !dspAutoEnabled) return;
    if (!queue.currentTrack) return;

    const now = Date.now();
    if (
      !force &&
      lastAutoPresetTrackRef.current === queue.currentTrack.id &&
      now - lastAutoPresetTimeRef.current < 30000
    ) {
      return;
    }

    // Android: el espectro se mide en el nativo (no hay AnalyserNode porque la
    // reproducción no pasa por Web Audio). Ese camino calcula una corrección
    // PROPIA de la canción en vez de elegir un preset.
    if (androidMusicLibrary.isAndroid) {
      const applied = await runNativeAutoTune();
      if (applied) {
        lastAutoPresetTrackRef.current = queue.currentTrack.id;
        lastAutoPresetTimeRef.current = now;
        toast.success(t("actions.autoOptimizedPreset"));
      }
      return;
    }

    const analyserNode = audioProcessor.getAnalyserNode();
    const selection = await analyzeSpectrumAndSelectPreset({
      analyserNode,
      sampleCount: 80,
      intervalMs: 125,
    });

    if (eqAutoEnabled) {
      const currentGains = audioProcessor.eqBands.map((band) => band.gain);
      await applyPresetSmooth({
        currentGains,
        targetGains: selection.preset.gainsDb,
        setEqBandGain: audioProcessor.setEqBandGain,
        durationMs: 800,
        stepMs: 100,
        maxDeltaPerStep: 0.5,
      });
      audioProcessor.setEqPreampDb(selection.preset.preampDb);
      audioProcessor.setEqEnabled(true);
    }

    if (dspAutoEnabled) {
      if (!epicenterEnabled) {
        audioProcessor.setEpicenterEnabled(true);
      }
      const dspSuggestion = suggestDspFromScores(selection.debug);
      const clampedSuggestion = clampDspParams({
        ...dspParams,
        ...dspSuggestion,
      });
      setDspParams(clampedSuggestion);
      Object.entries(clampedSuggestion).forEach(([key, value]) => {
        if (typeof value === "number") {
          audioProcessor.setDspParam(key as keyof StreamingParams, value);
        }
      });
    }

    lastAutoPresetTrackRef.current = queue.currentTrack.id;
    lastAutoPresetTimeRef.current = now;

    console.log("[AutoAdjustment]", {
      presetId: selection.presetId,
      presetName: selection.preset.name,
      debug: selection.debug,
    });

    toast.success(t("actions.autoOptimizedPreset"));
  }

  const formatTime = (seconds: number) => {
    if (!isFinite(seconds)) return "0:00";
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  // Agrupar canciones
  const songsByArtist = useMemo(
    () =>
      queue.library.reduce(
        (acc, track) => {
          const artist = track.artist || t("common.unknownArtist");
          if (!acc[artist]) acc[artist] = [];
          acc[artist].push(track);
          return acc;
        },
        {} as Record<string, Track[]>,
      ),
    [queue.library, t],
  );

  const albums = useMemo(
    () =>
      queue.library.reduce(
        (acc, track) => {
          const album = track.title.split(" - ")[0] || track.title;
          if (!acc[album]) acc[album] = [];
          acc[album].push(track);
          return acc;
        },
        {} as Record<string, Track[]>,
      ),
    [queue.library],
  );

  // Handlers
  const handleAddToQueue = (track: Track) => {
    queue.addToQueue(track);
    toast.success(t("actions.addedToQueue"));
    setContextMenu(null);
  };

  const handlePlayNext = (track: Track) => {
    queue.addToQueueNext(track);
    toast.success(t("actions.willPlayNext"));
    setContextMenu(null);
  };

  const handlePlayNow = (track: Track) => {
    playbackReasonRef.current = "manual";
    currentTrackRef.current = null;
    queue.playNow(track);
    setContextMenu(null);
    setActiveTab("player");
    setShowQueue(false);
  };

  const handlePersistEphemeralTrack = useCallback(
    async (track: Track) => {
      try {
        const persisted = await queue.persistEphemeralTrack(track.id);
        if (persisted) {
          toast.success(t("actions.persistTrackSuccess"));
        } else {
          toast.error(t("actions.persistTrackFailed"));
        }
      } catch (error) {
        console.error("Error persisting track:", error);
        toast.error(t("actions.persistTrackFailed"));
      }
    },
    [queue, t],
  );

  const handleDeleteTrack = useCallback(
    async (track: Track) => {
      try {
        // Quitar de la lista paginada al instante (sin recargar la página) y
        // luego borrar de la DB nativa.
        paginatedSongs.removeLocally(track.id);
        await queue.removeFromLibrary(track.id);
        toast.success(t("actions.deleteTrackSuccess"));
      } catch (error) {
        console.error("Error deleting track:", error);
        toast.error(t("actions.errorLoadingTrack"));
        paginatedSongs.reload();
      }
    },
    [paginatedSongs, queue, t],
  );

  const handleShufflePlay = (tracks: Track[]) => {
    if (tracks.length === 0) {
      toast.error(t("actions.noSongsToPlay"));
      return;
    }
    const candidates = tracks.length > 1 && nowPlayingTrack
      ? tracks.filter((track) => track.id !== nowPlayingTrack.id)
      : tracks;
    const randomIndex = Math.floor(Math.random() * candidates.length);
    const randomTrack = candidates[randomIndex] ?? tracks[0];
    console.info("[SHUFFLE_REQUEST]", {
      randomIndex,
      randomTrackId: randomTrack.id,
      randomTitle: randomTrack.title,
      previousNowPlayingId: nowPlayingTrack?.id,
    });
    playbackReasonRef.current = "shuffle";
    currentTrackRef.current = null;
    queue.shuffleAll(tracks, randomTrack.id);
    toast.success(t("actions.playingShuffled", { count: tracks.length }));
    setActiveTab("player");
    setShowQueue(false);
  };

  const handlePlayInOrder = (tracks: Track[]) => {
    if (tracks.length === 0) {
      toast.error(t("actions.noSongsToPlay"));
      return;
    }
    playbackReasonRef.current = "manual-order";
    currentTrackRef.current = null;
    queue.playAllInOrder(tracks);
    toast.success(t("actions.playingAll", { count: tracks.length }));
    setActiveTab("player");
    setShowQueue(false);
  };

  // Playlist handlers
  const handleCreatePlaylist = async () => {
    if (!newPlaylistName.trim()) return;
    await playlistManager.createPlaylist(newPlaylistName.trim());
    toast.success(t("playlists.created"));
    setNewPlaylistName("");
    setShowCreatePlaylist(false);
  };

  const handleRenamePlaylist = async () => {
    if (!selectedPlaylist || !newPlaylistName.trim()) return;
    await playlistManager.renamePlaylist(
      selectedPlaylist.id,
      newPlaylistName.trim(),
    );
    setSelectedPlaylist({ ...selectedPlaylist, name: newPlaylistName.trim() });
    toast.success(t("playlists.renamed"));
    setNewPlaylistName("");
    setShowRenamePlaylist(false);
    setPlaylistMenu(null);
  };

  const handleDeletePlaylist = async () => {
    if (!selectedPlaylist) return;
    await playlistManager.deletePlaylist(selectedPlaylist.id);
    toast.success(t("playlists.deleted"));
    setSelectedPlaylist(null);
    setShowDeletePlaylist(false);
    setPlaylistMenu(null);
    setLibraryView("playlists");
  };

  const handleAddToPlaylist = async (playlistId: string, track: Track) => {
    await playlistManager.addTrackToPlaylist(playlistId, track.id);
    toast.success(t("playlists.songAdded"));
    setShowAddToPlaylist(null);
  };

  const handleRemoveFromPlaylist = async (track: Track) => {
    if (!selectedPlaylist) return;
    await playlistManager.removeTrackFromPlaylist(
      selectedPlaylist.id,
      track.id,
    );
    // Update local state
    const updatedPlaylist = playlistManager.playlists.find(
      (p) => p.id === selectedPlaylist.id,
    );
    if (updatedPlaylist) {
      setSelectedPlaylist(updatedPlaylist);
    }
    toast.success(t("playlists.songRemoved"));
  };

  // Handler para abrir modal de selección de playlist desde cualquier canción
  const handleOpenAddToPlaylist = (track: Track) => {
    setShowAddToPlaylist(track);
  };

  // Handler para agregar canción a la playlist seleccionada (desde el modal dentro de playlist-detail)
  const handleAddSongToSelectedPlaylist = async (track: Track) => {
    if (!selectedPlaylist) return;

    // Check if already in playlist
    if (selectedPlaylist.trackIds.includes(track.id)) {
      toast.error(t("duplicates.alreadyInPlaylist"));
      return;
    }

    await playlistManager.addTrackToPlaylist(selectedPlaylist.id, track.id);
    toast.success(t("playlists.songAdded"));
  };

  // Touch reorder state
  const [touchStart, setTouchStart] = useState<{
    index: number;
    y: number;
  } | null>(null);
  const isRestoringNavigationRef = useRef(false);
  const lastNavigationSnapshotRef = useRef<HomeNavigationSnapshot | null>(null);

  const buildNavigationSnapshot = useCallback(
    (): HomeNavigationSnapshot => ({
      activeTab,
      libraryView,
      showQueue,
      showEqAutoModal,
      showDspAutoModal,
      showCreatePlaylist,
      showRenamePlaylist,
      showDeletePlaylist,
      showAddToPlaylist: !!showAddToPlaylist,
      showAddSongsToPlaylist,
      showOnboarding,
      onboardingStep,
      selectedPlaylistId: selectedPlaylist?.id ?? null,
      contextMenuOpen: !!contextMenu,
      playlistMenuOpen: !!playlistMenu,
      duplicatesModalOpen: showDuplicatesModal.length > 0,
    }),
    [
      activeTab,
      libraryView,
      showQueue,
      showEqAutoModal,
      showDspAutoModal,
      showCreatePlaylist,
      showRenamePlaylist,
      showDeletePlaylist,
      showAddToPlaylist,
      showAddSongsToPlaylist,
      showOnboarding,
      onboardingStep,
      selectedPlaylist,
      contextMenu,
      playlistMenu,
      showDuplicatesModal,
    ],
  );

  const applyNavigationSnapshot = useCallback(
    (snapshot: HomeNavigationSnapshot) => {
      isRestoringNavigationRef.current = true;

      setActiveTab(snapshot.activeTab);
      setLibraryView(snapshot.libraryView);
      setShowQueue(snapshot.showQueue);
      setShowEqAutoModal(snapshot.showEqAutoModal);
      setShowDspAutoModal(snapshot.showDspAutoModal);
      setShowCreatePlaylist(snapshot.showCreatePlaylist);
      setShowRenamePlaylist(snapshot.showRenamePlaylist);
      setShowDeletePlaylist(snapshot.showDeletePlaylist);
      setShowAddSongsToPlaylist(snapshot.showAddSongsToPlaylist);
      setShowOnboarding(snapshot.showOnboarding);
      setOnboardingStep(snapshot.onboardingStep);

      if (!snapshot.showAddToPlaylist) {
        setShowAddToPlaylist(null);
      }
      if (!snapshot.contextMenuOpen) {
        setContextMenu(null);
      }
      if (!snapshot.playlistMenuOpen) {
        setPlaylistMenu(null);
      }
      if (!snapshot.duplicatesModalOpen) {
        setShowDuplicatesModal([]);
      }

      const snapshotPlaylist = snapshot.selectedPlaylistId
        ? (playlistManager.playlists.find(
            (playlist) => playlist.id === snapshot.selectedPlaylistId,
          ) ?? null)
        : null;

      if (snapshot.libraryView === "playlist-detail" && !snapshotPlaylist) {
        setLibraryView("playlists");
      }

      setSelectedPlaylist(snapshotPlaylist);

      window.setTimeout(() => {
        isRestoringNavigationRef.current = false;
      }, 0);
    },
    [playlistManager.playlists],
  );

  useEffect(() => {
    const initialSnapshot = buildNavigationSnapshot();
    lastNavigationSnapshotRef.current = initialSnapshot;

    window.history.replaceState(
      {
        ...(window.history.state ?? {}),
        [HOME_NAVIGATION_STATE_KEY]: initialSnapshot,
      },
      "",
    );
  }, []);

  useEffect(() => {
    if (isRestoringNavigationRef.current) return;

    const nextSnapshot = buildNavigationSnapshot();
    const previousSnapshot = lastNavigationSnapshotRef.current;

    if (
      previousSnapshot &&
      JSON.stringify(previousSnapshot) === JSON.stringify(nextSnapshot)
    ) {
      return;
    }

    lastNavigationSnapshotRef.current = nextSnapshot;
    window.history.pushState(
      {
        ...(window.history.state ?? {}),
        [HOME_NAVIGATION_STATE_KEY]: nextSnapshot,
      },
      "",
    );
  }, [buildNavigationSnapshot]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const navigationSnapshot = event.state?.[HOME_NAVIGATION_STATE_KEY] as
        | HomeNavigationSnapshot
        | undefined;

      if (!navigationSnapshot) {
        return;
      }

      lastNavigationSnapshotRef.current = navigationSnapshot;
      applyNavigationSnapshot(navigationSnapshot);
    };

    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, [applyNavigationSnapshot]);

  const dspControls = useMemo<DspParamConfig[]>(
    () => [
      {
        key: "sweepFreq",
        label: t("dsp.sweep"),
        value: dspParams.sweepFreq,
        min: 27,
        max: 63,
        step: 1,
        unit: " Hz",
        onChange: (value) => updateDspParam("sweepFreq", value),
        // Sweep/Width/Balance pertenecen al motor clásico: en modo Audífonos
        // solo aplica Intensidad, así que se deshabilitan.
        disabled: !epicenterEnabled || epicenterMode === "headphones",
      },
      {
        key: "width",
        label: t("dsp.width"),
        value: dspParams.width,
        min: 0,
        max: 100,
        step: 1,
        unit: "%",
        onChange: (value) => updateDspParam("width", value),
        disabled: !epicenterEnabled || epicenterMode === "headphones",
      },
      {
        key: "intensity",
        label: t("dsp.intensity"),
        value: dspParams.intensity,
        min: 0,
        max: 100,
        step: 1,
        unit: "%",
        onChange: (value) => updateDspParam("intensity", value),
        disabled: !epicenterEnabled,
      },
      {
        key: "balance",
        label: t("dsp.balance"),
        value: dspParams.balance,
        min: 0,
        max: 100,
        step: 1,
        unit: "%",
        onChange: (value) => updateDspParam("balance", value),
        disabled: !epicenterEnabled || epicenterMode === "headphones",
      },
      {
        key: "volume",
        label: t("dsp.volume"),
        value: dspParams.volume,
        min: 0,
        max: 100,
        step: 1,
        unit: "%",
        onChange: (value) => updateDspParam("volume", value),
      },
    ],
    [dspParams, epicenterEnabled, epicenterMode, t, updateDspParam],
  );

  useEffect(() => {
    if (!["dsp", "eq", "fx"].includes(activeTab)) return;

    requestAnimationFrame(() => {
      window.scrollTo({ top: 0, left: 0, behavior: "auto" });
    });
  }, [activeTab]);

  useEffect(() => {
    if (activeTab !== "player") return;

    const originalOverflow = document.body.style.overflow;
    const originalOverscroll = document.body.style.overscrollBehavior;
    document.body.style.overflow = "hidden";
    document.body.style.overscrollBehavior = "none";

    return () => {
      document.body.style.overflow = originalOverflow;
      document.body.style.overscrollBehavior = originalOverscroll;
    };
  }, [activeTab]);

  return (
    <div className="epicenter-shell min-h-screen flex flex-col bg-black text-white">
      <TrackContextMenu
        contextMenu={contextMenu}
        t={t}
        onClose={() => setContextMenu(null)}
        onPlayNow={handlePlayNow}
        onPlayNext={handlePlayNext}
        onAddToQueue={handleAddToQueue}
        onAddToPlaylist={(track) => {
          setShowAddToPlaylist(track);
          setContextMenu(null);
        }}
      />

      <PlaylistContextMenu
        playlistMenu={playlistMenu}
        t={t}
        onClose={() => setPlaylistMenu(null)}
        onRename={(playlist) => {
          setSelectedPlaylist(playlist);
          setNewPlaylistName(playlist.name);
          setShowRenamePlaylist(true);
        }}
        onDelete={(playlist) => {
          setSelectedPlaylist(playlist);
          setShowDeletePlaylist(true);
        }}
      />

      <PlaylistNameModal
        isOpen={showCreatePlaylist}
        title={t("playlists.createNew")}
        confirmLabel={t("playlists.create")}
        cancelLabel={t("common.cancel")}
        playlistName={newPlaylistName}
        placeholder={t("playlists.enterName")}
        onPlaylistNameChange={setNewPlaylistName}
        onClose={() => {
          setShowCreatePlaylist(false);
          setNewPlaylistName("");
        }}
        onConfirm={handleCreatePlaylist}
      />

      <PlaylistNameModal
        isOpen={showRenamePlaylist && !!selectedPlaylist}
        title={t("playlists.rename")}
        confirmLabel={t("common.save")}
        cancelLabel={t("common.cancel")}
        playlistName={newPlaylistName}
        placeholder={t("playlists.enterName")}
        onPlaylistNameChange={setNewPlaylistName}
        onClose={() => {
          setShowRenamePlaylist(false);
          setNewPlaylistName("");
          setPlaylistMenu(null);
        }}
        onConfirm={handleRenamePlaylist}
      />

      <DeletePlaylistModal
        isOpen={showDeletePlaylist && !!selectedPlaylist}
        t={t}
        onClose={() => {
          setShowDeletePlaylist(false);
          setPlaylistMenu(null);
        }}
        onConfirm={handleDeletePlaylist}
      />

      <AddToPlaylistModal
        track={showAddToPlaylist}
        playlists={playlistManager.playlists}
        t={t}
        onClose={() => setShowAddToPlaylist(null)}
        onSelect={handleAddToPlaylist}
      />

      <DuplicatesModal
        duplicateFileNames={showDuplicatesModal}
        t={t}
        onClose={() => setShowDuplicatesModal([])}
      />

      <OnboardingModal
        isOpen={showOnboarding}
        t={t}
        steps={onboardingSteps}
        currentStep={onboardingStep}
        onClose={dismissOnboarding}
        onPrevious={() => setOnboardingStep((prev) => Math.max(prev - 1, 0))}
        onNext={() =>
          setOnboardingStep((prev) =>
            Math.min(prev + 1, onboardingSteps.length - 1),
          )
        }
      />

      <AddSongsToPlaylistModal
        isOpen={showAddSongsToPlaylist}
        selectedPlaylist={selectedPlaylist}
        library={queue.library}
        t={t}
        onClose={() => setShowAddSongsToPlaylist(false)}
        onAddTrack={handleAddSongToSelectedPlaylist}
      />

      <HomePlayerView
        isVisible={activeTab === "player"}
        t={t}
        showQueue={showQueue}
        onToggleQueue={() => setShowQueue(!showQueue)}
        onCloseQueue={() => setShowQueue(false)}
        onOpenFilePicker={handleFileSelect}
        queue={{
          queue: queue.queue,
          currentTrack: nowPlayingTrack,
          currentTrackIndex: nowPlayingTrack
            ? queue.queue.findIndex((track) => track.id === nowPlayingTrack.id)
            : queue.currentTrackIndex,
          playTrack: (index: number) => {
            playbackReasonRef.current = "manual";
            queue.playTrack(index);
          },
          removeFromQueue: queue.removeFromQueue,
          reorderQueue: queue.reorderQueue,
          previousTrack: () => {
            playbackReasonRef.current = "previous";
            queue.previousTrack();
          },
          nextTrack: () => {
            playbackReasonRef.current = "next";
            queue.nextTrack();
          },
        }}
        audioProcessor={{
          currentTime: audioProcessor.currentTime,
          duration: audioProcessor.duration,
          isPlaying: audioProcessor.isPlaying,
          seek: audioProcessor.seek,
          pause: audioProcessor.pause,
          play: audioProcessor.play,
          getAnalyserNode: audioProcessor.getAnalyserNode,
        }}
        draggedIndex={draggedIndex}
        onDraggedIndexChange={setDraggedIndex}
        touchStart={touchStart}
        onTouchStartChange={setTouchStart}
        formatTime={formatTime}
        hiresAudioBadgeUrl={hiresAudioBadgeUrl}
        epicenterEnabled={epicenterEnabled}
      />

      {activeTab === "library" && (
        <HomeLibraryView
          t={t}
          libraryView={libraryView}
          setLibraryView={setLibraryView}
          queueLibrary={queue.library}
          queueIsLoading={queue.isLoading}
          importIsImporting={queue.importProgress.isImporting}
          playlists={playlistManager.playlists}
          selectedPlaylist={selectedPlaylist}
          setSelectedPlaylist={setSelectedPlaylist}
          hiResTracks={hiResTracks}
          songsByArtist={songsByArtist}
          albums={albums}
          sortedSongs={songsForDisplay}
          songsPlayAll={sortedSongs}
          songsPaginated={songsPaginated}
          songsHasMore={paginatedSongs.hasMore}
          songsTotal={paginatedSongs.total}
          songsLoading={paginatedSongs.isLoading}
          songsNextPageCount={paginatedSongs.nextPageCount}
          onLoadMoreSongs={paginatedSongs.loadMore}
          songSort={songSort}
          setSongSort={setSongSort}
          visibleSongsCount={visibleSongsCount}
          setVisibleSongsCount={setVisibleSongsCount}
          visibleArtistsCount={visibleArtistsCount}
          setVisibleArtistsCount={setVisibleArtistsCount}
          playlistMenu={playlistMenu}
          setPlaylistMenu={setPlaylistMenu}
          onCreatePlaylist={() => setShowCreatePlaylist(true)}
          onOpenFilePicker={handleFileSelect}
          onImportMediaStoreTracks={handleMediaStoreImport}
          onPlayNow={handlePlayNow}
          onAddToQueue={handleAddToQueue}
          onPlayNext={handlePlayNext}
          onAddToPlaylist={handleOpenAddToPlaylist}
          onPlayInOrder={handlePlayInOrder}
          onShufflePlay={handleShufflePlay}
          onOpenAddToPlaylist={handleOpenAddToPlaylist}
          onPersistEphemeralTrack={handlePersistEphemeralTrack}
          onDeleteTrack={handleDeleteTrack}
          onOpenAddSongsToPlaylist={() => setShowAddSongsToPlaylist(true)}
          onOpenDeletePlaylist={(playlist) => {
            setSelectedPlaylist(playlist);
            setShowDeletePlaylist(true);
          }}
          onOpenRenamePlaylist={(playlist) => {
            setSelectedPlaylist(playlist);
            setNewPlaylistName(playlist.name);
            setShowRenamePlaylist(true);
          }}
          onRemoveFromPlaylist={handleRemoveFromPlaylist}
          hiresLogoUrl={hiresLogoUrl}
        />
      )}

      {activeTab === "search" && (
        <HomeSearchView
          t={t}
          globalSearchQuery={globalSearchQuery}
          setGlobalSearchQuery={setGlobalSearchQuery}
          normalizedGlobalQuery={normalizedGlobalQuery}
          globalResults={globalResults}
          onPlayNow={handlePlayNow}
          onAddToQueue={handleAddToQueue}
          onPlayNext={handlePlayNext}
          onAddToPlaylist={handleOpenAddToPlaylist}
        />
      )}

      {activeTab === "eq" && (
        <HomeEqView
          t={t}
          eqEnabled={audioProcessor.eqEnabled}
          eqBands={audioProcessor.eqBands}
          onToggleEq={toggleEq}
          onOpenAutoModal={() => setShowEqAutoModal(true)}
          onSetEqBandGain={audioProcessor.setEqBandGain}
          onResetEq={() =>
            audioProcessor.eqBands.forEach((_, index) =>
              audioProcessor.setEqBandGain(index, 0),
            )
          }
        />
      )}

      {activeTab === "dsp" && (
        <HomeDspView
          t={t}
          epicenterEnabled={epicenterEnabled}
          epicenterMode={epicenterMode}
          onChangeEpicenterMode={changeEpicenterMode}
          params={dspControls}
          onOpenAutoModal={() => setShowDspAutoModal(true)}
          onToggleEpicenter={toggleEpicenter}
          onOpenEq={() => setActiveTab("eq")}
          onOpenFx={() => setActiveTab("fx")}
        />
      )}

      {activeTab === "fx" && (
        <HomeFxView
          t={t}
          reverbEnabled={audioProcessor.spatialEffects.reverbEnabled}
          reverbAmount={audioProcessor.spatialEffects.reverbAmount}
          concertHallEnabled={audioProcessor.spatialEffects.concertHallEnabled}
          concertHallAmount={audioProcessor.spatialEffects.concertHallAmount}
          onToggleReverb={audioProcessor.setReverbEnabled}
          onReverbAmountChange={audioProcessor.setReverbAmount}
          onToggleConcertHall={audioProcessor.setConcertHallEnabled}
          onConcertHallAmountChange={audioProcessor.setConcertHallAmount}
        />
      )}

      {showEqAutoModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-6">
          <div className="bg-zinc-900 rounded-2xl p-6 w-full max-w-md border border-zinc-800 space-y-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-lg font-bold">{t("eq.autoTitle")}</h3>
                <p className="text-sm text-zinc-400 mt-1">
                  {t("eq.autoDescription")}
                </p>
              </div>
              <button
                onClick={() => setShowEqAutoModal(false)}
                className="text-zinc-500 hover:text-white"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="flex items-center justify-between p-3 bg-zinc-800/50 rounded-xl">
              <p className="text-sm text-zinc-300">{t("eq.autoEnable")}</p>
              <Switch
                checked={eqAutoEnabled}
                onCheckedChange={setEqAutoEnabled}
              />
            </div>
            <Button
              onClick={() => {
                runAutoOptimization(true);
                setShowEqAutoModal(false);
              }}
              className="w-full bg-white text-black hover:bg-zinc-200"
            >
              {t("eq.autoApplyNow")}
            </Button>
          </div>
        </div>
      )}

      {showDspAutoModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-6">
          <div className="bg-zinc-900 rounded-2xl p-6 w-full max-w-md border border-zinc-800 space-y-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-lg font-bold">{t("dsp.autoTitle")}</h3>
                <p className="text-sm text-zinc-400 mt-1">
                  {t("dsp.autoDescription")}
                </p>
              </div>
              <button
                onClick={() => setShowDspAutoModal(false)}
                className="text-zinc-500 hover:text-white"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="flex items-center justify-between p-3 bg-zinc-800/50 rounded-xl">
              <p className="text-sm text-zinc-300">{t("dsp.autoEnable")}</p>
              <Switch
                checked={dspAutoEnabled}
                onCheckedChange={setDspAutoEnabled}
              />
            </div>
            <Button
              onClick={() => {
                runAutoOptimization(true);
                setShowDspAutoModal(false);
              }}
              className="w-full bg-white text-black hover:bg-zinc-200"
            >
              {t("dsp.autoApplyNow")}
            </Button>
          </div>
        </div>
      )}

      {activeTab === "settings" && (
        <HomeSettingsView
          t={t}
          switchable={switchable}
          theme={theme}
          toggleTheme={toggleTheme}
          language={language}
          setLanguage={setLanguage}
          crossfadeEnabled={crossfade.enabled}
          crossfadeDuration={crossfade.duration}
          onCrossfadeEnabledChange={crossfade.setEnabled}
          onCrossfadeDurationChange={crossfade.setDuration}
        />
      )}


      {isLibraryStabilizing && (
        <LibraryPreparingOverlay
          done={libraryPrepDone}
          onClose={closeLibraryPreparing}
        />
      )}

      {reviewPrompt.shouldShow && (
        <ReviewPrompt
          t={t}
          onRate={reviewPrompt.rate}
          onLater={reviewPrompt.later}
          onNever={reviewPrompt.never}
        />
      )}

      <HomeImportProgressOverlay t={t} importProgress={queue.importProgress} />

      {activeTab !== "player" && (
        <PremiumMiniPlayer
          track={nowPlayingTrack}
          isPlaying={audioProcessor.isPlaying}
          currentTime={audioProcessor.currentTime}
          duration={audioProcessor.duration}
          onPlay={audioProcessor.play}
          onPause={audioProcessor.pause}
          onOpenPlayer={() => setActiveTab("player")}
        />
      )}

      {/* Bottom Navigation */}
      <BottomNavigation
        activeTab={activeTab}
        onTabChange={setActiveTab}
        onLibraryTab={() => {
          setActiveTab("library");
          setLibraryView("main");
        }}
        eqEnabled={audioProcessor.eqEnabled}
        epicenterEnabled={epicenterEnabled}
        spatialEffectsEnabled={
          audioProcessor.spatialEffects.reverbEnabled ||
          audioProcessor.spatialEffects.concertHallEnabled
        }
        t={t}
      />
      <div className={activeTab === "player" ? "h-0" : "h-32"} />
    </div>
  );
}
