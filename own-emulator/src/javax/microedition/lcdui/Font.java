package javax.microedition.lcdui;

import java.awt.FontMetrics;
import java.awt.image.BufferedImage;

/** MIDP Font backed by a java.awt.Font. */
public class Font {
    public static final int FACE_SYSTEM = 0;
    public static final int FACE_MONOSPACE = 1;
    public static final int FACE_PROPORTIONAL = 2;

    public static final int STYLE_PLAIN = 0;
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_UNDERLINED = 4;

    public static final int SIZE_SMALL = 0;
    public static final int SIZE_MEDIUM = 1;
    public static final int SIZE_LARGE = 2;

    private static final BufferedImage METRIC_IMAGE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    private static final java.awt.Graphics2D METRIC_G = METRIC_IMAGE.createGraphics();

    private final java.awt.Font awt;
    private final int face, style, size;

    private Font(java.awt.Font awt, int face, int style, int size) {
        this.awt = awt;
        this.face = face;
        this.style = style;
        this.size = size;
    }

    public static Font getFont(int face, int style, int size) {
        String family = java.awt.Font.MONOSPACED;
        if (face == FACE_SYSTEM) family = java.awt.Font.SANS_SERIF;
        else if (face == FACE_PROPORTIONAL) family = java.awt.Font.SERIF;

        int awtStyle = java.awt.Font.PLAIN;
        if ((style & STYLE_BOLD) != 0) awtStyle |= java.awt.Font.BOLD;
        if ((style & STYLE_ITALIC) != 0) awtStyle |= java.awt.Font.ITALIC;

        int px = 12;
        if (size == SIZE_SMALL) px = 10;
        else if (size == SIZE_LARGE) px = 18;

        return new Font(new java.awt.Font(family, awtStyle, px), face, style, size);
    }

    public static Font getDefaultFont() {
        return getFont(FACE_SYSTEM, STYLE_PLAIN, SIZE_MEDIUM);
    }

    java.awt.Font awtFont() { return awt; }

    public int getHeight() { return metrics().getHeight(); }
    public int stringWidth(String s) { return s == null ? 0 : metrics().stringWidth(s); }
    public int charWidth(char c) { return metrics().charWidth(c); }
    public int substringWidth(String s, int offset, int len) {
        if (s == null) return 0;
        return metrics().stringWidth(s.substring(offset, Math.min(s.length(), offset + len)));
    }
    public int getBaselinePosition() { return metrics().getAscent(); }
    public int getFace() { return face; }
    public int getStyle() { return style; }
    public int getSize() { return size; }

    FontMetrics metrics() { return METRIC_G.getFontMetrics(awt); }
}
