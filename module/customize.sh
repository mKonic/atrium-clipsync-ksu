# KernelSU module installer.
ui_print "- The phone pairs with your atrium PC as usual; turn on"
ui_print "  Settings > Bluetooth > Phone clipboard there."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
