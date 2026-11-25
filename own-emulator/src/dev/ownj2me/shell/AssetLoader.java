package dev.ownj2me.shell;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Loads optional PNG assets for the firmware shell from assets/s40/.
 * Drop wallpaper.png and icon PNGs there (extracted from YOUR OWN device)
 * and the shell uses them instead of its built-in procedural graphics.
 */
public final class AssetLoader {
    private static boolean loaded;
    private static BufferedImage wallpaper;
    private static final Map<String, BufferedImage> ICONS = new HashMap<>();

    private AssetLoader() { }

    public static File directory() {
        File games = dev.ownj2me.Emulator.gamesDir();
        File root = games == null ? new File(".") : games.getAbsoluteFile().getParentFile();
        return new File(root, "assets" + File.separator + "s40");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        File dir = directory();
        wallpaper = read(new File(dir, "wallpaper.png"));
        for (String n : new String[]{"snake", "bricks", "ship", "gear", "info", "disk", "box"}) {
            BufferedImage img = read(new File(dir, n + ".png"));
            if (img != null) ICONS.put(n, img);
        }
    }

    private static BufferedImage read(File f) {
        try { return f.isFile() ? ImageIO.read(f) : null; }
        catch (Exception e) { return null; }
    }

    public static BufferedImage wallpaper() { load(); return wallpaper; }
    public static BufferedImage icon(String kind) { load(); return ICONS.get(kind); }
    public static int count() { load(); return ICONS.size() + (wallpaper != null ? 1 : 0); }
}
