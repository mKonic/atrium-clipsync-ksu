package dev.atrium.clipsync;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

// The phone's end of the network link with atrium (Link has the rules):
// listens on TCP, announced over mDNS as Link.SERVICE_TYPE; atrium connects.
// Paired PCs may ask for the phone's audio (AudioSender).
//
// The module's WebUI talks to it through two files in the state directory:
// it writes `command` ("pair", "cancel", "accept", "reject", "connect <id>",
// "disconnect <id>", "auto <id> on|off", "forget <id>"), and reads
// `status.json`, rewritten on every change. Pairing is open for
// PAIR_WINDOW_MS after "pair". The paired PCs are in `paired`, one per line:
// hex id, hex key, name.
final class LanLink {
    private static final long PAIR_WINDOW_MS = 120_000;
    // How long "connect" asks the PC (over mDNS: the PC connects, not us).
    private static final long CALL_MS = 60_000;

    private final Context ctx;
    private final Handler main;
    private final File dir, pairedFile, statusFile, commandFile;
    private final String name;
    private final byte[] id;
    private final Map<String, String[]> paired = new LinkedHashMap<>();  // hex id -> {hex key, name}
    // PCs that may connect only when asked (Connect on either end), not by
    // themselves: in `manual`, one id a line.
    private final java.util.Set<String> manual = new java.util.HashSet<>();
    private final File manualFile;
    private final List<Conn> conns = new ArrayList<>();
    private final MediaBridge media;
    private int port;
    private long pairUntil;
    private Conn confirming;  // the connection showing a pairing code
    private String code;
    private String lastError = "";
    private FileObserver observer;  // kept: it stops when collected
    private Mdns mdns;
    private String callFor = "";  // the PC asked to connect
    private long callUntil, callSeq;

    LanLink(Context ctx, Handler main, File dir, String name) {
        this.ctx = ctx;
        this.main = main;
        this.dir = dir;
        this.name = name;
        this.pairedFile = new File(dir, "paired");
        this.statusFile = new File(dir, "status.json");
        this.commandFile = new File(dir, "command");
        this.manualFile = new File(dir, "manual");
        this.id = loadId(new File(dir, "id"));
        this.media = new MediaBridge(ctx, main, body -> {
            for (Conn k : new ArrayList<>(conns))
                if (k.link.ready())
                    k.apply(k.link.message(Link.MEDIA, body));
        });
        loadPaired();
    }

    private static byte[] loadId(File f) {
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            if (b.length == Link.ID_SIZE)
                return b;
        } catch (IOException ignored) {
        }
        byte[] b = Crypto.random(Link.ID_SIZE);
        try {
            Files.write(f.toPath(), b);
        } catch (IOException e) {
            Log.i("lan: can't store the id: " + e);
        }
        return b;
    }

    private void loadPaired() {
        try {
            for (String line : Files.readAllLines(pairedFile.toPath(), StandardCharsets.UTF_8)) {
                String[] f = line.split(" ", 3);
                if (f.length >= 2)
                    paired.put(f[0], new String[] {f[1], f.length > 2 ? f[2] : ""});
            }
        } catch (IOException ignored) {
        }
        try {
            for (String line : Files.readAllLines(manualFile.toPath(), StandardCharsets.UTF_8))
                if (paired.containsKey(line.trim()))
                    manual.add(line.trim());
        } catch (IOException ignored) {
        }
    }

    private void saveManual() {
        StringBuilder sb = new StringBuilder();
        for (String pc : manual)
            sb.append(pc).append('\n');
        writeAtomically(manualFile, sb.toString());
    }

    private void savePaired() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : paired.entrySet())
            sb.append(e.getKey()).append(' ').append(e.getValue()[0]).append(' ').append(e.getValue()[1]).append('\n');
        writeAtomically(pairedFile, sb.toString());
    }

    private static void writeAtomically(File f, String s) {
        File tmp = new File(f.getPath() + ".new");
        try {
            Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f))
                throw new IOException("rename failed");
        } catch (IOException e) {
            Log.i("lan: can't write " + f.getName() + ": " + e);
        }
    }

    void start() {
        commandFile.delete();
        observer = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if ("command".equals(path))
                    main.post(LanLink.this::command);
            }
        };
        observer.startWatching();
        media.start();
        Thread t = new Thread(this::serve, "lan");
        t.setDaemon(true);
        t.start();
        writeStatus();
    }

    // --- the WebUI -------------------------------------------------------------

    private void command() {
        String c;
        try {
            c = new String(Files.readAllBytes(commandFile.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            if (commandFile.exists())
                Log.i("lan: can't read the command: " + e);
            return;
        }
        commandFile.delete();
        Log.i("lan: command " + c);
        if (c.equals("pair")) {
            pairUntil = System.currentTimeMillis() + PAIR_WINDOW_MS;
            for (Conn k : conns)
                k.link.setPairing(true);
            main.postDelayed(this::writeStatus, PAIR_WINDOW_MS + 100);
        } else if (c.startsWith("connect ")) {
            callFor = c.substring(8).trim();
            callUntil = System.currentTimeMillis() + CALL_MS;
            callSeq = System.currentTimeMillis() / 1000;
            main.postDelayed(this::writeStatus, CALL_MS + 100);
        } else if (c.startsWith("disconnect ")) {
            String pc = c.substring(11).trim();
            for (Conn k : new ArrayList<>(conns))
                if (k.link.ready() && Crypto.hex(k.link.peerId()).equals(pc))
                    k.disconnect();
        } else if (c.startsWith("auto ")) {
            String[] a = c.split(" ");
            if (a.length == 3 && paired.containsKey(a[1])) {
                if (a[2].equals("on")) {
                    manual.remove(a[1]);
                    // A PC kept away by the switch may come now.
                    callFor = a[1];
                    callUntil = System.currentTimeMillis() + CALL_MS;
            callSeq = System.currentTimeMillis() / 1000;
                    main.postDelayed(this::writeStatus, CALL_MS + 100);
                } else {
                    manual.add(a[1]);
                }
                saveManual();
            }
        } else if (c.equals("cancel")) {
            pairUntil = 0;
            if (confirming != null)
                confirming.apply(confirming.link.reject());
        } else if (c.equals("accept") && confirming != null) {
            confirming.apply(confirming.link.accept());
        } else if (c.equals("reject") && confirming != null) {
            confirming.apply(confirming.link.reject());
        } else if (c.startsWith("forget ")) {
            String pc = c.substring(7).trim();
            if (paired.remove(pc) != null) {
                savePaired();
                if (manual.remove(pc))
                    saveManual();
                for (Conn k : new ArrayList<>(conns))
                    if (Crypto.hex(k.link.peerId()).equals(pc))
                        k.close();
            }
        }
        writeStatus();
    }

    private boolean pairingOpen() {
        return System.currentTimeMillis() < pairUntil;
    }

    private boolean calling(String pc) {
        return callFor.equals(pc) && System.currentTimeMillis() < callUntil;
    }

    // What mDNS says besides the id (atrium's link_core.hpp has the keys).
    private void updateTxt() {
        if (mdns == null)
            return;
        List<String> t = new ArrayList<>();
        if (pairingOpen())
            t.add("pair=1");
        // A new value each time: avahi only reports a TXT record it hasn't
        // got cached, and the last call's may still be.
        if (calling(callFor))
            t.add("call=" + callFor + "-" + callSeq);
        mdns.setTxt(t.toArray(new String[0]));
    }

    // Also keeps mDNS saying the same.
    private void writeStatus() {
        updateTxt();
        try {
            JSONObject s = new JSONObject();
            s.put("name", name);
            s.put("port", port);
            s.put("error", lastError);
            JSONArray pcs = new JSONArray();
            for (Map.Entry<String, String[]> e : paired.entrySet()) {
                JSONObject p = new JSONObject();
                p.put("id", e.getKey());
                p.put("name", e.getValue()[1]);
                boolean connected = false, streaming = false;
                for (Conn k : conns) {
                    if (k.link.ready() && Crypto.hex(k.link.peerId()).equals(e.getKey())) {
                        connected = true;
                        streaming |= k.audio != null;
                    }
                }
                p.put("connected", connected);
                p.put("streaming", streaming);
                p.put("calling", !connected && calling(e.getKey()));
                p.put("auto", !manual.contains(e.getKey()));
                pcs.put(p);
            }
            s.put("pcs", pcs);
            JSONObject pairing = new JSONObject();
            pairing.put("open", pairingOpen());
            pairing.put("until", pairUntil);
            if (confirming != null && code != null) {
                pairing.put("code", code);
                pairing.put("pc", confirming.link.peerName());
            }
            s.put("pairing", pairing);
            writeAtomically(statusFile, s.toString(1));
        } catch (JSONException e) {
            Log.i("lan: status: " + e);
        }
    }

    // --- the network -----------------------------------------------------------

    private void serve() {
        // The same port as last time when it's free: a PC that knew it
        // reconnects before mDNS tells it otherwise.
        File portFile = new File(dir, "port");
        ServerSocket server = null;
        try {
            int last = Integer.parseInt(new String(Files.readAllBytes(portFile.toPath()), StandardCharsets.UTF_8).trim());
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(last));
        } catch (IOException | NumberFormatException e) {
            server = null;
        }
        try {
            if (server == null || !server.isBound())
                server = new ServerSocket(0);
        } catch (IOException e) {
            Log.i("lan: can't listen: " + e);
            return;
        }
        port = server.getLocalPort();
        writeAtomically(portFile, Integer.toString(port));
        Log.i("lan: listening on " + port);
        main.post(() -> {
            announce();
            writeStatus();
        });
        for (;;) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                main.post(() -> accepted(s));
            } catch (IOException e) {
                Log.i("lan: accept: " + e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void announce() {
        mdns = new Mdns(name, Link.SERVICE_TYPE, port, id);
        mdns.start();
    }

    private void accepted(Socket s) {
        Log.i("lan: connection from " + s.getInetAddress().getHostAddress());
        Conn k = new Conn(s);
        conns.add(k);
        k.begin();
    }

    // One PC's connection: a thread reading, a thread writing; the rest on main.
    private final class Conn {
        final Socket socket;
        final Link link;
        final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        AudioSender audio;
        int nextSeq;
        boolean closed;

        Conn(Socket socket) {
            this.socket = socket;
            this.link = new Link(Link.ROLE_PHONE, id, name, peer -> {
                String[] p = paired.get(Crypto.hex(peer));
                return p != null ? Crypto.unhex(p[0]) : null;
            }, Crypto::random);
            link.setPairing(pairingOpen());
        }

        void begin() {
            Thread r = new Thread(this::reading, "lan-read");
            Thread w = new Thread(this::writing, "lan-write");
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
            apply(link.start());
        }

        void reading() {
            byte[] buf = new byte[65536];
            try {
                InputStream in = socket.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] chunk = java.util.Arrays.copyOf(buf, n);
                    main.post(() -> {
                        if (!closed)
                            apply(link.received(chunk, 0, chunk.length));
                    });
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

        void apply(List<Link.Event> events) {
            for (Link.Event e : events) {
                if (closed)
                    return;
                switch (e.kind) {
                    case Link.Event.SEND:
                        out.add(e.bytes);
                        break;
                    case Link.Event.PAIR_CODE:
                        Log.i("lan: pairing with " + link.peerName() + ", code " + e.text);
                        if (confirming != null && confirming != this)
                            confirming.apply(confirming.link.reject());
                        confirming = this;
                        code = e.text;
                        writeStatus();
                        break;
                    case Link.Event.PAIRED:
                        paired.put(Crypto.hex(link.peerId()), new String[] {Crypto.hex(e.bytes), link.peerName()});
                        savePaired();
                        confirming = null;
                        code = null;
                        pairUntil = 0;
                        Log.i("lan: paired with " + link.peerName());
                        writeStatus();
                        break;
                    case Link.Event.READY:
                        Log.i("lan: ready with " + link.peerName());
                        if (callFor.equals(Crypto.hex(link.peerId())))
                            callUntil = 0;  // answered
                        // The name may have changed since pairing.
                        String[] p = paired.get(Crypto.hex(link.peerId()));
                        if (p != null && !p[1].equals(link.peerName())) {
                            p[1] = link.peerName();
                            savePaired();
                        }
                        apply(link.message(Link.MEDIA, media.current()));
                        writeStatus();
                        break;
                    case Link.Event.MESSAGE:
                        message(e.type, e.bytes);
                        break;
                    case Link.Event.CLOSE:
                        Log.i("lan: closing: " + e.text);
                        lastError = e.text;
                        finish();
                        return;
                    default:
                }
            }
        }

        void message(int type, byte[] body) {
            if (type == Link.AUDIO_START && body.length >= 4) {
                int udpPort = (int) Link.get(body, 0, 2), frames = (int) Link.get(body, 2, 2);
                startAudio(socket.getInetAddress(), udpPort, frames);
            } else if (type == Link.AUDIO_STOP) {
                stopAudio();
                state(Link.AUDIO_STOPPED, "");
            } else if (type == Link.WANT && body.length >= 1) {
                String pc = Crypto.hex(link.peerId());
                if (body[0] == 0 && manual.contains(pc)) {
                    Log.i("lan: " + link.peerName() + " came by itself; it connects only when asked");
                    disconnect();
                }
            } else if (type == Link.MEDIA_COMMAND) {
                long[] c = Link.unpackMediaCommand(body);
                if (c != null)
                    media.command((int) c[0], c[1]);
            }
        }

        void startAudio(InetAddress pc, int udpPort, int frames) {
            // One PC at a time has the sound.
            for (Conn k : conns)
                if (k != this && k.audio != null) {
                    k.stopAudio();
                    k.state(Link.AUDIO_STOPPED, "another PC took the audio");
                }
            stopAudio();
            AudioSender a = new AudioSender(ctx, link.datagramKey(), nextSeq, pc, udpPort, frames, port, why -> main.post(() -> {
                if (audio == null || why == null)
                    return;
                nextSeq = audio.nextSeq();
                audio = null;
                state(Link.AUDIO_FAILED, why);
                writeStatus();
            }));
            try {
                a.start();
                audio = a;
                state(Link.AUDIO_STREAMING, "");
            } catch (IOException e) {
                Log.i("audio: can't capture: " + e.getMessage());
                state(Link.AUDIO_FAILED, String.valueOf(e.getMessage()));
            }
            writeStatus();
        }

        void stopAudio() {
            if (audio == null)
                return;
            AudioSender a = audio;
            audio = null;
            a.stop();
            nextSeq = a.nextSeq();
            writeStatus();
        }

        void state(int state, String why) {
            byte[] w = why.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[1 + w.length];
            body[0] = (byte) state;
            System.arraycopy(w, 0, body, 1, w.length);
            apply(link.message(Link.AUDIO_STATE, body));
        }

        // The user's: the PC stays away until asked back.
        void disconnect() {
            stopAudio();
            apply(link.message(Link.DISCONNECT, new byte[0]));
            finish();
        }

        // Closes once what's queued is out (a REFUSED or UNKNOWN tells the
        // PC why), or after a while if the PC isn't taking it.
        void finish() {
            stopAudio();
            out.add(new byte[0]);  // the writer closes when it gets here
            main.postDelayed(this::close, 2000);
        }

        void close() {
            if (closed)
                return;
            closed = true;
            stopAudio();
            conns.remove(this);
            if (confirming == this) {
                // A code that was shown and didn't pair (rejected on either
                // end, cancelled, cut off) ends the pairing: the user starts
                // a new one, not the PC retrying.
                confirming = null;
                code = null;
                pairUntil = 0;
            }
            out.add(new byte[0]);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            Log.i("lan: disconnected" + (link.peerName().isEmpty() ? "" : " from " + link.peerName()));
            writeStatus();
        }
    }
}
