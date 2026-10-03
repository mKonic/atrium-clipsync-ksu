package dev.atrium.link;

import android.os.FileObserver;
import android.os.Handler;

import dev.atrium.link.Protocol.Clip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// New screenshots, as if copied. Keyboards like Gboard offer the newest
// screenshot in their clipboard strip without it ever being in the
// clipboard, so the PC never saw it; this hands each one over as it is
// saved. The phone's own clipboard is left alone.
final class Screenshots {
    // Screenshots land in a folder named for them under one of these: the
    // system's (Pictures/Screenshots) and others' (a game mode's "Game
    // Space Screenshot"), some made only with their first screenshot.
    private static final String[] ROOTS = {"/sdcard/Pictures", "/sdcard/DCIM"};

    private final Handler main;
    private final Consumer<Clip> taken;
    // Kept: an observer stops when it is collected.
    private final List<FileObserver> observers = new ArrayList<>();
    private final java.util.Set<String> watched = new java.util.HashSet<>();

    Screenshots(Handler main, Consumer<Clip> taken) {
        this.main = main;
        this.taken = taken;
    }

    void start() {
        for (String path : ROOTS) {
            File root = new File(path);
            if (!root.isDirectory())
                continue;
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null)
                for (File d : dirs)
                    consider(d);
            // A screenshot folder made later.
            FileObserver o = new FileObserver(root, FileObserver.CREATE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String name) {
                    File d = name != null ? new File(root, name) : null;
                    if (d != null && d.isDirectory())
                        consider(d);
                }
            };
            o.startWatching();
            observers.add(o);
        }
    }

    private synchronized void consider(File dir) {
        if (!dir.getName().toLowerCase(java.util.Locale.ROOT).contains("screenshot") || !watched.add(dir.getPath()))
            return;
        // Saved through MediaStore: written as a hidden pending file, then
        // renamed (MOVED_TO), or written in place (CLOSE_WRITE).
        FileObserver o = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String name) {
                if (name != null && !name.startsWith(".") && mime(name) != null)
                    read(new File(dir, name));
            }
        };
        o.startWatching();
        observers.add(o);
        Log.i("watching " + dir);
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png"))
            return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
            return "image/jpeg";
        if (n.endsWith(".webp"))
            return "image/webp";
        return null;  // screen recordings and the like
    }

    // On the observer's thread, so the main one never waits on storage.
    private void read(File f) {
        try {
            if (f.length() == 0 || f.length() > Protocol.MAX_CLIP) {
                Log.i("screenshot " + f.getName() + " is " + f.length() + " bytes: skipped");
                return;
            }
            Clip c = new Clip(mime(f.getName()), Files.readAllBytes(f.toPath()), System.currentTimeMillis());
            Log.i("screenshot " + f.getName());
            main.post(() -> taken.accept(c));
        } catch (IOException | RuntimeException e) {
            Log.i("can't read screenshot " + f.getName() + ": " + e);
        }
    }
}
