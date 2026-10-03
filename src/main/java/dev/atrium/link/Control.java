package dev.atrium.link;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

// The Atrium Link app's way in: the abstract socket that is also the
// one-daemon lock. One line each way: the daemon sends its status as JSON on
// connecting and on every change, the app sends commands (LanLink.command).
// Only the app, root and shell may connect.
final class Control {
    static final String APP = "dev.atrium.link";

    interface Commands {
        // On the main thread.
        void command(String line);
    }

    private final Context ctx;
    private final LocalServerSocket server;
    private final Handler main;
    private final Commands commands;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile String status = "{}";

    Control(Context ctx, LocalServerSocket server, Handler main, Commands commands) {
        this.ctx = ctx;
        this.server = server;
        this.main = main;
        this.commands = commands;
    }

    void start() {
        Thread t = new Thread(this::serve, "control");
        t.setDaemon(true);
        t.start();
    }

    // The status for every app connected now and later.
    void status(String json) {
        status = json;
        for (Client c : clients)
            c.out.add(json);
    }

    private void serve() {
        for (;;) {
            LocalSocket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                Log.i("control: accept: " + e);
                return;
            }
            try {
                Credentials who = s.getPeerCredentials();
                if (!allowed(who.getUid())) {
                    Log.i("control: refused uid " + who.getUid());
                    s.close();
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            Client c = new Client(s);
            clients.add(c);
            c.out.add(status);
            c.start();
        }
    }

    private boolean allowed(int uid) {
        if (uid == 0 || uid == 2000)
            return true;
        try {
            return uid == ctx.getPackageManager().getPackageUid(APP, 0);
        } catch (Exception e) {
            return false;
        }
    }

    private final class Client {
        final LocalSocket socket;
        final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();

        Client(LocalSocket socket) {
            this.socket = socket;
        }

        void start() {
            Thread r = new Thread(this::reading, "control-read");
            Thread w = new Thread(this::writing, "control-write");
            r.setDaemon(true);
            w.setDaemon(true);
            r.start();
            w.start();
        }

        void reading() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String l = line.trim();
                    if (!l.isEmpty())
                        main.post(() -> commands.command(l));
                }
            } catch (IOException ignored) {
            }
            close();
        }

        void writing() {
            try {
                OutputStream o = socket.getOutputStream();
                for (;;) {
                    String s = out.take();
                    if (s.isEmpty())
                        break;  // closed
                    o.write((s.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            }
            close();
        }

        void close() {
            if (!clients.remove(this))
                return;
            out.add("");
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
