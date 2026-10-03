package dev.atrium.link.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The connection to the module's daemon (dev.atrium.link.Control on the
// daemon's side): its status comes as one JSON line per change, commands go
// as one line each. Reconnects while started; the listener hears null while
// the daemon can't be reached.
final class DaemonClient {
    static final String SOCKET = "atrium-link";
    private static final long RETRY_MS = 2000;

    interface Listener {
        // On the main thread.
        void status(JSONObject status);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean running;
    private volatile LocalSocket socket;
    private Thread thread;

    DaemonClient(Listener listener) {
        this.listener = listener;
    }

    void start() {
        if (running)
            return;
        running = true;
        thread = new Thread(this::run, "daemon");
        thread.start();
    }

    void stop() {
        running = false;
        closeSocket();
        if (thread != null)
            thread.interrupt();
    }

    void send(String command) {
        sender.execute(() -> {
            LocalSocket s = socket;
            if (s == null)
                return;
            try {
                OutputStream o = s.getOutputStream();
                o.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                closeSocket();
            }
        });
    }

    private void run() {
        while (running) {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                socket = s;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    try {
                        JSONObject status = new JSONObject(line);
                        main.post(() -> listener.status(status));
                    } catch (JSONException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
            closeSocket();
            if (!running)
                return;
            main.post(() -> listener.status(null));
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void closeSocket() {
        LocalSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
