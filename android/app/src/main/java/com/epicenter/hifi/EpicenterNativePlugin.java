package com.epicenter.hifi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.epicenter.hifi.dsp.EpicenterDSPNative;
import com.epicenter.hifi.nativeaudio.EqAudioProcessor;
import com.epicenter.hifi.nativeaudio.NativeAudioTrack;
import com.epicenter.hifi.nativeaudio.NativePlaybackController;
import com.epicenter.hifi.nativeaudio.SpectrumAnalyzer;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

@CapacitorPlugin(name = "EpicenterNative")
public class EpicenterNativePlugin extends Plugin {
  private static final String TAG = "EpicenterNative";

  private EpicenterDSPNative dspNative;
  private boolean initialized = false;
  private boolean nativeModeEnabled = false;
  private boolean epicenterEnabled = false;
  // BUG 1 FIX: nativeAvailable is determined ONCE at initialize() time via
  // runSelfTest() and then cached. Previously isNativeAvailableInternal() was
  // calling runSelfTest() on EVERY state poll, which builds + tears down the
  // entire DSP engine (nativeInit/nativeRelease) on every UI slider change.
  // Now state polling is a pure getter.
  private Boolean nativeAvailableCached = null;
  // "car" = classic Epicenter core, "headphones" = headphones bass core.
  // Only one engine ever processes audio; see EpicenterDSPJNI.cpp.
  private boolean headphonesMode = false;
  private float intensity = 100f;
  private float sweepFreq = 45f;
  private float width = 50f;
  private float balance = 100f;
  private float volume = 100f;
  private String lastError = null;
  private NativePlaybackController playbackController;
  private ServiceConnection serviceConnection;
  private boolean serviceBindRequested = false;
  private Player.Listener pluginPlayerListener;
  private final Handler progressHandler = new Handler(Looper.getMainLooper());
  private final Runnable progressRunnable = new Runnable() {
    @Override
    public void run() {
      if (playbackController != null) {
        notifyListeners("progressChanged", buildPlaybackState("progress"));
      }
      progressHandler.postDelayed(this, 1000);
    }
  };

  /**
   * ExoPlayer is single-threaded. We anchor it to the main looper (see
   * NativePlaybackController) and post EVERY player-touching operation to
   * this handler so we never get "Player is accessed on the wrong thread".
   * Capacitor plugin methods are invoked on the "CapacitorPlugins" thread,
   * so they must hop here before talking to the player.
   */
  private void runOnPlayerThread(Runnable r) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      r.run();
    } else {
      progressHandler.post(r);
    }
  }

  @PluginMethod
  public void initialize(PluginCall call) {
    // Fast path: already initialized and service is still connected.
    if (initialized && playbackController != null) {
      call.resolve(getEngineState("already_initialized"));
      return;
    }

    // DSP self-test (fast, sync, safe on any thread).
    if (nativeAvailableCached == null) {
      try {
        nativeAvailableCached = EpicenterDSPNative.runSelfTest();
        Log.i(TAG, "native self-test cached result=" + nativeAvailableCached);
      } catch (Throwable selfTestErr) {
        nativeAvailableCached = false;
        lastError = "native_unavailable: " + selfTestErr.getMessage();
        Log.w(TAG, "native self-test failed on initialize", selfTestErr);
      }
    }
    if (dspNative == null) {
      dspNative = new EpicenterDSPNative(44100, 2);
      dspNative.setEnabled(epicenterEnabled);
      dspNative.setParams(intensity, sweepFreq, width, balance, volume);
    }

    if (serviceBindRequested) {
      call.reject("initialize_bind_already_in_progress");
      return;
    }
    serviceBindRequested = true;

    // Start the service so it stays alive even if we later unbind.
    // GUARD: startService() throws if the app is in the background (Android 8+:
    // IllegalStateException; Android 12+: ForegroundServiceStartNotAllowedException).
    // If initialize() runs while backgrounded we must NOT crash — the bindService
    // below (BIND_AUTO_CREATE) still creates the service when playback resumes.
    Intent startIntent = new Intent(getContext(), EpicenterPlaybackService.class);
    try {
      getContext().startService(startIntent);
    } catch (Throwable startErr) {
      Log.w(TAG, "startService deferred (app likely backgrounded): " + startErr.getMessage());
    }

    serviceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder binder) {
        EpicenterPlaybackService.LocalBinder lb = (EpicenterPlaybackService.LocalBinder) binder;
        EpicenterPlaybackService service = lb.getService();
        runOnPlayerThread(() -> {
          try {
            playbackController = service.getPlaybackController();
            // Forward the notification's skip buttons to JS — the JS queue owns
            // next/previous logic (same path as the in-app skip buttons).
            service.setMediaButtonCallback(action -> {
              JSObject evt = new JSObject();
              evt.put("action", action);
              notifyListeners("mediaNotificationCommand", evt);
            });
            pluginPlayerListener = createPlayerListener();
            playbackController.addPlayerListener(pluginPlayerListener);
            playbackController.getEpicenterAudioProcessor().setEpicenterParams(
                intensity, sweepFreq, width, balance, volume);
            playbackController.getEpicenterAudioProcessor().setEpicenterEnabled(
                epicenterEnabled && nativeModeEnabled);
            // Re-apply the selected engine so a service rebind doesn't silently
            // drop the user back to Car Audio.
            playbackController.getEpicenterAudioProcessor().setEpicenterMode(headphonesMode);
            progressHandler.removeCallbacks(progressRunnable);
            progressHandler.post(progressRunnable);
            initialized = true;
            lastError = null;
            Log.i(TAG, "initialize ok (via service bind) nativeMode=" + nativeModeEnabled
                + " epicenter=" + epicenterEnabled);
            JSObject data = getEngineState("initialized");
            call.resolve(data);
            notifyListeners("nativeEngineReady", data);
          } catch (Throwable throwable) {
            lastError = "initialize_failed: " + throwable.getMessage();
            Log.e(TAG, "initialize failed after service bind", throwable);
            call.reject(lastError);
            notifyListeners("nativeEngineError", buildError("initialize_failed", throwable.getMessage()));
          }
        });
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        // Service process crashed — mark as uninitialized so JS can retry.
        playbackController = null;
        initialized = false;
        serviceBindRequested = false;
        Log.w(TAG, "onServiceDisconnected: service died unexpectedly");
        notifyListeners("nativeEngineError", buildError("service_disconnected",
            "EpicenterPlaybackService disconnected unexpectedly"));
      }
    };

    Intent bindIntent = new Intent(getContext(), EpicenterPlaybackService.class);
    bindIntent.setAction(EpicenterPlaybackService.ACTION_LOCAL_BIND);
    getContext().bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  @PluginMethod
  public void isNativeAvailable(PluginCall call) {
    JSObject result = new JSObject();
    result.put("available", isNativeAvailableInternal());
    result.put("library", "epicenterdsp");
    call.resolve(result);
  }

  @PluginMethod
  public void runNativeDspSelfTest(PluginCall call) {
    try {
      boolean ok = EpicenterDSPNative.runSelfTest();
      JSObject result = new JSObject();
      result.put("ok", ok);
      result.put("status", ok ? "ok" : "failed");
      if (!ok) {
        lastError = "self_test_failed";
        notifyListeners("nativeEngineError", buildError("self_test_failed", "runSelfTest returned false"));
      }
      call.resolve(result);
    } catch (Throwable throwable) {
      lastError = "self_test_exception: " + throwable.getMessage();
      JSObject error = buildError("self_test_exception", throwable.getMessage());
      call.reject(lastError);
      notifyListeners("nativeEngineError", error);
    }
  }

  @PluginMethod
  public void setNativeModeEnabled(PluginCall call) {
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) {
      call.reject("invalid_param_enabled");
      return;
    }
    if (!initialized) {
      call.reject("native_not_initialized");
      return;
    }

    nativeModeEnabled = enabled;
    if (playbackController != null) {
      playbackController.getEpicenterAudioProcessor().setEpicenterEnabled(epicenterEnabled && nativeModeEnabled);
    }
    JSObject state = getEngineState("native_mode_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setEpicenterEnabled(PluginCall call) {
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) {
      call.reject("invalid_param_enabled");
      return;
    }
    if (!initialized || dspNative == null) {
      call.reject("native_not_initialized");
      return;
    }

    epicenterEnabled = enabled;
    dspNative.setEnabled(enabled);
    if (playbackController != null) {
      playbackController.getEpicenterAudioProcessor().setEpicenterEnabled(enabled && nativeModeEnabled);
    }

    JSObject state = getEngineState("epicenter_enabled_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setEpicenterParams(PluginCall call) {
    if (!initialized || dspNative == null) {
      call.reject("native_not_initialized");
      return;
    }

    Double intensityInput = call.getDouble("intensity");
    Double sweepFreqInput = call.getDouble("sweepFreq");
    Double widthInput = call.getDouble("width");
    Double balanceInput = call.getDouble("balance");
    Double volumeInput = call.getDouble("volume");

    float nextIntensity = intensityInput == null ? intensity : intensityInput.floatValue();
    float nextSweepFreq = sweepFreqInput == null ? sweepFreq : sweepFreqInput.floatValue();
    float nextWidth = widthInput == null ? width : widthInput.floatValue();
    float nextBalance = balanceInput == null ? balance : balanceInput.floatValue();
    float nextVolume = volumeInput == null ? volume : volumeInput.floatValue();

    if (!inRange(nextIntensity, 0f, 100f)) {
      call.reject("invalid_intensity_range_0_100");
      return;
    }
    if (!inRange(nextSweepFreq, 27f, 63f)) {
      call.reject("invalid_sweepFreq_range_27_63");
      return;
    }
    if (!inRange(nextWidth, 0f, 100f)) {
      call.reject("invalid_width_range_0_100");
      return;
    }
    if (!inRange(nextBalance, 0f, 100f)) {
      call.reject("invalid_balance_range_0_100");
      return;
    }
    if (!inRange(nextVolume, 0f, 100f)) {
      call.reject("invalid_volume_range_0_100");
      return;
    }

    intensity = nextIntensity;
    sweepFreq = nextSweepFreq;
    width = nextWidth;
    balance = nextBalance;
    volume = nextVolume;

    dspNative.setParams(intensity, sweepFreq, width, balance, volume);
    if (playbackController != null) {
      playbackController.getEpicenterAudioProcessor().setEpicenterParams(intensity, sweepFreq, width, balance, volume);
    }

    JSObject state = getEngineState("epicenter_params_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  /**
   * Selects the Epicenter engine: "car" (classic, subwoofer systems) or
   * "headphones" (small drivers). Mirrors setEpicenterMode on iOS.
   */
  @PluginMethod
  public void setEpicenterMode(PluginCall call) {
    String mode = call.getString("mode");
    if (mode == null || (!"car".equals(mode) && !"headphones".equals(mode))) {
      call.reject("invalid_mode_expected_car_or_headphones");
      return;
    }
    if (!initialized || dspNative == null) {
      call.reject("native_not_initialized");
      return;
    }

    headphonesMode = "headphones".equals(mode);
    dspNative.setHeadphonesMode(headphonesMode);
    if (playbackController != null) {
      playbackController.getEpicenterAudioProcessor().setEpicenterMode(headphonesMode);
    }

    JSObject state = getEngineState("epicenter_mode_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  /**
   * Perfil espectral del audio que se está reproduciendo, para el ajuste
   * automático por canción. En Android no hay AnalyserNode de Web Audio (la
   * reproducción es nativa), así que la medición se hace aquí.
   */
  @PluginMethod
  public void getSpectrumProfile(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    com.epicenter.hifi.nativeaudio.SpectrumAnalyzer an =
        playbackController.getEpicenterAudioProcessor().getSpectrumAnalyzer();
    JSObject r = new JSObject();
    r.put("status", "ok");
    r.put("frames", an.getFrameCount());
    r.put("ready", an.getFrameCount() >= 4);   // ~1.4 s de audio medido
    r.put("bands", toJsArray(an.getBandsDb()));
    r.put("edges", toJsArray(SpectrumAnalyzer.bandEdges()));
    r.put("rmsDb", an.getRmsDb());
    r.put("crestDb", an.getCrestDb());
    r.put("peak", an.getPeak());
    call.resolve(r);
  }

  /** Activa/desactiva el coste de FFT sin depender del estado del Epicenter. */
  @PluginMethod
  public void setSpectrumAnalysisEnabled(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) { call.reject("missing_enabled"); return; }
    playbackController.getEpicenterAudioProcessor().setSpectrumAnalysisEnabled(enabled);
    JSObject result = new JSObject();
    result.put("status", "ok");
    result.put("enabled", enabled);
    call.resolve(result);
  }

  /**
   * Pseudo-crossfade: funde la salida al final de la pista y la entrada al
   * principio de la siguiente. Corre en el nativo para que siga funcionando con
   * la app en segundo plano.
   */
  @PluginMethod
  public void setCrossfade(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) { call.reject("invalid_param_enabled"); return; }
    Double seconds = call.getDouble("durationSeconds");
    long ms = seconds == null ? 5000L : Math.round(seconds * 1000.0);
    final boolean on = enabled;
    final long durationMs = ms;
    runOnPlayerThread(() -> {
      playbackController.setCrossfadeConfig(on, durationMs);
      JSObject r = new JSObject();
      r.put("status", "ok");
      r.put("enabled", on);
      r.put("durationMs", durationMs);
      call.resolve(r);
    });
  }

  /** Descarta la medición acumulada (al cambiar de pista). */
  @PluginMethod
  public void resetSpectrumProfile(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    playbackController.getEpicenterAudioProcessor().getSpectrumAnalyzer().reset();
    JSObject r = new JSObject();
    r.put("status", "ok");
    call.resolve(r);
  }

  // === EQ (FASE 6) ===

  @PluginMethod
  public void setEqEnabled(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) { call.reject("missing_enabled"); return; }
    playbackController.getEqAudioProcessor().setEnabled(enabled);
    applyOutputHeadroom();
    Log.i(TAG, "setEqEnabled " + enabled);
    JSObject state = getEngineState("eq_enabled_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setEqBand(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Integer band = call.getInt("band");
    Double gain = call.getDouble("gain");
    if (band == null || gain == null) { call.reject("missing_band_or_gain"); return; }
    playbackController.getEqAudioProcessor().setBandGain(band, gain.floatValue());
    applyOutputHeadroom();
    Log.i(TAG, "setEqBand band=" + band + " gain=" + gain + " dB");
    JSObject state = getEngineState("eq_band_changed");
    call.resolve(state);
  }

  @PluginMethod
  public void setEqBands(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    JSArray bandsJs = call.getArray("bands");
    if (bandsJs == null) { call.reject("missing_bands"); return; }
    try {
      int n = bandsJs.length();
      float[] gains = new float[n];
      for (int i = 0; i < n; i++) {
        gains[i] = (float) bandsJs.getDouble(i);
      }
      playbackController.getEqAudioProcessor().setBandsGain(gains);
      applyOutputHeadroom();
      Log.i(TAG, "setEqBands count=" + n);
      JSObject state = getEngineState("eq_bands_changed");
      call.resolve(state);
      notifyListeners("dspStateChanged", state);
    } catch (Throwable t) {
      call.reject("set_eq_bands_failed: " + t.getMessage());
    }
  }

  @PluginMethod
  public void setEqPreamp(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Double preampDb = call.getDouble("preampDb");
    if (preampDb == null) { call.reject("missing_preampDb"); return; }
    playbackController.getEqAudioProcessor().setPreampDb(preampDb.floatValue());
    Log.i(TAG, "setEqPreamp " + preampDb + " dB");
    JSObject state = getEngineState("eq_preamp_changed");
    call.resolve(state);
  }

  @PluginMethod
  public void resetEq(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    playbackController.getEqAudioProcessor().resetEq();
    applyOutputHeadroom();
    Log.i(TAG, "resetEq");
    JSObject state = getEngineState("eq_reset");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  // === FX: Reverb / Concert Hall (FASE 7) ===

  @PluginMethod
  public void setReverbEnabled(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) { call.reject("missing_enabled"); return; }
    playbackController.getReverbAudioProcessor().setReverbEnabled(enabled);
    applyOutputHeadroom();
    Log.i(TAG, "setReverbEnabled " + enabled);
    JSObject state = getEngineState("reverb_enabled_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setReverbAmount(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Double amount = call.getDouble("amount");
    if (amount == null) { call.reject("missing_amount"); return; }
    playbackController.getReverbAudioProcessor().setReverbAmount(amount.floatValue());
    applyOutputHeadroom();
    Log.i(TAG, "setReverbAmount " + amount);
    JSObject state = getEngineState("reverb_amount_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setConcertHallEnabled(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Boolean enabled = call.getBoolean("enabled");
    if (enabled == null) { call.reject("missing_enabled"); return; }
    playbackController.getReverbAudioProcessor().setConcertHallEnabled(enabled);
    applyOutputHeadroom();
    Log.i(TAG, "setConcertHallEnabled " + enabled);
    JSObject state = getEngineState("concert_hall_enabled_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void setConcertHallAmount(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    Double amount = call.getDouble("amount");
    if (amount == null) { call.reject("missing_amount"); return; }
    playbackController.getReverbAudioProcessor().setConcertHallAmount(amount.floatValue());
    applyOutputHeadroom();
    Log.i(TAG, "setConcertHallAmount " + amount);
    JSObject state = getEngineState("concert_hall_amount_changed");
    call.resolve(state);
    notifyListeners("dspStateChanged", state);
  }

  @PluginMethod
  public void getEqState(PluginCall call) {
    if (playbackController == null) { call.reject("native_not_initialized"); return; }
    EqAudioProcessor eq = playbackController.getEqAudioProcessor();
    JSObject state = new JSObject();
    state.put("status", "ok");
    state.put("eqEnabled", eq.isEnabled());
    state.put("eqBypass", !eq.isEnabled());
    state.put("eqPreampDb", eq.getPreampDb());
    state.put("eqBands", toJsArray(eq.getBandsDb()));
    state.put("eqSampleRate", eq.getSampleRate());
    state.put("eqProcessedBufferCount", eq.getProcessedBufferCount());
    state.put("eqBypassBufferCount", eq.getBypassBufferCount());
    state.put("eqLastError", eq.getLastError());
    call.resolve(state);
  }


  @PluginMethod
  public void getNativeEngineState(PluginCall call) {
    call.resolve(getEngineState("snapshot"));
  }

  @PluginMethod
  public void loadTrack(PluginCall call) {
    if (!initialized || playbackController == null) {
      call.reject("native_not_initialized");
      return;
    }
    String id = call.getString("id", "unknown-id");
    String title = call.getString("title", "Unknown title");
    String artist = call.getString("artist", "Unknown artist");
    String album = call.getString("album", "Unknown album");
    Long duration = call.getLong("duration", 0L);
    String rawSource = call.getString("uri");
    if (rawSource == null || rawSource.isEmpty()) rawSource = call.getString("path");
    if (rawSource == null || rawSource.isEmpty()) rawSource = call.getString("playbackUrl");
    String artworkUri = call.getString("artworkUri");
    if (rawSource == null || rawSource.isEmpty()) {
      call.reject("missing_track_source_uri_path_playbackUrl");
      return;
    }
    final String source = sanitizeMediaSource(rawSource);
    if (source == null || source.isEmpty()) {
      call.reject("could_not_resolve_media_source: " + rawSource);
      return;
    }
    if (!source.equals(rawSource)) {
      Log.i(TAG, "sanitized media source: " + rawSource + " -> " + source);
    }
    final NativeAudioTrack track = new NativeAudioTrack(id, title, artist, album, duration, source, artworkUri);
    runOnPlayerThread(() -> {
      try {
        playbackController.loadTrack(track);
        JSObject trackObj = toTrackObject(track);
        notifyListeners("trackChanged", trackObj);
        JSObject result = new JSObject();
        result.put("status", "ok");
        result.put("track", trackObj);
        call.resolve(result);
      } catch (Throwable throwable) {
        call.reject("load_track_failed: " + throwable.getMessage());
        notifyListeners("nativePlayerError", buildError("load_track_failed", throwable.getMessage()));
      }
    });
  }

  @PluginMethod public void play(PluginCall call) { handlePlayerControl(call, "play"); }
  @PluginMethod public void pause(PluginCall call) { handlePlayerControl(call, "pause"); }
  @PluginMethod public void stop(PluginCall call) { handlePlayerControl(call, "stop"); }

  // === COLA NATIVA ===

  @PluginMethod
  public void setQueue(PluginCall call) {
    if (!initialized || playbackController == null) {
      call.reject("native_not_initialized");
      return;
    }
    com.getcapacitor.JSArray tracksJs = call.getArray("tracks");
    Integer startIndex = call.getInt("startIndex", 0);
    if (tracksJs == null) {
      call.reject("missing_tracks");
      return;
    }
    try {
      java.util.List<com.epicenter.hifi.nativeaudio.NativeAudioTrack> tracks = new java.util.ArrayList<>();
      for (int i = 0; i < tracksJs.length(); i++) {
        org.json.JSONObject obj = tracksJs.getJSONObject(i);
        String id         = obj.optString("id", "unknown-" + i);
        String title      = obj.optString("title", "Unknown");
        String artist     = obj.optString("artist", "Unknown");
        String album      = obj.optString("album", "Unknown");
        long   duration   = obj.optLong("duration", 0L);
        String rawSource  = obj.optString("uri", null);
        if (rawSource == null || rawSource.isEmpty()) rawSource = obj.optString("path", null);
        if (rawSource == null || rawSource.isEmpty()) rawSource = obj.optString("playbackUrl", null);
        String artworkUri = obj.optString("artworkUri", null);
        if (rawSource == null || rawSource.isEmpty()) continue;
        String source = sanitizeMediaSource(rawSource);
        if (source == null || source.isEmpty()) continue;
        tracks.add(new com.epicenter.hifi.nativeaudio.NativeAudioTrack(
            id, title, artist, album, duration, source, artworkUri));
      }
      if (tracks.isEmpty()) {
        call.reject("no_valid_tracks_in_queue");
        return;
      }
      final int idx = startIndex != null ? startIndex : 0;
      final java.util.List<com.epicenter.hifi.nativeaudio.NativeAudioTrack> finalTracks = tracks;
      runOnPlayerThread(() -> {
        try {
          playbackController.setQueue(finalTracks, idx);
          JSObject result = new JSObject();
          result.put("status", "ok");
          result.put("queueSize", finalTracks.size());
          result.put("startIndex", idx);
          call.resolve(result);
        } catch (Throwable t) {
          call.reject("set_queue_failed: " + t.getMessage());
        }
      });
    } catch (Throwable t) {
      call.reject("set_queue_parse_failed: " + t.getMessage());
    }
  }

  @PluginMethod
  public void nextTrack(PluginCall call) {
    if (!initialized || playbackController == null) { call.reject("native_not_initialized"); return; }
    runOnPlayerThread(() -> {
      playbackController.nextTrack();
      call.resolve(buildPlaybackState("next_track"));
    });
  }

  @PluginMethod
  public void previousTrack(PluginCall call) {
    if (!initialized || playbackController == null) { call.reject("native_not_initialized"); return; }
    runOnPlayerThread(() -> {
      playbackController.previousTrack();
      call.resolve(buildPlaybackState("previous_track"));
    });
  }

  @PluginMethod
  public void skipToIndex(PluginCall call) {
    if (!initialized || playbackController == null) { call.reject("native_not_initialized"); return; }
    Integer index = call.getInt("index");
    if (index == null || index < 0) { call.reject("invalid_index"); return; }
    final int idx = index;
    runOnPlayerThread(() -> {
      playbackController.skipToIndex(idx);
      call.resolve(buildPlaybackState("skip_to_index_" + idx));
    });
  }

  @PluginMethod
  public void seek(PluginCall call) {
    if (!initialized || playbackController == null) {
      call.reject("native_not_initialized");
      return;
    }
    Long positionMs = call.getLong("positionMs");
    if (positionMs == null || positionMs < 0) {
      call.reject("invalid_positionMs");
      return;
    }
    final long pos = positionMs;
    runOnPlayerThread(() -> {
      try {
        playbackController.seekTo(pos);
        JSObject state = buildPlaybackState("seek");
        // Diagnóstico del salto: con esto se ve si el reproductor aceptó la
        // orden o si el ítem no era buscable (la barra se movería en pantalla
        // pero la canción no).
        state.put("seekRequestedMs", pos);
        state.put("seekPositionAfterMs", playbackController.getPosition());
        state.put("seekable", playbackController.isSeekable());
        notifyListeners("playbackStateChanged", state);
        call.resolve(state);
      } catch (Throwable throwable) {
        call.reject("seek_failed: " + throwable.getMessage());
        notifyListeners("nativePlayerError", buildError("seek_failed", throwable.getMessage()));
      }
    });
  }

  @PluginMethod
  public void getPlaybackState(PluginCall call) {
    runOnPlayerThread(() -> call.resolve(buildPlaybackState("snapshot")));
  }

  @PluginMethod
  public void getCurrentTrack(PluginCall call) {
    runOnPlayerThread(() -> {
      JSObject result = new JSObject();
      NativeAudioTrack track = playbackController == null ? null : playbackController.getCurrentTrack();
      result.put("status", "ok");
      result.put("track", track == null ? null : toTrackObject(track));
      call.resolve(result);
    });
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    progressHandler.removeCallbacks(progressRunnable);
    // Unbind from the service — the service keeps the player alive for background audio.
    // Do NOT call playbackController.release() here; the service owns the player.
    if (serviceConnection != null) {
      try {
        getContext().unbindService(serviceConnection);
      } catch (Throwable ignored) {}
      serviceConnection = null;
      serviceBindRequested = false;
    }
    // Remove our listener from the player so we don't get callbacks after destroy.
    if (playbackController != null && pluginPlayerListener != null) {
      try {
        playbackController.removePlayerListener(pluginPlayerListener);
      } catch (Throwable ignored) {}
      pluginPlayerListener = null;
      playbackController = null;
    }
    runOnPlayerThread(() -> {
      if (dspNative != null) {
        try { dspNative.release(); } catch (Throwable throwable) {
          Log.w(TAG, "dspNative.release failed", throwable);
        }
        dspNative = null;
      }
      initialized = false;
    });
  }

  private boolean isNativeAvailableInternal() {
    // BUG 1 FIX: pure getter. Never invoke runSelfTest() here, otherwise
    // every getEngineState() call (UI slider movements, progress ticks,
    // listener notifications) would tear down + rebuild the DSP and spam
    // nativeInit/nativeRelease, causing audio dropouts and CPU storms.
    if (nativeAvailableCached == null) {
      // Defensive: should have been initialized in initialize(). Mark as
      // available=false so callers see a clear state instead of crashing.
      return false;
    }
    return nativeAvailableCached;
  }

  private boolean inRange(float value, float min, float max) {
    return value >= min && value <= max;
  }

  /**
   * Reproduce iOS NativeAudioEngine.updateHeadroom(): empuja al último procesador
   * (Reverb) el trim de salida total = min(10, headroomEQ + headroomFX), donde
   * headroomFX = (reverb? amt/100*3) + (concertHall? amt/100*4). El EQ aplica su
   * propio headroom internamente (= eqNode.globalGain), así el escalonado de
   * ganancia queda idéntico a iOS.
   */
  private void applyOutputHeadroom() {
    if (playbackController == null) return;
    EqAudioProcessor eq = playbackController.getEqAudioProcessor();
    com.epicenter.hifi.nativeaudio.ReverbAudioProcessor reverb =
        playbackController.getReverbAudioProcessor();
    float eqHead = eq.isEnabled() ? eq.getHeadroomDb() : 0f;
    float fxHead =
        (reverb.isReverbEnabled() ? (reverb.getReverbAmount() / 100f) * 3f : 0f)
      + (reverb.isConcertHallEnabled() ? (reverb.getConcertHallAmount() / 100f) * 4f : 0f);
    float total = Math.min(10f, eqHead + fxHead);
    reverb.setOutputHeadroomDb(total);
  }

  private JSObject getEngineState(String reason) {
    JSObject state = new JSObject();
    state.put("status", "ok");
    state.put("reason", reason);
    state.put("initialized", initialized);
    state.put("nativeModeEnabled", nativeModeEnabled);
    state.put("nativeAvailable", isNativeAvailableInternal());
    // BUG 1 FIX: diagnostic counters. Should stay flat once the engine is up.
    state.put("dspInitCount", EpicenterDSPNative.getInitCount());
    state.put("dspReleaseCount", EpicenterDSPNative.getReleaseCount());
    state.put("epicenterEnabled", epicenterEnabled);
    state.put("epicenterMode", headphonesMode ? "headphones" : "car");
    if (playbackController != null) {
      state.put("dspSampleRate", playbackController.getEpicenterAudioProcessor().getSampleRate());
      state.put("dspChannels", playbackController.getEpicenterAudioProcessor().getChannelCount());
      state.put("dspLastEncoding", playbackController.getEpicenterAudioProcessor().getLastEncoding());
      state.put("dspBypass", !playbackController.getEpicenterAudioProcessor().isEpicenterEnabled());
      state.put("processedBufferCount", playbackController.getEpicenterAudioProcessor().getProcessedBufferCount());
      state.put("bypassBufferCount", playbackController.getEpicenterAudioProcessor().getBypassBufferCount());
      state.put("dspLastError", playbackController.getEpicenterAudioProcessor().getLastError());
      state.put("spectrumAnalysisEnabled", playbackController.getEpicenterAudioProcessor().isSpectrumAnalysisEnabled());
      // PIPELINE DIAGNOSTICS — confirm ExoPlayer actually visits our processor.
      state.put("buildAudioSinkCalls", NativePlaybackController.getBuildAudioSinkCallCount());
      state.put("epicenterOnConfigureCalled", playbackController.getEpicenterAudioProcessor().wasOnConfigureCalled());
      state.put("epicenterQueueInputCalled", playbackController.getEpicenterAudioProcessor().wasQueueInputCalled());
      state.put("epicenterGetOutputCalled", playbackController.getEpicenterAudioProcessor().wasGetOutputCalled());
      // EQ (FASE 6)
      EqAudioProcessor eq = playbackController.getEqAudioProcessor();
      state.put("eqEnabled", eq.isEnabled());
      state.put("eqBypass", !eq.isEnabled());
      state.put("eqPreampDb", eq.getPreampDb());
      state.put("eqBands", toJsArray(eq.getBandsDb()));
      state.put("eqProcessedBufferCount", eq.getProcessedBufferCount());
      state.put("eqBypassBufferCount", eq.getBypassBufferCount());
      state.put("eqLastError", eq.getLastError());
    } else {
      state.put("dspSampleRate", 0);
      state.put("dspChannels", 0);
      state.put("dspLastEncoding", -1);
      state.put("dspBypass", true);
      state.put("processedBufferCount", 0);
      state.put("bypassBufferCount", 0);
      state.put("dspLastError", null);
      state.put("spectrumAnalysisEnabled", false);
      state.put("buildAudioSinkCalls", NativePlaybackController.getBuildAudioSinkCallCount());
      state.put("epicenterOnConfigureCalled", false);
      state.put("epicenterQueueInputCalled", false);
      state.put("epicenterGetOutputCalled", false);
      state.put("eqEnabled", false);
      state.put("eqBypass", true);
      state.put("eqPreampDb", 0f);
      state.put("eqBands", new JSArray());
      state.put("eqProcessedBufferCount", 0);
      state.put("eqBypassBufferCount", 0);
      state.put("eqLastError", null);
    }
    // FX — Reverb / Concert Hall (FASE 7)
    if (playbackController != null) {
      com.epicenter.hifi.nativeaudio.ReverbAudioProcessor reverb =
          playbackController.getReverbAudioProcessor();
      boolean rEnabled = reverb.isReverbEnabled();
      boolean chEnabled = reverb.isConcertHallEnabled();
      state.put("reverbEnabled", rEnabled);
      state.put("concertHallEnabled", chEnabled);
      state.put("fxEnabled", rEnabled || chEnabled);
      state.put("reverbAmount", reverb.getReverbAmount());
      state.put("concertHallAmount", reverb.getConcertHallAmount());
    } else {
      state.put("reverbEnabled", false);
      state.put("concertHallEnabled", false);
      state.put("fxEnabled", false);
      state.put("reverbAmount", 35f);
      state.put("concertHallAmount", 45f);
    }
    state.put("spatialEnabled", false);
    state.put("bassBoostEnabled", false);
    state.put("fxLastError", null);
    state.put("mediaSessionActive", initialized && playbackController != null);
    state.put("backgroundServiceActive", initialized && playbackController != null);
    state.put("epicenter", new JSObject()
      .put("intensity", intensity)
      .put("sweepFreq", sweepFreq)
      .put("width", width)
      .put("balance", balance)
      .put("volume", volume)
    );
    state.put("lastError", lastError);
    return state;
  }

  private JSArray toJsArray(float[] values) {
    JSArray arr = new JSArray();
    if (values == null) return arr;
    for (float v : values) {
      try {
        arr.put((double) v);
      } catch (org.json.JSONException ignored) {}
    }
    return arr;
  }

  /**
   * Convierte URLs propias del WebView de Capacitor (que ExoPlayer NO puede abrir
   * porque intentaría hablar HTTPS contra "localhost:443") en URIs reales que
   * Media3 sí entiende.
   *
   * Conversiones:
   * <ul>
   *   <li>{@code https://localhost/_capacitor_file_/<path>} -> {@code file:///<path>}</li>
   *   <li>{@code https://localhost/_capacitor_content_/<auth>/<rest>} -> {@code content://<auth>/<rest>}</li>
   *   <li>Rutas absolutas sin esquema ({@code /data/...}) -> {@code file:///data/...}</li>
   *   <li>Resto ({@code file://}, {@code content://}, {@code http://...}, {@code https://...} válido) -> pass-through</li>
   * </ul>
   *
   * También se elimina el query-string ({@code ?v=...}, usado para cache-busting
   * en el WebView) porque rompe los esquemas locales.
   */
  static String sanitizeMediaSource(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return s;

    final String fileMarker = "/_capacitor_file_/";
    final String contentMarker = "/_capacitor_content_/";

    int fileIdx = s.indexOf(fileMarker);
    int contentIdx = s.indexOf(contentMarker);

    if (fileIdx >= 0) {
      String tail = s.substring(fileIdx + fileMarker.length());
      tail = stripQueryAndFragment(tail);
      if (!tail.startsWith("/")) tail = "/" + tail;
      return "file://" + tail;
    }
    if (contentIdx >= 0) {
      String tail = s.substring(contentIdx + contentMarker.length());
      tail = stripQueryAndFragment(tail);
      return "content://" + tail;
    }
    // file:// con query inservible (algunos pipelines lo agregan).
    if (s.startsWith("file://")) {
      return stripQueryAndFragment(s);
    }
    // content:// pass-through (no se le quita query: algunos providers la usan).
    if (s.startsWith("content://")) {
      return s;
    }
    // http/https remotos legítimos: pass-through.
    if (s.startsWith("http://") || s.startsWith("https://")) {
      return s;
    }
    // Ruta absoluta sin esquema -> filesystem.
    if (s.startsWith("/")) {
      return "file://" + s;
    }
    return s;
  }

  private static String stripQueryAndFragment(String url) {
    int q = url.indexOf('?');
    if (q >= 0) url = url.substring(0, q);
    int f = url.indexOf('#');
    if (f >= 0) url = url.substring(0, f);
    return url;
  }

  private JSObject buildError(String code, String message) {
    JSObject obj = new JSObject();
    obj.put("status", "error");
    obj.put("code", code);
    obj.put("message", message == null ? "unknown" : message);
    return obj;
  }

  private Player.Listener createPlayerListener() {
    return new Player.Listener() {
      @Override
      public void onPlaybackStateChanged(int playbackState) {
        notifyListeners("playbackStateChanged", buildPlaybackState("state_changed"));
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        notifyListeners("playbackStateChanged", buildPlaybackState("is_playing_changed"));
      }

      @Override
      public void onMediaItemTransition(
          androidx.media3.common.MediaItem mediaItem,
          int reason) {
        // PLAYLIST_CHANGED (3) fires from our own setMediaItems() call — not a
        // real track advance. Skip it to avoid a feedback loop with the JS layer.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return;

        com.epicenter.hifi.nativeaudio.NativeAudioTrack current =
            playbackController != null ? playbackController.getCurrentTrack() : null;
        JSObject event = new JSObject();
        event.put("reason", reason); // 0=REPEAT, 1=AUTO, 2=SEEK
        event.put("index", playbackController != null ? playbackController.getCurrentIndex() : -1);
        if (current != null) {
          event.put("id",       current.id);
          event.put("title",    current.title);
          event.put("artist",   current.artist);
          event.put("album",    current.album);
          event.put("duration", current.duration);
          event.put("source",   current.source);
          event.put("artworkUri", current.artworkUri);
        }
        notifyListeners("trackChanged", event);
        notifyListeners("playbackStateChanged", buildPlaybackState("media_item_transition"));
      }

      @Override
      public void onPlayerError(PlaybackException error) {
        JSObject event = buildError("player_error", error.getMessage());
        event.put("errorCode", error.errorCode);
        event.put("errorCodeName", error.getErrorCodeName());
        Throwable cause = error.getCause();
        event.put("causeClass", cause == null ? null : cause.getClass().getName());
        event.put("causeMessage", cause == null ? null : cause.getMessage());
        Log.e(TAG, "player error " + error.getErrorCodeName(), error);
        notifyListeners("nativePlayerError", event);
      }
    };
  }

  private void handlePlayerControl(PluginCall call, String action) {
    if (!initialized || playbackController == null) {
      call.reject("native_not_initialized");
      return;
    }
    runOnPlayerThread(() -> {
      try {
        switch (action) {
          case "play": playbackController.play(); break;
          case "pause": playbackController.pause(); break;
          case "stop": playbackController.stop(); break;
        }
        JSObject state = buildPlaybackState(action);
        notifyListeners("playbackStateChanged", state);
        call.resolve(state);
      } catch (Throwable throwable) {
        call.reject(action + "_failed: " + throwable.getMessage());
        notifyListeners("nativePlayerError", buildError(action + "_failed", throwable.getMessage()));
      }
    });
  }

  private JSObject buildPlaybackState(String reason) {
    JSObject state = new JSObject();
    state.put("status", "ok");
    state.put("reason", reason);
    if (playbackController == null) {
      state.put("initialized", false);
      state.put("isPlaying", false);
      state.put("positionMs", 0);
      state.put("durationMs", 0);
      state.put("playbackState", -1);
      state.put("currentTrack", null);
      return state;
    }
    state.put("initialized", true);
    state.put("isPlaying", playbackController.isPlaying());
    state.put("positionMs", playbackController.getPosition());
    state.put("durationMs", playbackController.getDuration());
    state.put("playbackState", playbackController.getPlaybackState());
    NativeAudioTrack track = playbackController.getCurrentTrack();
    state.put("currentTrack", track == null ? null : toTrackObject(track));
    return state;
  }

  private JSObject toTrackObject(NativeAudioTrack track) {
    JSObject obj = new JSObject();
    obj.put("id", track.id);
    obj.put("title", track.title);
    obj.put("artist", track.artist);
    obj.put("album", track.album);
    obj.put("duration", track.duration);
    obj.put("source", track.source);
    obj.put("artworkUri", track.artworkUri);
    return obj;
  }
}
