package dev.atrium.link;

import dev.atrium.link.Protocol.Clip;
import dev.atrium.link.Protocol.Message;
import dev.atrium.link.Session.Action;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// The same cases as atrium's tests/clipsync_test.cpp, on a plain JVM:
// `./build.sh test`.
public final class SessionTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(Object got, Object want, String what) {
        check(got.equals(want), what + ": got " + got + ", want " + want);
    }

    private static Clip text(String s, long t) {
        return Clip.text(s, t);
    }

    static final class End {
        Session session;
        List<String> clipboard = new ArrayList<>(), history = new ArrayList<>();
        boolean closed;
        ByteArrayOutputStream outbox = new ByteArrayOutputStream();

        End(String name, List<Clip> recent) {
            this(name, recent, Protocol.MAX_CLIP);
        }

        End(String name, List<Clip> recent, long max) {
            session = new Session(name, recent, max);
        }

        void apply(List<Action> actions) {
            for (Action a : actions) {
                switch (a.kind) {
                    case Action.SEND: outbox.write(a.bytes, 0, a.bytes.length); break;
                    case Action.SET_CLIPBOARD: clipboard.add(a.clip.string()); break;
                    case Action.ADD_HISTORY: history.add(a.clip.string()); break;
                    case Action.CLOSE: closed = true; break;
                }
            }
        }

        byte[] take() {
            byte[] b = outbox.toByteArray();
            outbox.reset();
            return b;
        }
    }

    static void pump(End a, End b) {
        while (a.outbox.size() > 0 || b.outbox.size() > 0) {
            byte[] ab = a.take(), ba = b.take();
            if (ab.length > 0)
                b.apply(b.session.received(ab, 0, ab.length));
            if (ba.length > 0)
                a.apply(a.session.received(ba, 0, ba.length));
        }
    }

    static void connect(End a, End b) {
        a.apply(a.session.start());
        b.apply(b.session.start());
        pump(a, b);
    }

    static List<String> list(String... s) {
        return Arrays.asList(s);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] args) {
        // Wire bytes and hash, pinned by an independent encoder (atrium's
        // test pins the same ones).
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (byte[] f : new byte[][] {Protocol.hello("pc", 1234), Protocol.clip(text("héllo", 42), Protocol.FLAG_LIVE), Protocol.synced()})
            wire.write(f, 0, f.length);
        byte[] golden = hex("00000009010001000004d270630000002a02000000000000002a020018746578742f706c61696e3b636861727365743d7574662d3868c3a96c6c6f0000000103");
        check(Arrays.equals(wire.toByteArray(), golden), "wire bytes");
        eq(Long.toHexString(text("hello", 0).hash()), "6cd2490d6771879b", "hash");

        // Any split.
        Protocol.Reader r = new Protocol.Reader(Protocol.MAX_CLIP);
        List<Message> got = new ArrayList<>();
        for (byte b : golden) {
            r.feed(new byte[] {b}, 0, 1);
            Message m;
            while ((m = r.next()) != null)
                got.add(m);
        }
        eq(got.size(), 3, "messages");
        eq(got.get(0).name, "pc", "hello name");
        eq(got.get(0).maxClip, 1234L, "hello max");
        eq(got.get(1).clip.string(), "héllo", "clip");
        eq(got.get(1).clip.time, 42L, "clip time");
        eq(got.get(2).type, Protocol.SYNCED, "synced");

        Protocol.Reader big = new Protocol.Reader(100);
        byte[] f = Protocol.clip(text("x".repeat(100 + 0x20000), 1), Protocol.FLAG_LIVE);
        big.feed(f, 0, f.length);
        check(big.next() == null && big.broken(), "oversized frame breaks");

        byte[] future = new byte[] {0, 0, 0, 3, 99, 'a', 'b'};
        Protocol.Reader skip = new Protocol.Reader(Protocol.MAX_CLIP);
        skip.feed(future, 0, future.length);
        byte[] s = Protocol.synced();
        skip.feed(s, 0, s.length);
        Message sm = skip.next();
        check(sm != null && sm.type == Protocol.SYNCED, "unknown skipped");

        {
            End pc = new End("pc", List.of(text("p3", 30), text("p2", 20), text("p1", 10)));
            End phone = new End("phone", List.of(text("f2", 25), text("f1", 5)));
            connect(pc, phone);
            check(pc.session.synced() && phone.session.synced(), "synced");
            eq(pc.session.peerName(), "phone", "peer name");
            eq(pc.clipboard, list(), "pc keeps newest");
            eq(phone.clipboard, list("p3"), "phone takes newest");
            eq(pc.history, list("f1", "f2"), "pc history");
            eq(phone.history, list("p1", "p2"), "phone history");
        }
        {
            End pc = new End("pc", List.of(text("p1", 10)));
            End phone = new End("phone", List.of(text("f1", 50)));
            connect(pc, phone);
            eq(pc.clipboard, list("f1"), "other newest wins");
            eq(phone.history, list("p1"), "other newest wins: history");
        }
        {
            End pc = new End("pc", List.of(text("same", 40), text("p1", 10)));
            End phone = new End("phone", List.of(text("same", 45), text("f1", 5)));
            connect(pc, phone);
            eq(pc.history, list("f1"), "shared not twice (pc)");
            eq(phone.history, list("p1"), "shared not twice (phone)");
            check(pc.clipboard.isEmpty() && phone.clipboard.isEmpty(), "shared: nothing set");
        }
        {
            End pc = new End("pc", List.of(text("x", 30), text("a", 10)));
            End phone = new End("phone", List.of(text("a", 50)));
            connect(pc, phone);
            eq(pc.clipboard, list("a"), "newest in history set again");
            eq(phone.history, list("x"), "newest in history: other side");
        }
        {
            List<Clip> many = new ArrayList<>();
            for (int i = 9; i >= 0; i--)
                many.add(text("p" + i, i + 1));
            End pc = new End("pc", many);
            End phone = new End("phone", List.of());
            connect(pc, phone);
            eq(phone.clipboard, list("p9"), "five: clipboard");
            eq(phone.history, list("p5", "p6", "p7", "p8"), "five: history");
        }
        {
            End pc = new End("pc", List.of(text("p1", 10)));
            End phone = new End("phone", List.of());
            connect(pc, phone);
            phone.apply(phone.session.copied(text("p1", 11)));
            eq(phone.outbox.size(), 0, "peer's clip not sent back");
            pc.apply(pc.session.copied(text("b", 20)));
            pump(pc, phone);
            eq(phone.clipboard.get(phone.clipboard.size() - 1), "b", "live pc to phone");
            phone.apply(phone.session.copied(text("from phone", 30)));
            pump(pc, phone);
            eq(pc.clipboard, list("from phone"), "live phone to pc");
        }
        {
            String bigText = "b".repeat(2000);
            End pc = new End("pc", List.of(text(bigText, 50), text("small", 10)), 1000);
            End phone = new End("phone", List.of(text(bigText + "!", 60)));
            connect(pc, phone);
            check(pc.clipboard.isEmpty() && phone.clipboard.isEmpty(), "limit: nobody gives up a newer clip");
            eq(phone.history, list("small"), "limit: history");
            phone.apply(phone.session.copied(text(bigText, 80)));
            eq(phone.outbox.size(), 0, "limit: too big not sent");
        }
        {
            End pc = new End("pc", List.of(text("p1", 10)));
            End phone = new End("phone", List.of());
            pc.apply(pc.session.start());
            pc.apply(pc.session.copied(text("early", 20)));
            phone.apply(phone.session.start());
            pump(pc, phone);
            eq(phone.clipboard, list("p1", "early"), "early copies go after");
        }
        {
            Session x = new Session("pc", List.of(), Protocol.MAX_CLIP);
            byte[] c = Protocol.clip(text("x", 1), Protocol.FLAG_LIVE);
            List<Action> a = x.received(c, 0, c.length);
            check(a.get(a.size() - 1).kind == Action.CLOSE, "clip before hello closes");
        }

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("all passed");
    }
}
