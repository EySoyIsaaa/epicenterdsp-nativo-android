package com.epicenter.hifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.MediaStyleNotificationHelper;

import com.epicenter.hifi.nativeaudio.NativePlaybackController;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EpicenterPlaybackService
 *
 * Owns the single ExoPlayer (with the Epicenter/EQ/Reverb DSP chain) wrapped in
 * a Media3 MediaSession so audio keeps playing in the background.
 *
 * MEDIA NOTIFICATION — MANUAL: instead of relying on Media3's automatic
 * notification (which wasn't rendering on some Android 10 / OEM devices), we
 * build and post our OWN MediaStyle notification (title/artist/artwork + prev /
 * play-pause / next) and manage the foreground state explicitly. We override
 * onUpdateNotification so Media3 routes its update calls to us, and we also
 * listen to the player so the notification always reflects the current state.
 */
@OptIn(markerClass = UnstableApi.class)
public class EpicenterPlaybackService extends MediaSessionService {

    private static final String TAG = "EpicenterService";

    /** Intent action used by the native activity to bind to the shared player. */
    public static final String ACTION_LOCAL_BIND = "com.epicenter.hifi.LOCAL_BIND";

    private static final String CHANNEL_ID = "epicenter_playback";
    private static final int NOTIF_ID = 1001;
    static final String ACTION_PLAY_PAUSE = "com.epicenter.hifi.PLAY_PAUSE";
    static final String ACTION_NEXT = "com.epicenter.hifi.NEXT";
    static final String ACTION_PREV = "com.epicenter.hifi.PREV";

    private MediaSession mediaSession;
    private NativePlaybackController playbackController;
    private PendingIntent contentIntent;

    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private String currentArtUri;
    private Bitmap currentArtBitmap;

    public class LocalBinder extends Binder {
        public EpicenterPlaybackService getService() {
            return EpicenterPlaybackService.this;
        }
    }
    private final LocalBinder localBinder = new LocalBinder();

    /** Set true while tearing down (task swiped away) so we stop re-posting the notification. */
    private volatile boolean shuttingDown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        playbackController = new NativePlaybackController(this, null);

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        }

        MediaSession.Builder builder =
            new MediaSession.Builder(this, playbackController.getPlayer());
        if (contentIntent != null) {
            builder.setSessionActivity(contentIntent);
        }
        mediaSession = builder.build();

        // Re-post the notification on every relevant player change so it always
        // reflects the current track / play state.
        playbackController.getPlayer().addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) { refreshNotification(false); }
            @Override public void onMediaMetadataChanged(MediaMetadata m) { refreshNotification(false); }
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { refreshNotification(false); }
        });

        Log.i(TAG, "onCreate: MediaSession + manual MediaStyle notification ready");
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action != null && playbackController != null) {
            switch (action) {
                case ACTION_PLAY_PAUSE:
                    if (playbackController.isPlaying()) playbackController.pause();
                    else playbackController.play();
                    return START_STICKY;
                case ACTION_NEXT:
                    playbackController.nextTrack();
                    return START_STICKY;
                case ACTION_PREV:
                    playbackController.previousTrack();
                    return START_STICKY;
                default:
                    break;
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Media3 calls this whenever it wants the notification refreshed. We take
     * over completely and post our own notification + manage foreground.
     */
    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        refreshNotification(startInForegroundRequired);
    }

    private void refreshNotification(boolean forceForeground) {
        if (shuttingDown || mediaSession == null || playbackController == null) return;
        Player player = playbackController.getPlayer();
        MediaMetadata meta = player.getMediaMetadata();

        // Album art (large icon) — load async + cache so it appears without blocking.
        String artUri = meta.artworkUri != null ? meta.artworkUri.toString() : null;
        if (artUri != null && !artUri.equals(currentArtUri)) {
            loadArtAsync(artUri);
        } else if (artUri == null) {
            currentArtUri = null;
            currentArtBitmap = null;
        }

        Notification notification = buildNotification(player, meta);
        if (forceForeground || player.isPlaying()) {
            startForeground(NOTIF_ID, notification);
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(false);
                }
            } catch (Throwable ignored) {}
            try {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, notification);
            } catch (Throwable ignored) {}
        }
    }

    private Notification buildNotification(Player player, MediaMetadata meta) {
        CharSequence title = meta.title != null ? meta.title : "Reproduciendo";
        CharSequence artist = meta.artist != null ? meta.artist : "";
        boolean playing = player.isPlaying();

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (contentIntent != null) b.setContentIntent(contentIntent);
        if (currentArtBitmap != null) b.setLargeIcon(currentArtBitmap);

        b.addAction(new NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Anterior", servicePendingIntent(ACTION_PREV)));
        b.addAction(new NotificationCompat.Action(
            playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            playing ? "Pausa" : "Play", servicePendingIntent(ACTION_PLAY_PAUSE)));
        b.addAction(new NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Siguiente", servicePendingIntent(ACTION_NEXT)));

        b.setStyle(new MediaStyleNotificationHelper.MediaStyle(mediaSession)
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action) {
        Intent i = new Intent(this, EpicenterPlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private void loadArtAsync(String uri) {
        currentArtUri = uri; // mark in-flight so we don't reload the same art
        artExecutor.execute(() -> {
            Bitmap bmp = null;
            try (InputStream is = getContentResolver().openInputStream(Uri.parse(uri))) {
                if (is != null) bmp = BitmapFactory.decodeStream(is);
            } catch (Throwable t) {
                Log.w(TAG, "art load failed: " + t.getMessage());
            }
            final Bitmap loaded = bmp;
            new Handler(getMainLooper()).post(() -> {
                if (uri.equals(currentArtUri) && loaded != null) {
                    currentArtBitmap = loaded;
                    try { refreshNotification(false); } catch (Throwable ignored) {}
                }
            });
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && ACTION_LOCAL_BIND.equals(intent.getAction())) {
            return localBinder;
        }
        return super.onBind(intent);
    }

    public NativePlaybackController getPlaybackController() {
        return playbackController;
    }

    /**
     * App swiped away from the recents/multitasking screen → tear everything
     * down: stop the music, remove the notification, and stop the service.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shuttingDown = true;
        try {
            if (playbackController != null) {
                playbackController.getPlayer().stop();
                playbackController.getPlayer().clearMediaItems();
            }
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { artExecutor.shutdownNow(); } catch (Throwable ignored) {}
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (playbackController != null) {
            playbackController.release();
            playbackController = null;
        }
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }
}
