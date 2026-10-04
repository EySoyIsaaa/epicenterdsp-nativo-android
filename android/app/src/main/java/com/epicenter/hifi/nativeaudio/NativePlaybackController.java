package com.epicenter.hifi.nativeaudio;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;

@OptIn(markerClass = UnstableApi.class)
public class NativePlaybackController {
  private static final String TAG = "NativePlayer";
  // Static counter: incremented every time DefaultRenderersFactory.buildAudioSink
  // is actually invoked. If this stays at 0 while audio is playing, our
  // RenderersFactory override is NOT being used.
  private static volatile int sBuildAudioSinkCallCount = 0;
  public static int getBuildAudioSinkCallCount() { return sBuildAudioSinkCallCount; }

  private final ExoPlayer player;
  private final NativeQueueManager queueManager = new NativeQueueManager();
  private final EpicenterAudioProcessor epicenterAudioProcessor;
  private final EqAudioProcessor eqAudioProcessor;
  private final ReverbAudioProcessor reverbAudioProcessor;

  public NativePlaybackController(Context context, Player.Listener listener) {
    epicenterAudioProcessor = new EpicenterAudioProcessor();
    eqAudioProcessor = new EqAudioProcessor();
    reverbAudioProcessor = new ReverbAudioProcessor();

    // CRÍTICO BUG FIX: en androidx.media3:1.3.1 la firma real de
    // DefaultRenderersFactory.buildAudioSink es de TRES parámetros
    //   (Context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams)
    // — no cuatro. La firma anterior (con enableOffload) NO sobreescribía el
    // método del padre, por lo que Media3 seguía usando el DefaultAudioSink
    // por defecto SIN nuestros EpicenterAudioProcessor / EqAudioProcessor.
    // Sin este override, los procesadores personalizados no entran al pipeline.
    DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
      @Nullable
      @Override
      protected AudioSink buildAudioSink(
          Context ctx,
          boolean enableFloatOutput,
          boolean enableAudioTrackPlaybackParams) {
        sBuildAudioSinkCallCount++;
        Log.i(TAG, "buildAudioSink CALLED (call #" + sBuildAudioSinkCallCount
            + ") floatOut=" + enableFloatOutput
            + " playbackParams=" + enableAudioTrackPlaybackParams);
        // IMPORTANTE: forzamos enableFloatOutput=false. Si el caller pidiera
        // PCM_FLOAT, nuestros AudioProcessors (PCM_16BIT only) lanzarían
        // UnhandledAudioFormatException y Media3 los saltaría silenciosamente.
        // La cola también define cuánto tarda en oírse un cambio de DSP. Un
        // factor 4 con límites de 250–750 ms dejaba entre 1 y 3 s de audio viejo
        // ya procesado en AudioTrack. Mantenemos una reserva conservadora para
        // la cadena DSP, pero sin multiplicarla: los cambios se oyen en ~100–200 ms.
        DefaultAudioSink.AudioTrackBufferSizeProvider bufferSizeProvider =
            new DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(100_000)  // 100ms
                .setMaxPcmBufferDurationUs(200_000)  // 200ms
                .setPcmBufferMultiplicationFactor(1)
                .build();
        AudioSink sink = new DefaultAudioSink.Builder(ctx)
          .setEnableFloatOutput(false)
          .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
          .setAudioTrackBufferSizeProvider(bufferSizeProvider)
          .setAudioProcessors(new AudioProcessor[] {
              epicenterAudioProcessor,
              eqAudioProcessor,
              reverbAudioProcessor
          })
          .build();
        Log.i(TAG, "buildAudioSink RETURNED custom DefaultAudioSink with "
            + "EpicenterAudioProcessor + EqAudioProcessor + ReverbAudioProcessor in chain");
        return sink;
      }
    };
    renderersFactory.setEnableAudioTrackPlaybackParams(true);
    renderersFactory.setEnableAudioFloatOutput(false);

    // CRÍTICO: ExoPlayer es single-threaded. Lo anclamos explícitamente al
    // main looper para que todas las llamadas al player puedan venir de los
    // listeners del plugin (que vamos a postear a main thread).
    // Proper media audio attributes + audio-focus handling: makes ExoPlayer a
    // first-class media player (pauses on focus loss / headset unplug) and is
    // what Media3's MediaSession + notification stack expects from a player.
    AudioAttributes audioAttributes = new AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build();
    player = new ExoPlayer.Builder(context, renderersFactory)
      .setLooper(Looper.getMainLooper())
      .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
      .setHandleAudioBecomingNoisy(true)
      .build();
    if (listener != null) {
      player.addListener(listener);
    }
    // Reset del DSP en cada cambio de pista. Media3 solo llama onFlush() cuando
    // el sink se vacía, y una transición de cola con el mismo formato NO lo
    // vacía (gapless), así que sin esto los motores arrastran filtros,
    // envolventes y —en audífonos— el periodo/fase del sub-oscilador de la
    // canción anterior, que se oye como una "burbuja" al arrancar la siguiente.
    player.addListener(new Player.Listener() {
      @Override
      public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        epicenterAudioProcessor.resetDsp();
        // El fundido de ENTRADA solo tiene sentido cuando la pista entró sola
        // al terminar la anterior. Si el usuario pulsó play o eligió una
        // canción, debe sonar de inmediato: arrancar desde silencio durante
        // varios segundos se sentiría como un fallo.
        fadeInArmed = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
        if (!fadeInArmed) applyVolume(1f);
      }
    });
    Log.i(TAG, "ExoPlayer created with custom RenderersFactory (looper=main)"
        + " buildAudioSinkCallCount=" + sBuildAudioSinkCallCount);
  }

  public void loadTrack(NativeAudioTrack track) {
    // CRÍTICO para la reproducción en segundo plano: si la pista YA está en la
    // cola de ExoPlayer, hay que saltar a su índice en vez de hacer
    // setMediaItem(), que reemplaza toda la cola por UN solo elemento.
    //
    // El controlador conserva la cola completa de Media3 y avanza por sí mismo
    // incluso cuando la interfaz no está activa.
    int existing = indexOfMediaId(track.id);
    if (existing >= 0) {
      // No se toca queueManager: su lista sigue siendo la cola correcta; solo
      // cambia qué elemento está sonando, y de eso lleva cuenta ExoPlayer.
      Log.i(TAG, "loadTrack -> saltando al indice " + existing + " de la cola (id=" + track.id + ")");
      player.seekToDefaultPosition(existing);
      player.prepare();
      return;
    }

    queueManager.setSingleTrack(track);
    MediaItem mediaItem = buildMediaItem(track);
    Log.i(TAG, "loadTrack source=" + track.source + " id=" + track.id + " title=" + track.title
        + " buildAudioSinkCalls=" + sBuildAudioSinkCallCount);
    player.setMediaItem(mediaItem);
    player.prepare();
  }

  /** Índice de la pista dentro de la cola actual de ExoPlayer, o -1. */
  private int indexOfMediaId(String mediaId) {
    if (mediaId == null) return -1;
    int count = player.getMediaItemCount();
    for (int i = 0; i < count; i++) {
      MediaItem item = player.getMediaItemAt(i);
      if (item != null && mediaId.equals(item.mediaId)) return i;
    }
    return -1;
  }

  /**
   * Builds a MediaItem WITH MediaMetadata (title/artist/album/artwork). Media3's
   * MediaSessionService reads this metadata to populate the system media
   * notification and the lock-screen controls — without it the notification has
   * nothing to show. Artwork is loaded by Media3's BitmapLoader from the
   * content:// album-art URI.
   */
  private static MediaItem buildMediaItem(NativeAudioTrack t) {
    MediaMetadata.Builder meta = new MediaMetadata.Builder()
        .setTitle(t.title)
        .setArtist(t.artist)
        .setAlbumTitle(t.album);
    if (t.artworkUri != null && !t.artworkUri.isEmpty()) {
      try {
        meta.setArtworkUri(Uri.parse(t.artworkUri));
      } catch (Throwable ignored) {}
    }
    return new MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(Uri.parse(t.source))
        .setMediaMetadata(meta.build())
        .build();
  }

  /**
   * Loads the full queue and starts playback at startIndex.
   * ExoPlayer pre-buffers adjacent items and auto-advances between tracks —
   * this enables gapless playback and background auto-advance without JS.
   */
  public void setQueue(java.util.List<NativeAudioTrack> tracks, int startIndex) {
    queueManager.setQueue(tracks);
    java.util.List<MediaItem> items = new java.util.ArrayList<>();
    for (NativeAudioTrack t : tracks) {
      items.add(buildMediaItem(t));
    }
    int clampedStart = Math.max(0, Math.min(startIndex, tracks.size() - 1));
    Log.i(TAG, "setQueue size=" + tracks.size() + " startIndex=" + clampedStart);
    player.setMediaItems(items, clampedStart, 0L);
    player.prepare();
  }

  public void addTrack(NativeAudioTrack track, int index) {
    if (track == null) return;
    int safeIndex = Math.max(0, Math.min(index, player.getMediaItemCount()));
    queueManager.insert(safeIndex, track);
    player.addMediaItem(safeIndex, buildMediaItem(track));
  }

  public void removeTrack(int index) {
    if (index < 0 || index >= player.getMediaItemCount()) return;
    player.removeMediaItem(index);
    queueManager.removeAt(index);
  }

  public void moveTrack(int from, int to) {
    if (from < 0 || to < 0 || from >= player.getMediaItemCount() || to >= player.getMediaItemCount() || from == to) return;
    player.moveMediaItem(from, to);
    queueManager.move(from, to);
  }

  public java.util.List<NativeAudioTrack> getQueue() { return queueManager.snapshot(); }

  public void clearQueue() {
    player.clearMediaItems();
    queueManager.clear();
  }

  public void nextTrack() {
    Log.i(TAG, "nextTrack");
    player.seekToNextMediaItem();
  }

  public void previousTrack() {
    Log.i(TAG, "previousTrack");
    player.seekToPreviousMediaItem();
  }

  public void skipToIndex(int index) {
    Log.i(TAG, "skipToIndex " + index);
    player.seekTo(index, 0L);
  }

  public int getCurrentIndex() {
    return player.getCurrentMediaItemIndex();
  }

  public NativeAudioTrack getCurrentTrack() {
    return queueManager.getTrackAtIndex(player.getCurrentMediaItemIndex());
  }

  public void play() {
    Log.i(TAG, "play (buildAudioSinkCalls=" + sBuildAudioSinkCallCount
        + " epicenterOnConfigure=" + epicenterAudioProcessor.wasOnConfigureCalled()
        + " epicenterQueueInput=" + epicenterAudioProcessor.wasQueueInputCalled()
        + " epicenterGetOutput=" + epicenterAudioProcessor.wasGetOutputCalled() + ")");
    player.play();
  }
  public void pause() { Log.i(TAG, "pause"); player.pause(); }
  public void stop() { Log.i(TAG, "stop"); player.stop(); }
  /**
   * Salta a una posición dentro de la pista actual.
   *
   * El audio se sirve por HTTP desde el servidor local del plugin. Si ExoPlayer
   * abrió esa fuente sin longitud conocida, la trata como emisión continua y
   * marca el ítem como NO buscable: entonces `seekTo` se ignora en silencio —
   * la barra se mueve en pantalla y la canción se queda donde iba. En ese caso
   * se rehace el ítem indicando la posición de arranque, que sí reposiciona la
   * fuente (el servidor soporta peticiones Range).
   */
  public void seekTo(long positionMs) {
    boolean seekable = player.isCurrentMediaItemSeekable();
    Log.i(TAG, "seekTo " + positionMs + " seekable=" + seekable
        + " dur=" + player.getDuration() + " pos=" + player.getCurrentPosition());
    if (seekable) {
      player.seekTo(positionMs);
      return;
    }
    MediaItem item = player.getCurrentMediaItem();
    if (item == null) {
      player.seekTo(positionMs); // sin ítem no hay nada mejor que intentar
      return;
    }
    boolean wasPlaying = player.getPlayWhenReady();
    Log.w(TAG, "seekTo fallback: reconstruyendo el item en " + positionMs + " ms");
    player.setMediaItem(item, positionMs);
    player.prepare();
    player.setPlayWhenReady(wasPlaying);
  }

  // ===== Pseudo-crossfade (fundido de salida y entrada, sin solape) =====
  //
  // Un crossfade REAL que solapa dos canciones necesitaría una segunda
  // instancia de ExoPlayer. Esto es la versión de un solo reproductor: baja el
  // volumen al final de la pista y lo sube al empezar la siguiente. Para el
  // oyente la transición deja de ser un corte seco, que es el 90% del efecto.
  //
  // Se ejecuta en el reproductor nativo para continuar en segundo plano. Usa
  // player.setVolume(), que es independiente
  // de la perilla Volume del DSP (esa se aplica dentro del C++).
  private boolean crossfadeEnabled = false;
  private long crossfadeMs = 5000;
  private final android.os.Handler crossfadeHandler =
      new android.os.Handler(Looper.getMainLooper());
  private float appliedVolume = 1f;
  /** Solo se funde la entrada si la pista llegó por avance automático. */
  private volatile boolean fadeInArmed = false;

  private final Runnable crossfadeTick = new Runnable() {
    @Override public void run() {
      try { updateCrossfadeVolume(); } catch (Throwable t) {
        Log.w(TAG, "crossfade tick falló", t);
      }
      if (crossfadeEnabled) crossfadeHandler.postDelayed(this, 50);
    }
  };

  public void setCrossfadeConfig(boolean enabled, long durationMs) {
    this.crossfadeEnabled = enabled;
    this.crossfadeMs = Math.max(500, Math.min(12000, durationMs));
    crossfadeHandler.removeCallbacks(crossfadeTick);
    if (enabled) {
      crossfadeHandler.post(crossfadeTick);
    } else {
      // Al apagarlo hay que devolver el volumen: si no, una pista que quedó a
      // media rampa se queda baja para siempre.
      applyVolume(1f);
    }
    Log.i(TAG, "setCrossfadeConfig enabled=" + enabled + " durationMs=" + this.crossfadeMs);
  }

  private void updateCrossfadeVolume() {
    if (!crossfadeEnabled) return;
    if (!player.isPlaying()) return;

    final long duration = player.getDuration();
    final long position = player.getCurrentPosition();
    if (duration <= 0 || position < 0) { applyVolume(1f); return; }

    // En pistas cortas el fundido se acorta para no dejar la canción entera en
    // rampa (nunca más de un tercio por lado).
    final long fade = Math.min(crossfadeMs, duration / 3);
    if (fade <= 0) { applyVolume(1f); return; }

    final long remaining = duration - position;
    float gain = 1f;
    if (remaining < fade) {
      gain = (float) remaining / fade;          // salida
    } else if (fadeInArmed && position < fade) {
      gain = (float) position / fade;           // entrada (solo tras avance auto)
    } else if (position >= fade) {
      fadeInArmed = false;                      // ya pasó la zona de entrada
    }
    // Curva de igual potencia: una rampa lineal se oye con un bajón en medio.
    gain = (float) Math.sin(Math.max(0f, Math.min(1f, gain)) * Math.PI / 2.0);
    applyVolume(gain);
  }

  private void applyVolume(float v) {
    final float clamped = Math.max(0f, Math.min(1f, v));
    if (Math.abs(clamped - appliedVolume) < 0.005f) return;   // evita spam
    appliedVolume = clamped;
    player.setVolume(clamped);
  }

  /** Diagnóstico: si sale false, `seekTo` normal no puede funcionar. */
  public boolean isSeekable() { return player.isCurrentMediaItemSeekable(); }
  public long getDuration() { return player.getDuration(); }
  public long getPosition() { return player.getCurrentPosition(); }
  public int getPlaybackState() { return player.getPlaybackState(); }
  public boolean isPlaying() { return player.isPlaying(); }
  public androidx.media3.common.Player getPlayer() { return player; }
  public void addPlayerListener(Player.Listener listener) { player.addListener(listener); }
  public void removePlayerListener(Player.Listener listener) { player.removeListener(listener); }
  public EpicenterAudioProcessor getEpicenterAudioProcessor() { return epicenterAudioProcessor; }
  public EqAudioProcessor getEqAudioProcessor() { return eqAudioProcessor; }
  public ReverbAudioProcessor getReverbAudioProcessor() { return reverbAudioProcessor; }

  public void release() {
    Log.i(TAG, "release");
    crossfadeEnabled = false;
    crossfadeHandler.removeCallbacks(crossfadeTick);
    try {
      player.release();
    } catch (Throwable t) {
      Log.w(TAG, "player.release failed", t);
    }
    try {
      epicenterAudioProcessor.release();
    } catch (Throwable t) {
      Log.w(TAG, "epicenterAudioProcessor.release failed", t);
    }
    try {
      eqAudioProcessor.release();
    } catch (Throwable t) {
      Log.w(TAG, "eqAudioProcessor.release failed", t);
    }
    try {
      reverbAudioProcessor.release();
    } catch (Throwable t) {
      Log.w(TAG, "reverbAudioProcessor.release failed", t);
    }
    queueManager.clear();
  }
}
