package dev.atrium.clipsync;

import dev.atrium.clipsync.Protocol.Clip;
import dev.atrium.clipsync.Protocol.Message;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// This phone's newest clips for the exchange on connecting, current first,
// with when each was copied (Android keeps no clipboard history of its own
// that we could read). Saved as CLIP frames, so a restart keeps the times.
final class RecentClips {
    // Kept beyond the exchanged few: a clip too big for the PC doesn't cost
    // it one of its places.
    private static final int KEPT = 2 * Protocol.HISTORY;

    private final File file;
    private final List<Clip> clips = new ArrayList<>();

    RecentClips(File file) {
        this.file = file;
        try {
            byte[] b = Files.readAllBytes(file.toPath());
            Protocol.Reader r = new Protocol.Reader(Protocol.MAX_CLIP);
            r.feed(b, 0, b.length);
            Message m;
            while ((m = r.next()) != null)
                if (m.type == Protocol.CLIP && clips.size() < KEPT)
                    clips.add(m.clip);
        } catch (IOException e) {
            // First run.
        }
    }

    List<Clip> recent() {
        return new ArrayList<>(clips);
    }

    Clip first() {
        return clips.isEmpty() ? null : clips.get(0);
    }

    // Became the clipboard (copied here, or taken from the PC).
    void current(Clip c) {
        long h = c.hash();
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).hash() != h)
                continue;
            if (i == 0 && c.time <= clips.get(0).time)
                return;  // already the clipboard's
            if (c.time == 0)
                c = c.at(clips.get(i).time);
            clips.remove(i);
            break;
        }
        clips.add(0, c);
        trim();
        save();
    }

    // From the PC's history: goes in by its time, behind the current clip.
    void older(Clip c) {
        long h = c.hash();
        for (Clip x : clips)
            if (x.hash() == h)
                return;
        int at = clips.isEmpty() ? 0 : 1;
        while (at < clips.size() && clips.get(at).time >= c.time)
            at++;
        clips.add(at, c);
        trim();
        save();
    }

    private void trim() {
        while (clips.size() > KEPT)
            clips.remove(clips.size() - 1);
    }

    private void save() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Clip c : clips) {
            byte[] f = Protocol.clip(c, Protocol.FLAG_HISTORY);
            out.write(f, 0, f.length);
        }
        File tmp = new File(file.getPath() + ".new");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            out.writeTo(f);
            f.getFD().sync();
        } catch (IOException e) {
            Log.i("couldn't save history: " + e);
            return;
        }
        // What was copied: for this user only.
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(false, false);
        tmp.setWritable(true, true);
        if (!tmp.renameTo(file))
            Log.i("couldn't replace " + file);
    }
}
