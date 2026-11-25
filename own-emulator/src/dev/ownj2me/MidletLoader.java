package dev.ownj2me;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.microedition.midlet.MIDlet;

/** Reads JAR/JAD descriptors and instantiates MIDlets from their own class loader. */
public final class MidletLoader {
    private MidletLoader() { }

    public static Properties loadProperties(File jar) throws IOException {
        Properties p = new Properties();
        try (JarFile jf = new JarFile(jar)) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                mf.getMainAttributes().forEach((k, v) -> p.setProperty(k.toString(), v.toString()));
            }
        }
        File jad = new File(jar.getParentFile(), jar.getName().replaceAll("\\.jar$", ".jad"));
        if (jad.isFile()) {
            Properties fromJad = new Properties();
            try (FileInputStream in = new FileInputStream(jad)) { fromJad.load(in); }
            for (String k : fromJad.stringPropertyNames()) p.setProperty(k, fromJad.getProperty(k));
        }
        return p;
    }

    public static String midletClass(Properties p) {
        String entry = p.getProperty("MIDlet-1");
        if (entry == null) {
            for (String k : p.stringPropertyNames()) if (k.startsWith("MIDlet-")) { entry = p.getProperty(k); break; }
        }
        if (entry == null) throw new IllegalArgumentException("No MIDlet-* entry in descriptor");
        String[] parts = entry.split(",");
        for (String part : parts) {
            String s = part.trim();
            if (s.contains(".") && !s.contains("/") && !s.contains(" ")) return s;
        }
        return parts.length > 2 ? parts[2].trim() : entry.trim();
    }

    public static String midletName(Properties p) {
        String name = p.getProperty("MIDlet-Name");
        if (name != null) return name;
        String entry = p.getProperty("MIDlet-1");
        return entry == null ? "MIDlet" : entry.split(",")[0].trim();
    }

    public static MIDlet instantiate(File jar, Properties props) throws Exception {
        String className = midletClass(props);
        URL url = jar.getCanonicalFile().toURI().toURL();
        URLClassLoader loader = new URLClassLoader(new URL[]{url}, Emulator.class.getClassLoader());
        Class<?> cls = Class.forName(className, true, loader);
        return (MIDlet) cls.getDeclaredConstructor().newInstance();
    }
}
