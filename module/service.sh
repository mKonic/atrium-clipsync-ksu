#!/system/bin/sh
# Runs the sync daemon as the shell user (the one allowed to read the
# clipboard in the background), restarting it if it dies.
MODDIR=${0%/*}
# The shell user's own directory: it can't reach /data/adb.
HOME_DIR=/data/user_de/0/com.android.shell/atrium-clipsync

(
    until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
    while [ ! -f "$MODDIR/remove" ]; do
        if [ -f "$MODDIR/disable" ]; then
            sleep 10
            continue
        fi
        mkdir -p "$HOME_DIR"
        cp "$MODDIR/clipsync.dex" "$HOME_DIR/clipsync.dex"
        chown -R 2000:2000 "$HOME_DIR"
        chmod 700 "$HOME_DIR"
        chmod 600 "$HOME_DIR/clipsync.dex"
        if [ -f "$HOME_DIR/log" ] && [ "$(stat -c %s "$HOME_DIR/log")" -gt 1048576 ]; then
            mv "$HOME_DIR/log" "$HOME_DIR/log.old"
        fi
        touch "$HOME_DIR/log"
        chmod 600 "$HOME_DIR/log"
        su 2000 -c "CLASSPATH=$HOME_DIR/clipsync.dex exec app_process /system/bin dev.atrium.clipsync.Main $HOME_DIR" \
            >> "$HOME_DIR/log" 2>&1
        sleep 5
    done
) &
