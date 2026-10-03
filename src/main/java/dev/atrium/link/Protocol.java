package dev.atrium.link;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// The wire format shared with atrium (shell/clipsync/clipsync_core.hpp in the
// atrium repo, which documents it); change one, change both. Every message
// is a frame: u32 length (big endian, of what follows), u8 type, body.
//   HELLO   u16 version, u32 biggest clip it takes, name (UTF-8)
//   CLIP    i64 time (ms since the epoch), u8 flags, u16 mime length, mime, data
//   SYNCED  nothing
public final class Protocol {
    public static final String SERVICE_UUID = "7a1e5c0d-5ca1-4c1b-9d2e-a7c1195c0de1";
    public static final int VERSION = 1;
    public static final int HISTORY = 5;
    public static final int MAX_CLIP = 10 << 20;
    public static final String TEXT = "text/plain;charset=utf-8";

    public static final int HELLO = 1, CLIP = 2, SYNCED = 3;
    public static final int FLAG_HISTORY = 1, FLAG_LIVE = 2;

    private static final int OVERHEAD = 64;

    private Protocol() {}

    public static final class Clip {
        public final String mime;
        public final byte[] data;
        public final long time;  // ms since the epoch; 0 when not known

        public Clip(String mime, byte[] data, long time) {
            this.mime = mime;
            this.data = data;
            this.time = time;
        }

        public static Clip text(String s, long time) {
            return new Clip(TEXT, s.getBytes(StandardCharsets.UTF_8), time);
        }

        public boolean isText() {
            return TEXT.equals(mime);
        }

        public String string() {
            return new String(data, StandardCharsets.UTF_8);
        }

        public Clip at(long t) {
            return new Clip(mime, data, t);
        }

        // FNV-1a over mime, a zero byte, data: the same number atrium computes.
        public long hash() {
            long h = 0xcbf29ce484222325L;
            for (byte b : mime.getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
            h *= 0x100000001b3L;  // the zero byte
            for (byte b : data) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
            return h;
        }
    }

    public static final class Message {
        public int type;
        public int version;
        public long maxClip;
        public String name = "";
        public int flags;
        public Clip clip;
    }

    private static byte[] frame(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 5);
        put(out, body.length + 1L, 4);
        out.write(type);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, long v, int bytes) {
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8)
            out.write((int) (v >>> shift) & 0xff);
    }

    private static long get(byte[] b, int at, int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++)
            v = v << 8 | (b[at + i] & 0xff);
        return v;
    }

    public static byte[] hello(String name, long maxClip) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        put(body, VERSION, 2);
        put(body, maxClip, 4);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        body.write(n, 0, n.length);
        return frame(HELLO, body.toByteArray());
    }

    public static byte[] clip(Clip c, int flags) {
        byte[] mime = c.mime.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream(c.data.length + mime.length + 11);
        put(body, c.time, 8);
        body.write(flags);
        put(body, mime.length, 2);
        body.write(mime, 0, mime.length);
        body.write(c.data, 0, c.data.length);
        return frame(CLIP, body.toByteArray());
    }

    public static byte[] synced() {
        return frame(SYNCED, new byte[0]);
    }

    // Cuts a byte stream into messages. A frame bigger than the limit, or one
    // that doesn't parse, breaks it for good: the connection is to be dropped.
    public static final class Reader {
        private byte[] buf = new byte[4096];
        private int len;
        private final long limit;
        private boolean broken;

        public Reader(long limit) {
            this.limit = limit;
        }

        public void feed(byte[] b, int off, int n) {
            if (len + n > buf.length)
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            System.arraycopy(b, off, buf, len, n);
            len += n;
        }

        public boolean broken() {
            return broken;
        }

        private void consume(int n) {
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }

        public Message next() {
            while (true) {
                if (broken || len < 4)
                    return null;
                long length = get(buf, 0, 4);
                if (length == 0 || length > limit + OVERHEAD + 0xffff) {
                    broken = true;
                    return null;
                }
                if (len < 4 + length)
                    return null;
                int n = (int) length;
                byte[] body = Arrays.copyOfRange(buf, 5, 4 + n);
                Message m = new Message();
                m.type = buf[4] & 0xff;
                boolean ok = true;
                switch (m.type) {
                    case HELLO:
                        ok = body.length >= 6;
                        if (ok) {
                            m.version = (int) get(body, 0, 2);
                            m.maxClip = get(body, 2, 4);
                            m.name = new String(body, 6, body.length - 6, StandardCharsets.UTF_8);
                        }
                        break;
                    case CLIP: {
                        ok = body.length >= 11;
                        if (!ok)
                            break;
                        long time = get(body, 0, 8);
                        m.flags = body[8] & 0xff;
                        int mime = (int) get(body, 9, 2);
                        ok = body.length >= 11 + mime;
                        if (ok)
                            m.clip = new Clip(new String(body, 11, mime, StandardCharsets.UTF_8),
                                    Arrays.copyOfRange(body, 11 + mime, body.length), time);
                        break;
                    }
                    case SYNCED:
                        break;
                    default:
                        // A newer peer's message this end doesn't know: skipped.
                        consume(4 + n);
                        continue;
                }
                if (!ok) {
                    broken = true;
                    return null;
                }
                consume(4 + n);
                return m;
            }
        }
    }
}
