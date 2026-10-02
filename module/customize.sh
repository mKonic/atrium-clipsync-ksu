# KernelSU module installer.
ui_print "- The phone pairs with your atrium PC as usual; turn on"
ui_print "  Settings > Bluetooth > Phone clipboard there."
ui_print "- For audio over Wi-Fi, open this module's WebUI and tap Pair."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
