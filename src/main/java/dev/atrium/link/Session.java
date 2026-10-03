package dev.atrium.link;

import dev.atrium.link.Protocol.Clip;
import dev.atrium.link.Protocol.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// One connection's rules, the same as atrium's Session (clipsync_core.cpp):
// fed events, answering with actions, never touching the clipboard itself.
//
// Both ends send HELLO; once the other's is in, each sends its HISTORY
// newest clips (oldest first) and SYNCED. Then each adds the other's clips
// it lacks to its history, and takes the other's newest as its clipboard if
// that is newer than its own. From then on every copy goes over live.
public final class Session {
    public static final class Action {
        public static final int SEND = 0, SET_CLIPBOARD = 1, ADD_HISTORY = 2, CLOSE = 3;
        public final int kind;
        public final byte[] bytes;
        public final Clip clip;

        Action(int kind, byte[] bytes, Clip clip) {
            this.kind = kind;
            this.bytes = bytes;
            this.clip = clip;
        }

        static Action send(byte[] b) {
            return new Action(SEND, b, null);
        }

        static Action close() {
            return new Action(CLOSE, null, null);
        }
    }

    private static final int PHASE_HELLO = 0, PHASE_HISTORY = 1, PHASE_LIVE = 2;

    private final String name;
    private List<Clip> recent;  // ours, newest first
    private final Clip current; // what our clipboard holds
    private final List<Clip> theirs = new ArrayList<>();
    private final long maxClip;
    private long peerMax;
    private int phase = PHASE_HELLO;
    private boolean helloSeen;
    private final Protocol.Reader reader;
    private String peerName = "";
    private Long peerCurrent;
    private final List<Clip> early = new ArrayList<>();

    public Session(String name, List<Clip> recent, long maxClip) {
        this.name = name;
        this.recent = new ArrayList<>(recent);
        this.current = recent.isEmpty() ? null : recent.get(0);
        this.maxClip = maxClip;
        this.reader = new Protocol.Reader(maxClip);
    }

    public boolean synced() {
        return phase == PHASE_LIVE;
    }

    public String peerName() {
        return peerName;
    }

    private long limit() {
        return Math.min(maxClip, peerMax);
    }

    // Newer first; the hash breaks ties so both ends pick the same one.
    static boolean newer(Clip a, Clip b) {
        if (a.time != b.time)
            return a.time > b.time;
        return Long.compareUnsigned(a.hash(), b.hash()) > 0;
    }

    public List<Action> start() {
        List<Action> out = new ArrayList<>();
        out.add(Action.send(Protocol.hello(name, maxClip)));
        return out;
    }

    public List<Action> received(byte[] b, int off, int n) {
        reader.feed(b, off, n);
        List<Action> out = new ArrayList<>();
        Message m;
        while ((m = reader.next()) != null) {
            out.addAll(handle(m));
            if (!out.isEmpty() && out.get(out.size() - 1).kind == Action.CLOSE)
                return out;
        }
        if (reader.broken())
            out.add(Action.close());
        return out;
    }

    private List<Action> handle(Message m) {
        List<Action> out = new ArrayList<>();
        switch (m.type) {
            case Protocol.HELLO: {
                if (helloSeen || m.version < 1) {
                    out.add(Action.close());
                    return out;
                }
                helloSeen = true;
                peerName = m.name;
                peerMax = m.maxClip;
                phase = PHASE_HISTORY;
                List<Clip> send = new ArrayList<>();
                for (Clip c : recent) {
                    if (send.size() == Protocol.HISTORY)
                        break;
                    if (c.data.length <= limit())
                        send.add(c);
                }
                recent = send;
                for (int i = send.size() - 1; i >= 0; i--)
                    out.add(Action.send(Protocol.clip(send.get(i), Protocol.FLAG_HISTORY)));
                out.add(Action.send(Protocol.synced()));
                break;
            }
            case Protocol.CLIP:
                if (phase == PHASE_HELLO) {
                    out.add(Action.close());
                    return out;
                }
                if (m.clip.data.length > maxClip)
                    break;
                if (phase == PHASE_HISTORY) {
                    theirs.add(m.clip);
                    break;
                }
                peerCurrent = m.clip.hash();
                out.add(new Action(Action.SET_CLIPBOARD, null, m.clip));
                break;
            case Protocol.SYNCED:
                if (phase != PHASE_HISTORY) {
                    out.add(Action.close());
                    return out;
                }
                out.addAll(merge());
                break;
        }
        return out;
    }

    private List<Action> merge() {
        List<Action> out = new ArrayList<>();
        phase = PHASE_LIVE;
        Set<Long> seen = new HashSet<>();
        for (Clip c : recent)
            seen.add(c.hash());

        // Their newest replaces our clipboard only when it is newer than
        // what ours holds (which may be a clip too big to have gone over).
        Clip newest = null;
        for (Clip c : theirs)
            if (newest == null || newer(c, newest))
                newest = c;
        Clip ours = recent.isEmpty() ? null : recent.get(0);
        boolean take = newest != null
                && (current == null || (newer(newest, current) && newest.hash() != current.hash()));

        List<Clip> missing = new ArrayList<>();
        for (Clip c : theirs)
            if (seen.add(c.hash()) && !(take && c == newest))
                missing.add(c);
        missing.sort((a, b) -> newer(a, b) ? 1 : newer(b, a) ? -1 : 0);
        for (Clip c : missing)
            out.add(new Action(Action.ADD_HISTORY, null, c));
        if (take)
            out.add(new Action(Action.SET_CLIPBOARD, null, newest));

        if (ours != null && (newest == null || newer(ours, newest)))
            peerCurrent = ours.hash();
        else if (newest != null)
            peerCurrent = newest.hash();
        theirs.clear();

        List<Clip> copies = new ArrayList<>(early);
        early.clear();
        for (Clip c : copies)
            out.addAll(copied(c));
        return out;
    }

    // The user copied something here. Clips this end put in the clipboard
    // itself (SET_CLIPBOARD, ADD_HISTORY) must not come back as copies.
    public List<Action> copied(Clip c) {
        List<Action> out = new ArrayList<>();
        if (phase != PHASE_LIVE) {
            early.add(c);
            return out;
        }
        long h = c.hash();
        if ((peerCurrent != null && peerCurrent == h) || c.data.length > limit())
            return out;
        peerCurrent = h;
        out.add(Action.send(Protocol.clip(c, Protocol.FLAG_LIVE)));
        return out;
    }
}
