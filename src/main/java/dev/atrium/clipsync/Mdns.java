package dev.atrium.clipsync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

// Announces the link service over multicast DNS by itself. NsdManager would
// do it, but a shell-uid app_process gets SIGKILLed asking for it. Sends
// unsolicited responses (PTR, SRV, TXT, A) every ANNOUNCE_MS and answers
// queries that name the service, from port 5353 as RFC 6762 wants (avahi
// drops responses from any other port).
final class Mdns {
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;
    private static final int TTL = 120;
    private static final long ANNOUNCE_MS = 30_000;

    private final String type;      // "_atrium-link._tcp.local"
    private final String instance;  // "<name>.<type>"
    private final String host;      // "atrium-<id>.local"
    private final String txt;
    private final int port;
    private volatile boolean running = true;
    private MulticastSocket socket;
    private long lastSent;

    Mdns(String name, String serviceType, int port, byte[] id) {
        this.type = serviceType.replaceAll("\\.$", "") + ".local";
        this.instance = label(name) + "." + type;
        this.host = "atrium-" + Crypto.hex(id).substring(0, 8) + ".local";
        this.txt = "id=" + Crypto.hex(id);
        this.port = port;
    }

    void start() {
        Thread t = new Thread(this::run, "mdns");
        t.setDaemon(true);
        t.start();
    }

    // A DNS label is at most 63 bytes, and dots would split it.
    private static String label(String s) {
        s = s.replace('.', ' ');
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= 63)
            return s;
        return new String(b, 0, 63, StandardCharsets.UTF_8).replace("�", "");
    }

    private void run() {
        InetAddress group;
        try {
            group = InetAddress.getByName(GROUP);
        } catch (IOException e) {
            return;
        }
        Thread announcer = new Thread(() -> {
            while (running) {
                send(group);
                try {
                    Thread.sleep(ANNOUNCE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mdns-announce");
        announcer.setDaemon(true);
        byte[] buf = new byte[9000];
        while (running) {
            NetworkInterface wifi = wifi();
            if (wifi == null || !open(group, wifi)) {
                sleep(5000);
                continue;
            }
            if (!announcer.isAlive())
                announcer.start();
            else
                send(group);  // a new network: say so now
            Log.i("lan: mdns on " + wifi.getName() + " as " + instance);
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (IOException e) {
                    break;  // the interface went away: find it again
                }
                if (asksForUs(buf, p.getLength()))
                    send(group);
            }
            socket.close();
            sleep(1000);
        }
    }

    private boolean open(InetAddress group, NetworkInterface wifi) {
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setNetworkInterface(wifi);
            s.setTimeToLive(255);
            s.joinGroup(new InetSocketAddress(group, PORT), wifi);
            socket = s;
            return true;
        } catch (IOException e) {
            Log.i("lan: mdns: " + e);
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static NetworkInterface wifi() {
        try {
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (i.isUp() && !i.isLoopback() && i.supportsMulticast() && i.getName().startsWith("wlan") && ipv4(i) != null)
                    return i;
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Inet4Address ipv4(NetworkInterface i) {
        for (InetAddress a : Collections.list(i.getInetAddresses()))
            if (a instanceof Inet4Address)
                return (Inet4Address) a;
        return null;
    }

    private synchronized void send(InetAddress group) {
        MulticastSocket s = socket;
        NetworkInterface wifi = wifi();
        Inet4Address ip = wifi == null ? null : ipv4(wifi);
        if (s == null || ip == null)
            return;
        long now = System.currentTimeMillis();
        if (now - lastSent < 1000)
            return;  // queries come in bursts
        lastSent = now;
        byte[] d = response(ip);
        try {
            s.send(new DatagramPacket(d, d.length, group, PORT));
        } catch (IOException e) {
            Log.i("lan: mdns send: " + e);
        }
    }

    // --- the messages ----------------------------------------------------------

    private static final int PTR = 12, TXT = 16, SRV = 33, A = 1, ANY = 255;
    private static final int IN = 1, FLUSH = 0x8000;

    byte[] response(Inet4Address ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        put16(o, 0);       // id
        put16(o, 0x8400);  // response, authoritative
        put16(o, 0);
        put16(o, 4);       // answers
        put16(o, 0);
        put16(o, 0);
        record(o, type, PTR, IN, name(instance));
        ByteArrayOutputStream srv = new ByteArrayOutputStream();
        put16(srv, 0);
        put16(srv, 0);
        put16(srv, port);
        bytes(srv, name(host));
        record(o, instance, SRV, IN | FLUSH, srv.toByteArray());
        byte[] t = txt.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tx = new ByteArrayOutputStream();
        tx.write(t.length);
        bytes(tx, t);
        record(o, instance, TXT, IN | FLUSH, tx.toByteArray());
        record(o, host, A, IN | FLUSH, ip.getAddress());
        return o.toByteArray();
    }

    private static void record(ByteArrayOutputStream o, String owner, int rtype, int rclass, byte[] data) {
        bytes(o, name(owner));
        put16(o, rtype);
        put16(o, rclass);
        put16(o, TTL >>> 16);
        put16(o, TTL & 0xffff);
        put16(o, data.length);
        bytes(o, data);
    }

    // Labels split on dots; the instance's own label has none (see label()).
    private static byte[] name(String n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String l : n.split("\\.")) {
            byte[] b = l.getBytes(StandardCharsets.UTF_8);
            o.write(b.length);
            bytes(o, b);
        }
        o.write(0);
        return o.toByteArray();
    }

    private static void bytes(ByteArrayOutputStream o, byte[] b) {
        o.write(b, 0, b.length);
    }

    private static void put16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v);
    }

    // A query (not a response) with a question for the service type, the
    // instance or the host.
    boolean asksForUs(byte[] d, int len) {
        if (len < 12 || (d[2] & 0x80) != 0)
            return false;
        int questions = u16(d, 4);
        int at = 12;
        for (int q = 0; q < questions; q++) {
            StringBuilder sb = new StringBuilder();
            at = readName(d, len, at, sb, 0);
            if (at < 0 || at + 4 > len)
                return false;
            int qtype = u16(d, at);
            at += 4;
            String n = sb.toString();
            if ((qtype == PTR || qtype == ANY) && n.equalsIgnoreCase(type))
                return true;
            if (n.equalsIgnoreCase(instance) || n.equalsIgnoreCase(host))
                return true;
        }
        return false;
    }

    private static int u16(byte[] d, int at) {
        return (d[at] & 0xff) << 8 | (d[at + 1] & 0xff);
    }

    // Returns where the name ends in the message, or -1 when it's broken.
    private static int readName(byte[] d, int len, int at, StringBuilder sb, int depth) {
        while (at < len) {
            int l = d[at] & 0xff;
            if (l == 0)
                return at + 1;
            if ((l & 0xc0) == 0xc0) {
                if (at + 1 >= len || depth > 8)
                    return -1;
                int to = (l & 0x3f) << 8 | (d[at + 1] & 0xff);
                return readName(d, len, to, sb, depth + 1) < 0 ? -1 : at + 2;
            }
            if (at + 1 + l > len)
                return -1;
            if (sb.length() > 0)
                sb.append('.');
            sb.append(new String(d, at + 1, l, StandardCharsets.UTF_8));
            at += 1 + l;
        }
        return -1;
    }
}
