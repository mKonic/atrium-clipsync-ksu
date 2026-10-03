package dev.atrium.clipsync;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.List;

// The phone's media session for atrium, which shows it as a media player
// (play, pause, skip, seek, cover art) and sends commands back. Shell holds
// MEDIA_CONTENT_CONTROL, so every app's session is visible. The one followed
// is the first that plays, else Android's first (the most recent).
final class MediaBridge {
    interface Listener {
        // On the main thread: a new MEDIA body for every PC.
        void changed(byte[] body);
    }

    private static final int ART_SIZE = 300;

    private final Context ctx;
    private final Handler main;
    private final Listener listener;
    private MediaSessionManager sessions;
    private MediaController controller;
    private byte[] current = none();
    private Bitmap artFor;  // the bitmap `art` came from
    private byte[] art = new byte[0];

    // On every active session: any of them starting to play may change
    // which one is followed (apps often go active first, then play).
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            follow(watched);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            push();
        }

        @Override
        public void onSessionDestroyed() {
            follow(sessions.getActiveSessions(null));
        }
    };
    private List<MediaController> watched = new java.util.ArrayList<>();

    MediaBridge(Context ctx, Handler main, Listener listener) {
        this.ctx = ctx;
        this.main = main;
        this.listener = listener;
    }

    void start() {
        try {
            sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            sessions.addOnActiveSessionsChangedListener(this::follow, null, main);
            follow(sessions.getActiveSessions(null));
        } catch (RuntimeException e) {
            Log.i("media: unavailable: " + e);
        }
    }

    // The last MEDIA body, for a PC that just connected.
    byte[] current() {
        return current;
    }

    private void follow(List<MediaController> list) {
        if (list != watched) {
            for (MediaController c : watched)
                c.unregisterCallback(callback);
            watched = list == null ? new java.util.ArrayList<>() : list;
            for (MediaController c : watched)
                c.registerCallback(callback, main);
        }
        MediaController pick = null;
        for (MediaController c : watched)
            if (c.getPlaybackState() != null && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                pick = c;
                break;
            }
        if (pick == null && controller != null)
            for (MediaController c : watched)
                if (c.getSessionToken().equals(controller.getSessionToken()))
                    pick = c;  // nothing plays: stay with the last one
        if (pick == null && !watched.isEmpty())
            pick = watched.get(0);
        if (pick != controller && (pick == null || controller == null
                || !pick.getSessionToken().equals(controller.getSessionToken()))) {
            controller = pick;
            Log.i("media: following " + (pick == null ? "nothing" : pick.getPackageName() + " " + pick.getTag()));
        } else {
            controller = pick;
        }
        push();
    }

    private void push() {
        byte[] body = describe();
        if (java.util.Arrays.equals(body, current))
            return;
        current = body;
        listener.changed(body);
    }

    private static byte[] none() {
        return Link.packMedia(Link.MEDIA_NONE, 0, 0, 0, "", "", "", "", null);
    }

    private byte[] describe() {
        MediaController c = controller;
        if (c == null)
            return none();
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        int status;
        switch (s == null ? PlaybackState.STATE_NONE : s.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                status = Link.MEDIA_PLAYING;
                break;
            case PlaybackState.STATE_PAUSED:
                status = Link.MEDIA_PAUSED;
                break;
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_ERROR:
                status = Link.MEDIA_STOPPED;
                break;
            default:
                status = m == null ? Link.MEDIA_NONE : Link.MEDIA_STOPPED;
        }
        int actions = 0;
        long position = 0;
        if (s != null) {
            long a = s.getActions();
            if ((a & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0)
                actions |= Link.CAN_PLAY;
            if ((a & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0)
                actions |= Link.CAN_PAUSE;
            if ((a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0)
                actions |= Link.CAN_NEXT;
            if ((a & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0)
                actions |= Link.CAN_PREVIOUS;
            if ((a & PlaybackState.ACTION_SEEK_TO) != 0)
                actions |= Link.CAN_SEEK;
            position = s.getPosition();
            if (status == Link.MEDIA_PLAYING && s.getLastPositionUpdateTime() > 0)
                position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        String title = "", artist = "", album = "";
        long duration = 0;
        Bitmap bitmap = null;
        if (m != null) {
            title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            album = text(m, MediaMetadata.METADATA_KEY_ALBUM, null);
            duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bitmap == null)
                bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        }
        // An idle session (YouTube keeps one, empty) is nothing to show.
        if (status != Link.MEDIA_PLAYING && status != Link.MEDIA_PAUSED && title.isEmpty())
            return none();
        return Link.packMedia(status, actions, duration, Math.max(0, position), title, artist, album,
                label(c.getPackageName()), cover(bitmap));
    }

    private static String text(MediaMetadata m, String key, String fallback) {
        CharSequence t = m.getText(key);
        if ((t == null || t.length() == 0) && fallback != null)
            t = m.getText(fallback);
        return t == null ? "" : t.toString();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    // A small JPEG of the cover, made again only when the bitmap changes.
    private byte[] cover(Bitmap b) {
        if (b == artFor)
            return art;
        artFor = b;
        art = new byte[0];
        if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0)
            return art;
        try {
            float scale = Math.min(1f, (float) ART_SIZE / Math.max(b.getWidth(), b.getHeight()));
            Bitmap small = scale < 1
                    ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                            Math.max(1, Math.round(b.getHeight() * scale)), true)
                    : b;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 85, o);
            art = o.toByteArray();
        } catch (RuntimeException e) {
            Log.i("media: cover: " + e);
        }
        return art;
    }

    // From a PC.
    void command(int command, long position) {
        MediaController c = controller;
        if (c == null)
            return;
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState s = c.getPlaybackState();
        switch (command) {
            case Link.CMD_PLAY:
                t.play();
                break;
            case Link.CMD_PAUSE:
                t.pause();
                break;
            case Link.CMD_PLAY_PAUSE:
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
                    t.pause();
                else
                    t.play();
                break;
            case Link.CMD_NEXT:
                t.skipToNext();
                break;
            case Link.CMD_PREVIOUS:
                t.skipToPrevious();
                break;
            case Link.CMD_STOP:
                t.stop();
                break;
            case Link.CMD_SEEK:
                t.seekTo(position);
                break;
            default:
        }
    }
}
