package dev.atrium.clipsync;

import android.os.FileObserver;
import android.os.Handler;

import dev.atrium.clipsync.Protocol.Clip;

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
    private static final String[] FOLDERS = {"/sdcard/Pictures/Screenshots", "/sdcard/DCIM/Screenshots"};

    private final Handler main;
    private final Consumer<Clip> taken;
    private final List<FileObserver> observers = new ArrayList<>();

    Screenshots(Handler main, Consumer<Clip> taken) {
        this.main = main;
        this.taken = taken;
    }

    void start() {
        for (String path : FOLDERS) {
            File dir = new File(path);
            if (!dir.isDirectory())
                continue;
            // Saved through MediaStore: written as a hidden pending file,
            // then renamed (MOVED_TO), or written in place (CLOSE_WRITE).
            FileObserver o = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String name) {
                    if (name != null && !name.startsWith(".") && mime(name) != null)
                        read(new File(dir, name));
                }
            };
            o.startWatching();
            observers.add(o);
            Log.i("watching " + path);
        }
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
