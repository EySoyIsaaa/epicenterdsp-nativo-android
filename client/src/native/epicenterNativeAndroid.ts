import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export interface EpicenterParams {
  intensity?: number;
  sweepFreq?: number;
  width?: number;
  balance?: number;
  volume?: number;
}

/**
 * Motor de graves activo. "car" = Epicenter clásico (equipos con subwoofer),
 * "headphones" = motor de audífonos/bocinas portátiles. Nunca corren los dos.
 */
export type NativeEpicenterMode = 'car' | 'headphones';

export interface NativeEngineState {
  status: string;
  reason: string;
  initialized: boolean;
  nativeModeEnabled: boolean;
  nativeAvailable: boolean;
  epicenterEnabled: boolean;
  epicenterMode?: NativeEpicenterMode;
  epicenter: Required<EpicenterParams>;
  lastError?: string | null;
  // DSP Epicenter diagnostic
  dspSampleRate?: number;
  dspChannels?: number;
  dspLastEncoding?: number;
  dspBypass?: boolean;
  processedBufferCount?: number;
  bypassBufferCount?: number;
  dspLastError?: string | null;
  // BUG 1 FIX: DSP lifecycle diagnostic counters
  dspInitCount?: number;
  dspReleaseCount?: number;
  spectrumAnalysisEnabled?: boolean;
  // PIPELINE DIAGNOSTICS — confirm Media3 is actually visiting our processor.
  // If buildAudioSinkCalls=0 while audio plays, the RenderersFactory override
  // is NOT in use (typical Media3 signature mismatch). If epicenterQueueInputCalled
  // is false while audio plays, the AudioProcessor never received PCM frames.
  buildAudioSinkCalls?: number;
  epicenterOnConfigureCalled?: boolean;
  epicenterQueueInputCalled?: boolean;
  epicenterGetOutputCalled?: boolean;
  // EQ (FASE 6)
  eqEnabled?: boolean;
  eqBypass?: boolean;
  eqPreampDb?: number;
  eqBands?: number[];
  eqProcessedBufferCount?: number;
  eqBypassBufferCount?: number;
  eqLastError?: string | null;
  // FX placeholders (FASE 7 — pendiente)
  fxEnabled?: boolean;
  reverbEnabled?: boolean;
  concertHallEnabled?: boolean;
  spatialEnabled?: boolean;
  bassBoostEnabled?: boolean;
  fxLastError?: string | null;
  // MediaSession/Background placeholders (FASE 9 — pendiente)
  mediaSessionActive?: boolean;
  backgroundServiceActive?: boolean;
}

export interface NativeEqState {
  status: string;
  eqEnabled: boolean;
  eqBypass: boolean;
  eqPreampDb: number;
  eqBands: number[];
  eqSampleRate?: number;
  eqProcessedBufferCount?: number;
  eqBypassBufferCount?: number;
  eqLastError?: string | null;
}

export interface NativeTrackInfo {
  id: string;
  title?: string;
  artist?: string;
  album?: string;
  duration?: number;
  source?: string;
  artworkUri?: string;
}

export interface NativePlaybackStateEvent {
  status: string;
  reason: string;
  initialized: boolean;
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  playbackState: number; // ExoPlayer Player.State: 1=IDLE, 2=BUFFERING, 3=READY, 4=ENDED
  currentTrack: NativeTrackInfo | null;
  // Diagnóstico del salto: solo viene en la respuesta de seek(). Si
  // seekPositionAfterMs se queda lejos de lo pedido, el reproductor ignoró la
  // orden (normalmente porque seekable=false).
  seekRequestedMs?: number;
  seekPositionAfterMs?: number;
  seekable?: boolean;
}

export interface NativePlayerErrorEvent {
  status: string;
  code: string;
  message: string;
  errorCode?: number;
  errorCodeName?: string;
  causeClass?: string | null;
  causeMessage?: string | null;
}

export interface NativeTrackLoadInput {
  id: string;
  title?: string;
  artist?: string;
  album?: string;
  duration?: number;
  uri?: string;
  path?: string;
  playbackUrl?: string;
  artworkUri?: string;
}

export interface EpicenterNativePlugin {
  initialize(): Promise<NativeEngineState>;
  isNativeAvailable(): Promise<{ available: boolean; library: string }>;
  runNativeDspSelfTest(): Promise<{ ok: boolean; status: string }>;
  setNativeModeEnabled(params: { enabled: boolean }): Promise<NativeEngineState>;
  setEpicenterEnabled(params: { enabled: boolean }): Promise<NativeEngineState>;
  setEpicenterParams(params: EpicenterParams): Promise<NativeEngineState>;
  setEpicenterMode(params: { mode: NativeEpicenterMode }): Promise<NativeEngineState>;
  getNativeEngineState(): Promise<NativeEngineState>;
  // EQ (FASE 6)
  setEqEnabled(params: { enabled: boolean }): Promise<NativeEngineState>;
  setEqBand(params: { band: number; gain: number }): Promise<NativeEngineState>;
  setEqBands(params: { bands: number[] }): Promise<NativeEngineState>;
  setEqPreamp(params: { preampDb: number }): Promise<NativeEngineState>;
  resetEq(): Promise<NativeEngineState>;
  getEqState(): Promise<NativeEqState>;
  // FX — Reverb / Concert Hall (FASE 7)
  setReverbEnabled(params: { enabled: boolean }): Promise<NativeEngineState>;
  setReverbAmount(params: { amount: number }): Promise<NativeEngineState>;
  setConcertHallEnabled(params: { enabled: boolean }): Promise<NativeEngineState>;
  setConcertHallAmount(params: { amount: number }): Promise<NativeEngineState>;
  // Cola nativa (FASE 8)
  setQueue(params: { tracks: NativeTrackLoadInput[]; startIndex: number }): Promise<{ status: string; queueSize: number; startIndex: number }>;
  nextTrack(): Promise<NativePlaybackStateEvent>;
  previousTrack(): Promise<NativePlaybackStateEvent>;
  skipToIndex(params: { index: number }): Promise<NativePlaybackStateEvent>;
  loadTrack(track: NativeTrackLoadInput): Promise<{ status: string; track: Record<string, unknown> }>;
  play(): Promise<NativePlaybackStateEvent>;
  pause(): Promise<NativePlaybackStateEvent>;
  stop(): Promise<NativePlaybackStateEvent>;
  seek(params: { positionMs: number }): Promise<NativePlaybackStateEvent>;
  /** Pseudo-crossfade nativo: funde salida/entrada entre pistas. */
  setCrossfade(params: { enabled: boolean; durationSeconds: number }): Promise<{ status: string; enabled: boolean; durationMs: number }>;
  /** Perfil espectral medido en el nativo (para el ajuste automático). */
  setSpectrumAnalysisEnabled(params: { enabled: boolean }): Promise<{ status: string; enabled: boolean }>;
  getSpectrumProfile(): Promise<{ status: string; frames: number; ready: boolean; bands: number[]; edges: number[]; rmsDb: number; crestDb: number; peak: number }>;
  resetSpectrumProfile(): Promise<{ status: string }>;
  getPlaybackState(): Promise<NativePlaybackStateEvent>;
  getCurrentTrack(): Promise<{ status: string; track: NativeTrackInfo | null }>;
  addListener(
    eventName: 'nativeEngineReady',
    listenerFunc: (event: NativeEngineState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nativeEngineError',
    listenerFunc: (event: NativePlayerErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'dspStateChanged',
    listenerFunc: (event: NativeEngineState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'playbackStateChanged',
    listenerFunc: (event: NativePlaybackStateEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'progressChanged',
    listenerFunc: (event: NativePlaybackStateEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'trackChanged',
    listenerFunc: (event: NativeTrackInfo) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nativePlayerError',
    listenerFunc: (event: NativePlayerErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'mediaNotificationCommand',
    listenerFunc: (event: { action: string }) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const EpicenterNative = registerPlugin<EpicenterNativePlugin>('EpicenterNative');

export const runEpicenterNativePhase3Probe = async () => {
  const readyL = await EpicenterNative.addListener('nativeEngineReady', (event) => {
    console.info('[EpicenterNative] nativeEngineReady', event);
  });
  const errL = await EpicenterNative.addListener('nativeEngineError', (event) => {
    console.error('[EpicenterNative] nativeEngineError', event);
  });
  const dspL = await EpicenterNative.addListener('dspStateChanged', (event) => {
    console.info('[EpicenterNative] dspStateChanged', event);
  });

  const available = await EpicenterNative.isNativeAvailable();
  console.info('[EpicenterNative] isNativeAvailable', available);

  const initialized = await EpicenterNative.initialize();
  console.info('[EpicenterNative] initialize', initialized);

  const selfTest = await EpicenterNative.runNativeDspSelfTest();
  console.info('[EpicenterNative] runNativeDspSelfTest', selfTest);

  return {
    available,
    initialized,
    selfTest,
    dispose: async () => {
      await readyL.remove();
      await errL.remove();
      await dspL.remove();
    },
  };
};

export const runEpicenterNativePhase4PlaybackProbe = async (track: NativeTrackLoadInput) => {
  await EpicenterNative.initialize();
  const loaded = await EpicenterNative.loadTrack(track);
  console.info('[EpicenterNative] loadTrack', loaded);
  const played = await EpicenterNative.play();
  console.info('[EpicenterNative] play', played);
  return { loaded, played };
};

declare global {
  interface Window {
    epicenterNativePhase3Probe?: () => Promise<unknown>;
    epicenterNativePhase4PlaybackProbe?: (track: NativeTrackLoadInput) => Promise<unknown>;
    epicenterNativeDebugState?: () => Promise<NativeEngineState>;
  }
}

if (typeof window !== 'undefined') {
  window.epicenterNativePhase3Probe = runEpicenterNativePhase3Probe;
  window.epicenterNativePhase4PlaybackProbe = runEpicenterNativePhase4PlaybackProbe;
  window.epicenterNativeDebugState = async () => {
    const state = await EpicenterNative.getNativeEngineState();
    // eslint-disable-next-line no-console
    console.log('[NativeOnly] state', state);
    return state;
  };
}
