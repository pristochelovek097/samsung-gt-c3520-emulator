package dev.ownj2me;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

/**
 * OwnJ2ME — a J2ME (CLDC/MIDP) emulator written from scratch.
 *
 * The Java VM executes MIDlet bytecode; this project implements the Java ME
 * runtime itself: MIDlet lifecycle, Display/Canvas/Graphics/Font API, device
 * profiles, key mapping, JAR/JAD loading, the AWT host and the phone shell UI.
 */
public final class Emulator {
    private static final Map<MIDlet, Properties> PROPS = new IdentityHashMap<>();
    private static int screenW = 240, screenH = 320, scale = 2;
    private static String title = "OwnJ2ME Phone";
    private static File gamesDir = new File("games");

    private static HostFrame host;
    private static PhoneShellImpl shell;
    private static MIDlet currentGame;

    /** Internal wrapper so the shell has its own MIDlet identity. */
    public static final class PhoneShellImpl extends dev.ownj2me.shell.PhoneShell {
        public void startAppFromHost() { startApp(); }
    }

    private Emulator() { }

    public static void main(String[] args) {
        String firmware = null;
        List<String> clean = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--firmware".equals(args[i]) && i + 1 < args.length) firmware = args[++i];
            else clean.add(args[i]);
        }
        args = clean.toArray(new String[0]);
        if (firmware != null) {
            dev.ownj2me.shell.FirmwareLoader.init(new File(firmware));
            System.out.println("Firmware resources: " + dev.ownj2me.shell.FirmwareLoader.describe());
        }

        if (args.length < 1) {
            System.out.println("Usage: Emulator <game.jar> [width height scale profile gamesDir]");
            System.out.println("       Emulator --shell [width height scale profile gamesDir startJar]");
            System.out.println("       [--firmware <dir>] — загрузить ресурсы и иконки из прошивки");
            System.exit(1);
        }

        String first = args[0];
        boolean shellMode = "--shell".equals(first);
        int idx = shellMode ? 1 : 0;

        screenW = parseInt(arg(args, idx), 240);
        screenH = parseInt(arg(args, idx + 1), 320);
        scale = parseInt(arg(args, idx + 2), 2);
        String profile = arg(args, idx + 3) == null ? "Standard" : arg(args, idx + 3);
        String dir = arg(args, idx + 4);
        if (dir != null) gamesDir = new File(dir);
        String startName = arg(args, idx + 5);
        DeviceProfile.configure(profile, screenW, screenH);

        try {
            if (shellMode) {
                startShell();
                if (startName != null) {
                    File[] jars = gamesDir.listFiles((d, n) -> n.equalsIgnoreCase(startName)
                            || n.equalsIgnoreCase(startName + ".jar"));
                    if (jars != null && jars.length > 0) launchGame(jars[0]);
                }
            } else {
                File jar = new File(first);
                if (!jar.isFile()) { System.err.println("JAR not found: " + jar); System.exit(1); }
                launchGame(jar);
            }
        } catch (Throwable t) {
            System.err.println("OwnJ2ME failed:");
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static String arg(String[] a, int i) { return i < a.length ? a[i] : null; }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static void startShell() throws Exception {
        shell = new PhoneShellImpl();
        shell.startAppFromHost();
        System.out.println("OwnJ2ME phone shell: " + screenW + "x" + screenH + " [" + DeviceProfile.name + "]");
    }

    public static void launchGame(File jar) throws Exception {
        Properties props = MidletLoader.loadProperties(jar);
        String name = MidletLoader.midletName(props);
        MIDlet midlet = MidletLoader.instantiate(jar, props);
        PROPS.put(midlet, props);
        currentGame = midlet;
        title = name;
        Method start = midlet.getClass().getMethod("startApp");
        start.setAccessible(true);
        start.invoke(midlet);
        if (host != null) host.setTitleText(name);
        System.out.println("OwnJ2ME launched: " + name);
    }

    public static void showShell() {
        if (shell == null) return;
        currentGame = null;
        title = "OwnJ2ME Phone";
        if (host != null) host.setTitleText(title);
        shell.startAppFromHost();
    }

    public static boolean isShell(Displayable d) {
        return shell != null && d == shell.canvas();
    }

    /** Left softkey doubles as "back to firmware UI" outside the shell. */
    public static boolean softLeft(Displayable d) {
        if (shell != null && d != shell.canvas()) { showShell(); return true; }
        return false;
    }

    public static HostFrame getHost() {
        if (host == null) host = new HostFrame(null, null, title, screenW, screenH, scale);
        return host;
    }

    public static String getProperty(MIDlet m, String key) {
        Properties p = PROPS.get(m);
        return p == null ? null : p.getProperty(key);
    }

    public static File gamesDir() { return gamesDir; }

    public static void midletDestroyed(MIDlet m) {
        try { Display.getDisplay(m).dispose(); } catch (Throwable ignored) { }
        if (m == currentGame) showShell();
    }

    public static void midletPaused(MIDlet m) { }

    public static void exit() { System.exit(0); }
}
