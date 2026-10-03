# KernelSU module installer.
OLD=/data/adb/modules/atrium-clipsync

# This module was atrium-clipsync: retire that one. Its state moves over at
# the next start (service.sh), pairings included.
if [ -d "$OLD" ]; then
    ui_print "- Replacing atrium-clipsync"
    touch "$OLD/remove"
fi

# The app is how the phone is paired and managed.
if pm install -r "$MODPATH/atrium-link.apk" >/dev/null 2>&1; then
    ui_print "- Installed the Atrium Link app"
else
    ui_print "! Couldn't install the Atrium Link app: an older one signed"
    ui_print "  with another key may be installed. Uninstall it and"
    ui_print "  install the module again."
fi
rm -f "$MODPATH/atrium-link.apk"

ui_print "- Open Atrium Link and press Pair, then pair the phone in"
ui_print "  Atrium's Settings > Phone. The clipboard pairs over"
ui_print "  Bluetooth as before: Settings > Bluetooth > Phone Clipboard."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
