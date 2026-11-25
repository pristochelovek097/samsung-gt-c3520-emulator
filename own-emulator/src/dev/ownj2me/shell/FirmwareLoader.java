package dev.ownj2me.shell;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Reads the Samsung C3520 firmware image(s) and carves PNG resources directly
 * out of the raw filesystem dump. The phone shell then uses those real icons
 * instead of hand-drawn placeholders.
 */
public final class FirmwareLoader {
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Map<Long, BufferedImage> BY_OFFSET = new HashMap<>();
    private static final List<long[]> INDEX = new ArrayList<>(); // {offset, width, height}
    private static boolean initialized;
    private static File directory;

    private FirmwareLoader() { }

    public static void init(File dir) {
        if (initialized) return;
        initialized = true;
        if (dir == null || !dir.isDirectory()) return;
        directory = dir;
        List<File> images = new ArrayList<>();
        collectImages(dir, images, 0);
        images.sort(Comparator.comparing(File::getName));
        for (File image : images) scan(image);
        INDEX.sort(Comparator.comparingLong(a -> a[0]));
    }

    private static void collectImages(File dir, List<File> out, int depth) {
        if (depth > 3 || dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectImages(f, out, depth + 1);
            else if (f.getName().toLowerCase().endsWith(".image")) out.add(f);
        }
    }

    private static void scan(File file) {
        try {
            byte[] b = Files.readAllBytes(file.toPath());
            int pos = 0;
            while (true) {
                int i = indexOf(b, PNG_SIG, pos);
                if (i < 0) break;
                pos = i + PNG_SIG.length;
                int end = indexOf(b, new byte[]{'I', 'E', 'N', 'D'}, pos);
                if (end < 0) continue;
                end += 8;
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(b, i, end - i));
                    if (img != null && img.getWidth() >= 8 && img.getHeight() >= 8) {
                        BY_OFFSET.put((long) i, img);
                        INDEX.add(new long[]{i, img.getWidth(), img.getHeight()});
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    public static boolean hasFirmware() { return !INDEX.isEmpty(); }
    public static int count() { return INDEX.size(); }
    public static File directory() { return directory; }

    public static BufferedImage at(long offset) { return BY_OFFSET.get(offset); }

    public static BufferedImage find(int width, int height, int ordinal) {
        int n = 0;
        for (long[] e : INDEX) {
            if (e[1] == width && e[2] == height) {
                if (n == ordinal) return BY_OFFSET.get(e[0]);
                n++;
            }
        }
        return null;
    }

    public static String describe() {
        if (!hasFirmware()) return "Прошивка не загружена";
        return count() + " PNG из " + (directory == null ? "?" : directory.getName());
    }
}
