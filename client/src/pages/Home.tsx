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
import { usePresetPersistence } from "@/hooks/usePresetPersistence";
import { useMediaSession } from "@/hooks/useMediaSession";
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
import { useHomePlaylists } from "@/hooks/useHomePlaylists";
import { useHomeQueueActions } from "@/hooks/useHomeQueueActions";
import { useHomeNavigation } from "@/hooks/useHomeNavigation";
import { useHomeLibraryData } from "@/hooks/useHomeLibraryData";
import { HomePlayerView } from "@/components/home/HomePlayerView";
import { HomeSearchView } from "@/components/home/HomeSearchView";
import { HomeSettingsView } from "@/components/home/HomeSettingsView";
import {
  type DspParamConfig,
  type HomeLibraryView as LibraryView,
  type HomeTabType as TabType,
} from "@/components/home/types";
import { useLanguage } from "@/hooks/useLanguage";
import { hiresAudioBadgeUrl, hiresLogoUrl } from "@/lib/assetUrls";
import { toast } from "sonner";

type ResolvedTrackPlaybackSource = {
  source: File | string;
};

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
  const crossfade = useCrossfade();
  const lastTrack = useLastTrack();
  const { t, language, setLanguage } = useLanguage();
  const { theme, toggleTheme, switchable } = useTheme();

  const [activeTab, setActiveTab] = useState<TabType>("player");
  const [libraryView, setLibraryView] = useState<LibraryView>("main");
  const returnToPlaylistList = useCallback(() => setLibraryView("playlists"), []);
  const playlistController = useHomePlaylists(queue.library, t, returnToPlaylistList);
  const {
    playlistManager,
    selectedPlaylist,
    setSelectedPlaylist,
    showCreatePlaylist,
    setShowCreatePlaylist,
    showRenamePlaylist,
    setShowRenamePlaylist,
    showDeletePlaylist,
    setShowDeletePlaylist,
    showAddToPlaylist,
    setShowAddToPlaylist,
    showAddSongsToPlaylist,
    setShowAddSongsToPlaylist,
    newPlaylistName,
    setNewPlaylistName,
    playlistMenu,
    setPlaylistMenu,
    handleCreatePlaylist,
    handleRenamePlaylist,
    handleDeletePlaylist,
    handleAddToPlaylist,
    handleRemoveFromPlaylist,
    handleOpenAddToPlaylist,
    handleAddSongToSelectedPlaylist,
  } = playlistController;
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
  const lastPositionSyncRef = useRef(0);

  const openPlayer = useCallback(() => {
    setActiveTab("player");
    setShowQueue(false);
  }, []);
  const {
    handleAddToQueue,
    handlePlayNext,
    handlePlayNow,
    handleShufflePlay,
    handlePlayInOrder,
  } = useHomeQueueActions({
    queue,
    t,
    nowPlayingTrack,
    playbackReasonRef,
    currentTrackRef,
    closeContextMenu: () => setContextMenu(null),
    openPlayer,
  });

  const { hiResTracks, sortedSongs, songsByArtist, albums } = useHomeLibraryData(
    queue.library,
    songSort,
    language,
    t,
  );

  useEffect(() => {
    setVisibleSongsCount(250);
    setVisibleArtistsCount(30);
  }, [songSort, queue.library.length]);

  const songsForDisplay = sortedSongs;

  const closeLibraryPreparing = useCallback(() => {
    setIsLibraryStabilizing(false);
    setLibraryPrepDone(false);
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

  // Configurar los controles de Media Session del navegador.
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

  }, [audioProcessor, queue, mediaSession]);

  // Actualizar metadatos en Media Session cuando cambia el track
  useEffect(() => {
    if (nowPlayingTrack) {
      mediaSession.updateMetadata({
        title: nowPlayingTrack.title,
        artist: nowPlayingTrack.artist,
        artwork: nowPlayingTrack.coverUrl,
      });

    }
  }, [nowPlayingTrack, mediaSession]);

  // Actualizar estado de reproducción
  useEffect(() => {
    mediaSession.updatePlaybackState(
      audioProcessor.isPlaying ? "playing" : "paused",
    );
  }, [
    audioProcessor.isPlaying,
    mediaSession,
  ]);

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
  }, [
    audioProcessor.currentTime,
    audioProcessor.duration,
    mediaSession,
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
      if (track.unavailable) throw new Error("Track source not available");
      if (track.sourceUri && /^(https?|blob):/i.test(track.sourceUri)) {
        return { source: track.sourceUri };
      }
      const trackFile = track.file ?? await queue.getTrackFile(track);
      if (!trackFile) {
        throw new Error("Track source not available");
      }
      console.debug("[PlaybackSource] restored audio file", {
        requestId: context?.requestId ?? trackLoadRequestRef.current,
        reason: context?.reason,
        trackId: track.id,
      });
      return { source: trackFile };
    },
    [queue.getTrackFile],
  );

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

  useEffect(() => {
    return () => {
      trackLoadRequestRef.current += 1;
      clearPendingPlaybackTimers();
      clearLibraryStabilizationTimer();
    };
  }, [clearLibraryStabilizationTimer, clearPendingPlaybackTimers]);

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
          const { source } = await resolveTrackSource(requestedTrack, {
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

  const handleFileSelect = useCallback(() => {
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

    const input = document.createElement("input");
    input.type = "file";
    input.accept = "audio/*,.mp3,.wav,.flac,.ogg,.m4a,.aac";
    input.multiple = true;
    input.onchange = async (e) => {
      const files = Array.from((e.target as HTMLInputElement).files || []);
      if (files.length === 0) return;
      try {
        const result = await queue.addToLibrary(files);
        handleImportResult({ ...result, selectedCount: files.length, processedCount: files.length });
      } catch (error) {
        console.error("[AudioImport] File selection failed", error);
        toast.error(t("actions.errorAddingSongs"), {
          description: error instanceof Error ? error.message : undefined,
        });
      }
    };
    input.click();
  }, [queue, startLibraryStabilizationWait, t]);

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
        await queue.removeFromLibrary(track.id);
        toast.success(t("actions.deleteTrackSuccess"));
      } catch (error) {
        console.error("Error deleting track:", error);
        toast.error(t("actions.errorLoadingTrack"));
      }
    },
    [queue, t],
  );

  // Touch reorder state
  const [touchStart, setTouchStart] = useState<{
    index: number;
    y: number;
  } | null>(null);
  useHomeNavigation({
    activeTab,
    setActiveTab,
    libraryView,
    setLibraryView,
    showQueue,
    setShowQueue,
    showEqAutoModal,
    setShowEqAutoModal,
    showDspAutoModal,
    setShowDspAutoModal,
    showCreatePlaylist,
    setShowCreatePlaylist,
    showRenamePlaylist,
    setShowRenamePlaylist,
    showDeletePlaylist,
    setShowDeletePlaylist,
    showAddToPlaylist,
    setShowAddToPlaylist,
    showAddSongsToPlaylist,
    setShowAddSongsToPlaylist,
    showOnboarding,
    setShowOnboarding,
    onboardingStep,
    setOnboardingStep,
    selectedPlaylist,
    setSelectedPlaylist,
    playlists: playlistManager.playlists,
    contextMenu,
    setContextMenu,
    playlistMenu,
    setPlaylistMenu,
    showDuplicatesModal,
    setShowDuplicatesModal,
  });

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
          playlists={playlistManager.playlists}
          selectedPlaylist={selectedPlaylist}
          setSelectedPlaylist={setSelectedPlaylist}
          hiResTracks={hiResTracks}
          songsByArtist={songsByArtist}
          albums={albums}
          sortedSongs={songsForDisplay}
          songsPlayAll={sortedSongs}
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
