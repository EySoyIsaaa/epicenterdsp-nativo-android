/**
 * useAndroidNativeAudioProcessor
 *
 * Hook Android NATIVE-ONLY. Implementa la interfaz `IntegratedAudioController`
 * pero TODA la reproducción y procesamiento Epicenter ocurren del lado nativo:
 *
 *   EpicenterNative (Capacitor plugin)
 *     -> NativePlaybackController (ExoPlayer/Media3)
 *     -> EpicenterAudioProcessor (Media3 AudioProcessor)
 *     -> EpicenterDSPNative (JNI)
 *     -> EpicenterDSPCore.cpp (C++/NDK)
 *     -> Audio output Android
 *
 * IMPORTANTE:
 * - No se crea AudioContext / new Audio() / AudioWorklet.
 * - No hay fallback WebAudio si el nativo falla. Se emite error al callback de UI.
 * - EQ / FX / crossfade son state-only stubs (se persisten en UI pero no procesan).
 *   La implementación nativa de EQ/FX llegará en una fase posterior.
 * - Source `File` (blob URL) no es válido para ExoPlayer => se rechaza con error claro.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  EpicenterNative,
  type EpicenterParams,
  type NativeEpicenterMode,
  type NativePlaybackStateEvent,
  type NativeTrackLoadInput,
  type NativePlayerErrorEvent,
} from '@/native/epicenterNativeAndroid';
import {
  DEFAULT_EQ_BANDS,
  EQ_GAIN_MIN,
  EQ_GAIN_MAX,
  type CrossfadeConfig,
  type EqualizerBand,
  type IntegratedAudioController,
  type LoadFileRequestGuard,
  type LoadFileTrackMetadata,
  type SpatialEffectsConfig,
  type StreamingParams,
} from './useIntegratedAudioProcessor';

const TAG = '[NativeOnly]';

const clampStreamingParam = (name: keyof StreamingParams, value: number): number => {
  switch (name) {
    case 'sweepFreq':
      return Math.max(27, Math.min(63, value));
    case 'width':
    case 'intensity':
    case 'balance':
    case 'volume':
      return Math.max(0, Math.min(100, value));
    default:
      return value;
  }
};

const inferTrackIdFromSource = (source: string): string => {
  try {
    const url = new URL(source);
    return url.pathname.split('/').pop() || source;
  } catch {
    return source;
  }
};

const PLAYBACK_STATE_ENDED = 4;

// El modo del Epicenter se recuerda entre sesiones. Se lee de forma tolerante:
// cualquier valor desconocido cae en "car", que es el comportamiento histórico.
const EPICENTER_MODE_STORAGE_KEY = 'epicenter-mode';

const readStoredEpicenterMode = (): NativeEpicenterMode => {
  try {
    return localStorage.getItem(EPICENTER_MODE_STORAGE_KEY) === 'headphones'
      ? 'headphones'
      : 'car';
  } catch {
    return 'car';
  }
};

export function useAndroidNativeAudioProcessor(): IntegratedAudioController {
  // === Source-of-truth STATE (driven by native events) ===
  const [isReady, setIsReady] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);

  // === UI-only stubs (EQ / Spatial FX llegarán nativos en fase posterior) ===
  const [eqBands, setEqBands] = useState<EqualizerBand[]>(DEFAULT_EQ_BANDS);
  const eqBandsRef = useRef<EqualizerBand[]>(DEFAULT_EQ_BANDS);
  const eqDispatchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [eqEnabled, setEqEnabledState] = useState(true);
  const [epicenterEnabled, setEpicenterEnabledState] = useState(false);
  const [epicenterMode, setEpicenterModeState] = useState<NativeEpicenterMode>(
    readStoredEpicenterMode,
  );
  // Ref espejo: el bloque de init corre una sola vez y necesita el modo vigente
  // sin volver a crearse cuando cambia el estado.
  const epicenterModeRef = useRef<NativeEpicenterMode>(epicenterMode);
  epicenterModeRef.current = epicenterMode;
  const [spatialEffects, setSpatialEffects] = useState<SpatialEffectsConfig>({
    reverbEnabled: false,
    reverbAmount: 35,
    concertHallEnabled: false,
    concertHallAmount: 45,
  });

  // === Internal refs ===
  const dspParamsRef = useRef<StreamingParams>({
    sweepFreq: 45,
    width: 50,
    intensity: 100,
    balance: 100,
    volume: 100,
  });
  const onTrackEndedRef = useRef<(() => void) | null>(null);
  const onTrackErrorRef = useRef<((error: Error) => void) | null>(null);
  const onNativeTrackAdvancedRef = useRef<((index: number, id: string) => void) | null>(null);
  const onNotificationCommandRef = useRef<((action: string) => void) | null>(null);
  const crossfadeConfigRef = useRef<CrossfadeConfig>({ enabled: false, duration: 5 });
  const activeSourceRef = useRef<string>('');
  // Set by trackChanged when native auto-advances (queue mode). Consumed by loadFile()
  // to skip a redundant loadTrack() call when JS re-renders due to index sync.
  const nativeAutoAdvancedSourceRef = useRef<string | null>(null);
  const lastReportedEndedRef = useRef<string | null>(null);
  const initializedRef = useRef(false);
  const initPromiseRef = useRef<Promise<void> | null>(null);

  // === Initialize native engine once ===
  const ensureInitialized = useCallback(async () => {
    if (initializedRef.current) return;
    if (initPromiseRef.current) return initPromiseRef.current;

    initPromiseRef.current = (async () => {
      try {
        console.info(`${TAG} Android playback uses EpicenterNative`);
        console.info(`${TAG} WebAudio engine disabled on Android`);
        await EpicenterNative.initialize();
        // Habilitar modo nativo siempre que se monte la UI Android.
        await EpicenterNative.setNativeModeEnabled({ enabled: true });
        // Aplicar parámetros DSP iniciales para que el processor tenga estado coherente.
        await EpicenterNative.setEpicenterParams({
          intensity: dspParamsRef.current.intensity,
          sweepFreq: dspParamsRef.current.sweepFreq,
          width: dspParamsRef.current.width,
          balance: dspParamsRef.current.balance,
          volume: dspParamsRef.current.volume,
        });
        // Restaurar el motor elegido (Car Audio / Audífonos). El nativo siempre
        // arranca en "car", así que sin esto el modo guardado se perdería.
        await EpicenterNative.setEpicenterMode({ mode: epicenterModeRef.current });
        // FASE 6: sincronizar estado inicial del EQ con la UI.
        // La UI defaultea eqEnabled=true (matching web). Native arranca en false
        // por seguridad — aquí lo alineamos.
        await EpicenterNative.setEqEnabled({ enabled: true });
        initializedRef.current = true;
        console.info(`${TAG} native engine initialized`);
      } catch (error) {
        console.error(`${TAG} initialize failed`, error);
        const err = error instanceof Error ? error : new Error('native_init_failed');
        if (onTrackErrorRef.current) onTrackErrorRef.current(err);
      } finally {
        initPromiseRef.current = null;
      }
    })();

    return initPromiseRef.current;
  }, []);

  // Dispara init temprano (sin bloquear el render).
  useEffect(() => {
    void ensureInitialized();
  }, [ensureInitialized]);

  useEffect(() => () => {
    if (eqDispatchTimerRef.current !== null) {
      clearTimeout(eqDispatchTimerRef.current);
      eqDispatchTimerRef.current = null;
    }
  }, []);

  // === Subscribe to native events (source-of-truth) ===
  useEffect(() => {
    let mounted = true;
    const handles: Array<{ remove: () => Promise<void> }> = [];

    const handlePlaybackState = (event: NativePlaybackStateEvent) => {
      if (!mounted) return;
      setIsPlaying(!!event.isPlaying);
      if (typeof event.durationMs === 'number' && event.durationMs >= 0) {
        setDuration(event.durationMs / 1000);
      }
      if (typeof event.positionMs === 'number' && event.positionMs >= 0) {
        setCurrentTime(event.positionMs / 1000);
      }
      const ready = event.playbackState === 3 /* READY */ || event.playbackState === 4 /* ENDED */;
      setIsReady(ready);

      const trackId = event.currentTrack?.id ?? null;
      if (
        event.playbackState === PLAYBACK_STATE_ENDED &&
        trackId &&
        lastReportedEndedRef.current !== trackId
      ) {
        lastReportedEndedRef.current = trackId;
        console.info(`${TAG} track ended (native)`, trackId);
        if (onTrackEndedRef.current) onTrackEndedRef.current();
      }

      // Reset ended-dedupe cuando playback vuelve a READY o BUFFERING
      if (event.playbackState === 2 || event.playbackState === 3) {
        if (lastReportedEndedRef.current !== trackId) {
          lastReportedEndedRef.current = null;
        }
      }
    };

    const handleProgress = (event: NativePlaybackStateEvent) => {
      if (!mounted) return;
      if (typeof event.positionMs === 'number' && event.positionMs >= 0) {
        setCurrentTime(event.positionMs / 1000);
      }
      if (typeof event.durationMs === 'number' && event.durationMs >= 0) {
        setDuration(event.durationMs / 1000);
      }
    };

    const handleTrackChanged = (event: { id?: string; index?: number; source?: string }) => {
      if (!mounted) return;
      console.info(`${TAG} trackChanged (native) index=${event?.index} id=${event?.id}`);
      lastReportedEndedRef.current = null;
      // Store the source so loadFile() can detect a redundant reload after native auto-advance.
      if (event?.source) {
        nativeAutoAdvancedSourceRef.current = event.source;
        activeSourceRef.current = event.source;
      }
      // Notify caller (Home.tsx) so it can sync the JS queue index.
      if (onNativeTrackAdvancedRef.current && typeof event?.index === 'number') {
        onNativeTrackAdvancedRef.current(event.index, event?.id ?? '');
      }
    };

    const handlePlayerError = (event: NativePlayerErrorEvent) => {
      if (!mounted) return;
      console.error(`${TAG} nativePlayerError`, event);
      const message = event?.message || event?.code || 'native_playback_error';
      if (onTrackErrorRef.current) {
        onTrackErrorRef.current(new Error(message));
      }
    };

    const handleDspState = (event: unknown) => {
      // eslint-disable-next-line no-console
      console.debug(`${TAG} dsp state`, event);
    };

    // Skip buttons from the system media notification → forwarded here by the
    // native service. We delegate to the JS queue (via the registered callback)
    // so notification skip behaves exactly like the in-app next/previous.
    const handleNotificationCommand = (event: { action: string }) => {
      if (!mounted) return;
      if (onNotificationCommandRef.current) {
        onNotificationCommandRef.current(event?.action);
      }
    };

    (async () => {
      try {
        handles.push(
          await EpicenterNative.addListener('playbackStateChanged', handlePlaybackState),
        );
        handles.push(
          await EpicenterNative.addListener('progressChanged', handleProgress),
        );
        handles.push(
          await EpicenterNative.addListener('trackChanged', handleTrackChanged),
        );
        handles.push(
          await EpicenterNative.addListener('nativePlayerError', handlePlayerError),
        );
        handles.push(
          await EpicenterNative.addListener(
            'dspStateChanged',
            handleDspState as never,
          ),
        );
        handles.push(
          await EpicenterNative.addListener(
            'mediaNotificationCommand',
            handleNotificationCommand,
          ),
        );
      } catch (error) {
        console.error(`${TAG} addListener failed`, error);
      }
    })();

    return () => {
      mounted = false;
      for (const handle of handles) {
        void handle.remove().catch(() => undefined);
      }
    };
  }, []);

  // === Controller API ===

  const loadFile = useCallback(
    async (
      file: File | string,
      params: StreamingParams,
      requestGuard?: LoadFileRequestGuard,
      trackMetadata?: LoadFileTrackMetadata,
    ): Promise<boolean> => {
      const isCurrentRequest = () => requestGuard?.isCurrentRequest?.() ?? true;

      if (typeof file !== 'string') {
        console.error(
          `${TAG} loadFile got File object (blob URL not supported in native Android). Track must come from MediaStore/URI/path.`,
        );
        if (onTrackErrorRef.current) {
          onTrackErrorRef.current(
            new Error('android_native_requires_uri_source'),
          );
        }
        return false;
      }

      await ensureInitialized();
      if (!isCurrentRequest()) return false;

      const source = file;

      // Native auto-advanced to this source in queue mode — ExoPlayer is already
      // playing it. Skip loadTrack() to avoid interrupting gapless playback.
      if (source === nativeAutoAdvancedSourceRef.current) {
        nativeAutoAdvancedSourceRef.current = null;
        activeSourceRef.current = source;
        console.info(`${TAG} skipping loadTrack — native already advanced to this source`);
        setIsReady(true);
        return true;
      }
      nativeAutoAdvancedSourceRef.current = null;

      activeSourceRef.current = source;
      setIsReady(false);
      setCurrentTime(0);

      const fallbackId =
        trackMetadata?.id || inferTrackIdFromSource(source) || 'unknown-id';

      const trackInput: NativeTrackLoadInput = {
        id: fallbackId,
        title: trackMetadata?.title,
        artist: trackMetadata?.artist,
        album: trackMetadata?.album,
        duration: trackMetadata?.duration,
        uri: source,
        artworkUri: trackMetadata?.artworkUri,
      };

      // Persistimos los params recibidos para que setEpicenterEnabled posterior
      // pueda reaplicarlos sin perder el último valor de UI.
      dspParamsRef.current = {
        sweepFreq: clampStreamingParam('sweepFreq', params.sweepFreq),
        width: clampStreamingParam('width', params.width),
        intensity: clampStreamingParam('intensity', params.intensity),
        balance: clampStreamingParam('balance', params.balance),
        volume: clampStreamingParam('volume', params.volume),
      };

      try {
        // Sincronizar params DSP antes de cargar para que el siguiente buffer ya
        // los considere (no necesario para correctitud, pero útil para diagnóstico).
        await EpicenterNative.setEpicenterParams({
          intensity: dspParamsRef.current.intensity,
          sweepFreq: dspParamsRef.current.sweepFreq,
          width: dspParamsRef.current.width,
          balance: dspParamsRef.current.balance,
          volume: dspParamsRef.current.volume,
        });

        if (!isCurrentRequest()) return false;

        console.info(`${TAG} loadTrack sent to native`, {
          id: trackInput.id,
          title: trackInput.title,
          uri: source,
        });
        await EpicenterNative.loadTrack(trackInput);

        if (!isCurrentRequest()) return false;

        setIsReady(true);
        return true;
      } catch (error) {
        console.error(`${TAG} loadTrack failed`, error);
        if (onTrackErrorRef.current) {
          onTrackErrorRef.current(
            error instanceof Error ? error : new Error('native_load_failed'),
          );
        }
        return false;
      }
    },
    [ensureInitialized],
  );

  const play = useCallback((): boolean => {
    console.info(`${TAG} play sent to native`);
    void EpicenterNative.play().catch((error) => {
      console.error(`${TAG} play failed`, error);
      if (onTrackErrorRef.current) {
        onTrackErrorRef.current(
          error instanceof Error ? error : new Error('native_play_failed'),
        );
      }
    });
    // Update local state immediately for responsive UI.
    setIsPlaying(true);
    return true;
  }, []);

  const pause = useCallback(() => {
    void EpicenterNative.pause().catch((error) => {
      console.error(`${TAG} pause failed`, error);
    });
    setIsPlaying(false);
  }, []);

  const seek = useCallback((time: number) => {
    const safeTime = Math.max(0, time);
    setCurrentTime(safeTime);
    const targetMs = Math.round(safeTime * 1000);
    void EpicenterNative.seek({ positionMs: targetMs })
      .then((state) => {
        // Si el reproductor no quedó donde se le pidió, el salto no se aplicó
        // (típicamente porque el ítem no es buscable). Queda en el log para
        // poder diagnosticarlo sin adivinar.
        const after = (state as { seekPositionAfterMs?: number })?.seekPositionAfterMs;
        const seekable = (state as { seekable?: boolean })?.seekable;
        if (typeof after === 'number' && Math.abs(after - targetMs) > 2000) {
          console.warn(
            `${TAG} seek NO aplicado: pedido=${targetMs}ms quedó=${after}ms seekable=${seekable}`,
          );
        }
      })
      .catch((error) => {
        console.error(`${TAG} seek failed`, error);
      });
  }, []);

  const setDspParam = useCallback(
    (name: keyof StreamingParams, value: number) => {
      const clampedValue = clampStreamingParam(name, value);
      dspParamsRef.current = { ...dspParamsRef.current, [name]: clampedValue };
      const partial: EpicenterParams = { [name]: clampedValue } as EpicenterParams;
      void EpicenterNative.setEpicenterParams(partial).catch((error) => {
        console.error(`${TAG} setEpicenterParams ${name} failed`, error);
      });
    },
    [],
  );

  const setEqBandGain = useCallback((index: number, gain: number) => {
    const clampedGain = Math.max(EQ_GAIN_MIN, Math.min(EQ_GAIN_MAX, gain));
    if (index < 0 || index >= eqBandsRef.current.length) return;
    const next = [...eqBandsRef.current];
    next[index] = { ...next[index], gain: clampedGain };
    eqBandsRef.current = next;
    setEqBands(next);

    // Sliders and Auto Tune can update 31 bands in one UI tick. Coalesce them
    // into one Capacitor call and one immutable native coefficient snapshot.
    if (eqDispatchTimerRef.current === null) {
      eqDispatchTimerRef.current = setTimeout(() => {
        eqDispatchTimerRef.current = null;
        const bands = eqBandsRef.current.map((band) => band.gain);
        void EpicenterNative.setEqBands({ bands }).catch((error) => {
          console.error(`${TAG} setEqBands failed`, error);
        });
      }, 0);
    }
  }, []);

  const setEqEnabled = useCallback((enabled: boolean) => {
    setEqEnabledState(enabled);
    console.info(`${TAG} EQ enabled=${enabled}`);
    void EpicenterNative.setEqEnabled({ enabled }).catch((error) => {
      console.error(`${TAG} setEqEnabled failed`, error);
    });
  }, []);

  const setEqPreampDb = useCallback((preampDb: number) => {
    const clamped = Math.max(-24, Math.min(24, preampDb));
    console.info(`${TAG} EQ preamp=${clamped}dB`);
    void EpicenterNative.setEqPreamp({ preampDb: clamped }).catch((error) => {
      console.error(`${TAG} setEqPreamp failed`, error);
    });
  }, []);

  const setEpicenterEnabled = useCallback((enabled: boolean) => {
    setEpicenterEnabledState(enabled);
    void EpicenterNative.setEpicenterEnabled({ enabled }).catch((error) => {
      console.error(`${TAG} setEpicenterEnabled failed`, error);
    });
  }, []);

  const setEpicenterMode = useCallback((mode: NativeEpicenterMode) => {
    setEpicenterModeState(mode);
    try {
      localStorage.setItem(EPICENTER_MODE_STORAGE_KEY, mode);
    } catch (error) {
      console.warn(`${TAG} could not persist epicenter mode`, error);
    }
    console.info(`${TAG} epicenter mode=${mode}`);
    void EpicenterNative.setEpicenterMode({ mode }).catch((error) => {
      console.error(`${TAG} setEpicenterMode failed`, error);
    });
  }, []);

  const setOnTrackEnded = useCallback((callback: (() => void) | null) => {
    onTrackEndedRef.current = callback;
  }, []);

  const setOnTrackError = useCallback(
    (callback: ((error: Error) => void) | null) => {
      onTrackErrorRef.current = callback;
    },
    [],
  );

  const setCrossfadeConfig = useCallback((config: CrossfadeConfig) => {
    crossfadeConfigRef.current = config;
    // El fundido lo ejecuta el nativo: un temporizador de JS no correría con la
    // app en segundo plano, que es justo cuando más se nota el corte entre
    // canciones. Antes esto solo guardaba la config y no hacía nada.
    void EpicenterNative.setCrossfade({
      enabled: config.enabled,
      durationSeconds: config.duration,
    }).catch((error) => {
      console.error(`${TAG} setCrossfade failed`, error);
    });
  }, []);

  // === Cola nativa — background auto-advance ===

  const setNativeQueue = useCallback(
    (tracks: import('@/hooks/useAudioQueue').Track[], startIndex: number) => {
      const nativeTracks = tracks
        .map((t) => ({
          id: t.id,
          title: t.title,
          artist: t.artist,
          duration: t.duration,
          uri: t.sourceUri,
          artworkUri: t.albumArtUri,
        }))
        .filter((t): t is typeof t & { uri: string } => !!t.uri);

      if (nativeTracks.length === 0) return;
      const currentTrackId = tracks[startIndex]?.id;
      const mappedStart = currentTrackId
        ? nativeTracks.findIndex((track) => track.id === currentTrackId)
        : -1;
      const clampedStart = mappedStart >= 0
        ? mappedStart
        : Math.max(0, Math.min(startIndex, nativeTracks.length - 1));
      EpicenterNative.setQueue({ tracks: nativeTracks, startIndex: clampedStart }).catch((e) =>
        console.warn(`${TAG} setQueue failed`, e),
      );
    },
    [],
  );

  const setOnNativeTrackAdvanced = useCallback(
    (callback: ((index: number, id: string) => void) | null) => {
      onNativeTrackAdvancedRef.current = callback;
    },
    [],
  );

  const setOnNotificationCommand = useCallback(
    (callback: ((action: string) => void) | null) => {
      onNotificationCommandRef.current = callback;
    },
    [],
  );

  const setReverbEnabled = useCallback((enabled: boolean) => {
    setSpatialEffects((prev) => ({ ...prev, reverbEnabled: enabled }));
    EpicenterNative.setReverbEnabled({ enabled }).catch((e) =>
      console.warn('[EpicenterNative] setReverbEnabled failed', e),
    );
  }, []);

  const setReverbAmount = useCallback((amount: number) => {
    const clamped = Math.max(0, Math.min(100, amount));
    setSpatialEffects((prev) => ({ ...prev, reverbAmount: clamped }));
    EpicenterNative.setReverbAmount({ amount: clamped }).catch((e) =>
      console.warn('[EpicenterNative] setReverbAmount failed', e),
    );
  }, []);

  const setConcertHallEnabled = useCallback((enabled: boolean) => {
    setSpatialEffects((prev) => ({ ...prev, concertHallEnabled: enabled }));
    EpicenterNative.setConcertHallEnabled({ enabled }).catch((e) =>
      console.warn('[EpicenterNative] setConcertHallEnabled failed', e),
    );
  }, []);

  const setConcertHallAmount = useCallback((amount: number) => {
    const clamped = Math.max(0, Math.min(100, amount));
    setSpatialEffects((prev) => ({ ...prev, concertHallAmount: clamped }));
    EpicenterNative.setConcertHallAmount({ amount: clamped }).catch((e) =>
      console.warn('[EpicenterNative] setConcertHallAmount failed', e),
    );
  }, []);

  const resetAfterError = useCallback(() => {
    activeSourceRef.current = '';
    setIsReady(false);
    setIsPlaying(false);
    setCurrentTime(0);
    setDuration(0);
    void EpicenterNative.stop().catch(() => undefined);
  }, []);

  const getAnalyserNode = useCallback(() => null, []);

  const getCurrentDspParams = useCallback(
    () => ({ ...dspParamsRef.current }),
    [],
  );

  const getActiveSource = useCallback(() => activeSourceRef.current, []);

  return {
    isReady,
    isPlaying,
    currentTime,
    duration,
    loadFile,
    play,
    getActiveSource,
    pause,
    seek,
    setDspParam,
    setEqBandGain,
    setEqEnabled,
    setEpicenterEnabled,
    setEpicenterMode,
    setEqPreampDb,
    getAnalyserNode,
    getCurrentDspParams,
    setOnTrackEnded,
    setOnTrackError,
    setCrossfadeConfig,
    setReverbEnabled,
    setReverbAmount,
    setConcertHallEnabled,
    setConcertHallAmount,
    resetAfterError,
    eqBands,
    eqEnabled,
    epicenterEnabled,
    epicenterMode,
    spatialEffects,
    setNativeQueue,
    setOnNativeTrackAdvanced,
    setOnNotificationCommand,
  };
}

export default useAndroidNativeAudioProcessor;
