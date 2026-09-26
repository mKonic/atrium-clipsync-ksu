package dev.atrium.clipsync;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// To stdout, which service.sh sends to the log file next to the history
// (logcat is often silenced on phones like this one).
final class Log {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);

    private Log() {}

    static synchronized void i(String s) {
        System.out.println(TIME.format(new Date()) + " " + s);
    }
}
