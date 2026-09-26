package dev.atrium.clipsync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import dev.atrium.clipsync.Protocol.Clip;
import dev.atrium.clipsync.Session.Action;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

// The phone's end of atrium's clipboard sync, run as the shell user by
// service.sh: listens for atrium on an RFCOMM channel, and moves clips
// between each connection's Session and the clipboard. Everything but
// socket reads and writes happens on the main looper.
public final class Main {
    // Our own clips carry this label, so their change events are skipped.
    private static final String LABEL = "atrium-clipsync";
    private static final String SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";
    private static final String IS_REMOTE_DEVICE = "android.content.extra.IS_REMOTE_DEVICE";
    private static final String IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    // Between clips put in one after another, so a keyboard's clipboard
    // history sees each.
    private static final long SET_GAP_MS = 250;

    private final Context ctx;
    private final Handler main;
    private final ClipboardManager clipboard;
    private final RecentClips recent;
    private final String name;
    private final List<Link> links = new ArrayList<>();
    private final ArrayDeque<Clip> pendingSets = new ArrayDeque<>();
    private boolean setting;

    private Main(Context ctx, File state) {
        this.ctx = ctx;
        this.main = new Handler(Looper.getMainLooper());
        this.clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        this.recent = new RecentClips(new File(state, "history"));
        BluetoothAdapter adapter = adapter();
        String n = adapter != null ? adapter.getName() : null;
        this.name = n != null ? n : android.os.Build.MODEL;
    }

    // Deprecated for apps, whose main looper the framework makes; a process
    // started by app_process has to make its own.
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        File state = new File(args.length > 0 ? args[0] : ".");
        Main m = new Main(Env.context(), state);
        m.start();
        Looper.loop();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private void start() {
        Log.i("started as " + name);
        // The clip already there, if it is news to the history.
        Found found = read();
        Clip now = found != null ? load(found) : null;
        if (now != null)
            recent.current(now);
        clipboard.addPrimaryClipChangedListener(this::changed);
        Thread t = new Thread(this::serve, "rfcomm");
        t.setDaemon(true);
        t.start();
    }

    // --- the clipboard ---------------------------------------------------------

    // What the clipboard holds: text as a clip, or a picture still to be read.
    private static final class Found {
        Clip text;
        String mime, uri;
        long time;
    }

    // The clipboard's clip, or null when it is empty, ours or a secret.
    private Found read() {
        ClipData d;
        try {
            d = clipboard.getPrimaryClip();
        } catch (RuntimeException e) {
            Log.i("can't read the clipboard: " + e);
            return null;
        }
        if (d == null || d.getItemCount() == 0)
            return null;
        ClipDescription desc = d.getDescription();
        if (desc.getLabel() != null && LABEL.contentEquals(desc.getLabel()))
            return null;
        PersistableBundle extras = desc.getExtras();
        if (extras != null && extras.getBoolean(IS_SENSITIVE, false))
            return null;
        ClipData.Item item = d.getItemAt(0);
        String mime = desc.getMimeTypeCount() > 0 ? desc.getMimeType(0) : "";
        Found f = new Found();
        f.time = desc.getTimestamp();
        if (item.getUri() != null && mime.startsWith("image/")) {
            f.mime = mime;
            f.uri = item.getUri().toString();
            return f;
        }
        CharSequence text = item.getText();
        if (text == null)
            text = item.coerceToText(ctx);
        if (text == null || text.length() == 0)
            return null;
        f.text = Clip.text(text.toString(), f.time);
        return f;
    }

    // A found clip with its bytes: a picture's through `content read` (which
    // asks the provider as this uid, the one the clipboard granted access
    // to). Slow for pictures: off the main thread. Null when unreadable.
    private static Clip load(Found f) {
        if (f.text != null)
            return f.text;
        String mime = f.mime, uri = f.uri;
        try {
            Process p = new ProcessBuilder("/system/bin/content", "read", "--uri", uri).redirectErrorStream(false).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            try (InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > Protocol.MAX_CLIP) {
                        p.destroy();
                        return null;  // nothing will take it
                    }
                }
            }
            if (p.waitFor() != 0 || out.size() == 0)
                return null;
            return new Clip(mime, out.toByteArray(), f.time);
        } catch (IOException | InterruptedException e) {
            Log.i("can't read the picture: " + e);
            return null;
        }
    }

    private void changed() {
        Found f = read();
        if (f == null)
            return;
        if (f.text != null) {
            copied(f.text);
            return;
        }
        new Thread(() -> {
            Clip c = load(f);
            if (c != null)
                main.post(() -> copied(c));
        }, "picture").start();
    }

    private void copied(Clip c) {
        recent.current(c);
        for (Link l : new ArrayList<>(links))
            l.apply(l.session.copied(c));
    }

    // Puts clips in the clipboard one after another, the last one staying.
    private void put(Clip c) {
        pendingSets.add(c);
        if (!setting)
            nextSet();
    }

    private void nextSet() {
        Clip c = pendingSets.poll();
        if (c == null) {
            setting = false;
            return;
        }
        setting = true;
        ClipData d = c.isText() ? ClipData.newPlainText(LABEL, c.string()) : picture(c);
        if (d == null) {
            nextSet();
            return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(SUPPRESS_OVERLAY, true);
        extras.putBoolean(IS_REMOTE_DEVICE, true);
        d.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(d);
        } catch (RuntimeException e) {
            Log.i("can't set the clipboard: " + e);
        }
        main.postDelayed(this::nextSet, SET_GAP_MS);
    }

    // A picture as a clip other apps can paste: a file the shell package's
    // own FileProvider serves (content://com.android.shell/bugreports/...),
    // which the clipboard lets whoever pastes read.
    private static final File PICTURES = new File("/data/user_de/0/com.android.shell/files/bugreports");
    private static final String PICTURE_PREFIX = "atrium-clip-";
    private static final int PICTURES_KEPT = 10;

    private ClipData picture(Clip c) {
        String ext = c.mime.substring(c.mime.indexOf('/') + 1).replaceAll("[^a-z0-9]", "");
        File f = new File(PICTURES, PICTURE_PREFIX + Long.toHexString(c.hash()) + "." + ext);
        try {
            PICTURES.mkdirs();
            java.nio.file.Files.write(f.toPath(), c.data);
        } catch (IOException e) {
            Log.i("can't store the picture: " + e);
            return null;
        }
        // The oldest go; pastes of them from a keyboard's history stop working.
        File[] old = PICTURES.listFiles((dir, name) -> name.startsWith(PICTURE_PREFIX));
        if (old != null && old.length > PICTURES_KEPT) {
            java.util.Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = PICTURES_KEPT; i < old.length; i++)
                if (!old[i].equals(f))
                    old[i].delete();
        }
        android.net.Uri uri = android.net.Uri.parse("content://com.android.shell/bugreports/" + f.getName());
        return new ClipData(new ClipDescription(LABEL, new String[] {c.mime}), new ClipData.Item(uri));
    }

    // --- Bluetooth -------------------------------------------------------------

    // Listens while Bluetooth is on; atrium connects when the phone does.
    private void serve() {
        UUID uuid = UUID.fromString(Protocol.SERVICE_UUID);
        for (;;) {
            BluetoothAdapter adapter = adapter();
            if (adapter == null || !adapter.isEnabled()) {
                sleep(3000);
                continue;
            }
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("atrium clipboard", uuid);
                Log.i("listening");
                for (;;) {
                    BluetoothSocket s = server.accept();
                    main.post(() -> accepted(s));
                }
            } catch (IOException | SecurityException e) {
                Log.i("listening stopped: " + e.getMessage());
            } finally {
                if (server != null)
                    try {
                        server.close();
                    } catch (IOException ignored) {
                    }
            }
            sleep(3000);
        }
    }

    private void accepted(BluetoothSocket s) {
        Log.i("connected: " + s.getRemoteDevice().getAddress());
        Link l = new Link(s);
        links.add(l);
        l.begin();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // One PC's connection: a thread reading, a thread writing.
    private final class Link {
        final BluetoothSocket socket;
        final Session session;
        final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        boolean closed, addedHistory;

        Link(BluetoothSocket socket) {
            this.socket = socket;
            this.session = new Session(name, recent.recent(), Protocol.MAX_CLIP);
        }

        void begin() {
            Thread r = new Thread(this::reading, "read");
            Thread w = new Thread(this::writing, "write");
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
            apply(session.start());
        }

        void reading() {
            byte[] buf = new byte[65536];
            try {
                InputStream in = socket.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    main.post(() -> received(chunk));
                }
            } catch (IOException ignored) {
            }
            main.post(this::close);
        }

        void writing() {
            try {
                OutputStream o = socket.getOutputStream();
                for (;;) {
                    byte[] b = out.take();
                    if (b.length == 0)
                        break;
                    o.write(b);
                    o.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            }
            main.post(this::close);
        }

        void received(byte[] chunk) {
            if (closed)
                return;
            boolean was = session.synced();
            List<Action> actions = session.received(chunk, 0, chunk.length);
            apply(actions);
            if (closed || was || !session.synced())
                return;
            boolean took = false;
            for (Action a : actions)
                took |= a.kind == Action.SET_CLIPBOARD;
            Log.i("synced with " + session.peerName());
            // What the PC added went through the clipboard; ours is still the newest.
            if (addedHistory && !took && recent.first() != null)
                put(recent.first());
            addedHistory = false;
        }

        void apply(List<Action> actions) {
            for (Action a : actions) {
                if (closed)
                    return;
                switch (a.kind) {
                    case Action.SEND:
                        out.add(a.bytes);
                        break;
                    case Action.SET_CLIPBOARD:
                        put(a.clip);
                        recent.current(a.clip);
                        for (Link l : new ArrayList<>(links))
                            if (l != this)
                                l.apply(l.session.copied(a.clip));
                        break;
                    case Action.ADD_HISTORY:
                        put(a.clip);
                        recent.older(a.clip);
                        addedHistory = true;
                        break;
                    case Action.CLOSE:
                        close();
                        break;
                }
            }
        }

        void close() {
            if (closed)
                return;
            closed = true;
            links.remove(this);
            out.add(new byte[0]);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            Log.i("disconnected");
        }
    }
}
