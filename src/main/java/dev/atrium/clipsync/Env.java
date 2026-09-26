package dev.atrium.clipsync;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// A Context for a process started by app_process, not by an app: it speaks
// as com.android.shell (whose uid we run as), the one package allowed to read
// the clipboard from the background (READ_CLIPBOARD_IN_BACKGROUND). The same
// steps scrcpy's server takes.
final class Env {
    static final String PACKAGE = "com.android.shell";

    private Env() {}

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE).build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Object getSystemService(String name) {
            Object s = super.getSystemService(name);
            // The clipboard service asks its context who is calling.
            if (s != null && Context.CLIPBOARD_SERVICE.equals(name)) {
                try {
                    Field f = s.getClass().getDeclaredField("mContext");
                    f.setAccessible(true);
                    f.set(s, this);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            return s;
        }
    }

    static Context context() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = atClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object at = ctor.newInstance();
        set(atClass, null, "sCurrentActivityThread", at);
        Field system = atClass.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(at, true);

        try {
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Constructor<?> k = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            k.setAccessible(true);
            set(atClass, at, "mConfigurationController", k.newInstance(at));
        } catch (Throwable t) {
            Log.i("no configuration controller: " + t);
        }
        try {
            Class<?> bindData = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> k = bindData.getDeclaredConstructor();
            k.setAccessible(true);
            Object data = k.newInstance();
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = PACKAGE;
            set(bindData, data, "appInfo", info);
            set(atClass, at, "mBoundApplication", data);
        } catch (Throwable t) {
            Log.i("no app info: " + t);
        }
        // Bluetooth (a mainline module) finds its service manager through this.
        Method mainline = atClass.getDeclaredMethod("initializeMainlineModules");
        mainline.setAccessible(true);
        mainline.invoke(null);

        Method systemContext = atClass.getDeclaredMethod("getSystemContext");
        Context ctx = new ShellContext((Context) systemContext.invoke(at));
        try {
            Application app = Instrumentation.newApplication(Application.class, ctx);
            set(atClass, at, "mInitialApplication", app);
        } catch (Throwable t) {
            Log.i("no application: " + t);
        }
        return ctx;
    }

    private static void set(Class<?> c, Object o, String field, Object value) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }
}
