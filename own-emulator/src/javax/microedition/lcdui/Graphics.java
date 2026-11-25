package javax.microedition.lcdui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** MIDP Graphics drawn into a BufferedImage. */
public class Graphics {
    public static final int HCENTER = 1;
    public static final int VCENTER = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int TOP = 16;
    public static final int BOTTOM = 32;
    public static final int BASELINE = 64;

    public static final int SOLID = 0;
    public static final int DOTTED = 1;

    private final BufferedImage image;
    private final java.awt.Graphics2D g;
    private int color;
    private Font font = Font.getDefaultFont();
    private int translateX, translateY;

    public Graphics(BufferedImage image) {
        this.image = image;
        this.g = image.createGraphics();
        this.g.setColor(Color.BLACK);
        this.g.setFont(font.awtFont());
    }

    public void setColor(int rgb) {
        color = rgb & 0x00FFFFFF;
        g.setColor(new Color(color));
    }

    public void setColor(int r, int gr, int b) {
        setColor((r << 16) | (gr << 8) | b);
    }

    public int getColor() { return color; }
    public int getRedComponent() { return (color >> 16) & 0xFF; }
    public int getGreenComponent() { return (color >> 8) & 0xFF; }
    public int getBlueComponent() { return color & 0xFF; }
    public int getGrayScale() { return (getRedComponent() + getGreenComponent() + getBlueComponent()) / 3; }

    public void setFont(Font f) {
        if (f == null) throw new NullPointerException();
        font = f;
        g.setFont(f.awtFont());
    }

    public Font getFont() { return font; }

    public void fillRect(int x, int y, int w, int h) { g.fillRect(x + translateX, y + translateY, w, h); }
    public void drawRect(int x, int y, int w, int h) { g.drawRect(x + translateX, y + translateY, w, h); }
    public void drawLine(int x1, int y1, int x2, int y2) {
        g.drawLine(x1 + translateX, y1 + translateY, x2 + translateX, y2 + translateY);
    }
    public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) {
        g.fillRoundRect(x + translateX, y + translateY, w, h, aw, ah);
    }
    public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) {
        g.drawRoundRect(x + translateX, y + translateY, w, h, aw, ah);
    }
    public void fillArc(int x, int y, int w, int h, int start, int arc) {
        g.fillArc(x + translateX, y + translateY, w, h, start, arc);
    }
    public void drawArc(int x, int y, int w, int h, int start, int arc) {
        g.drawArc(x + translateX, y + translateY, w, h, start, arc);
    }
    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3) {
        int[] xs = {x1 + translateX, x2 + translateX, x3 + translateX};
        int[] ys = {y1 + translateY, y2 + translateY, y3 + translateY};
        g.fillPolygon(xs, ys, 3);
    }

    public void drawString(String s, int x, int y, int anchor) {
        if (s == null) throw new NullPointerException();
        FontMetrics fm = font.metrics();
        int w = fm.stringWidth(s);
        int tx = x + translateX, ty = y + translateY;

        if ((anchor & HCENTER) != 0) tx -= w / 2;
        else if ((anchor & RIGHT) != 0) tx -= w;
        else if ((anchor & LEFT) == 0) tx -= w / 2; // default: centered

        if ((anchor & BOTTOM) != 0) ty -= fm.getDescent();
        else if ((anchor & BASELINE) != 0) { /* ty is baseline */ }
        else if ((anchor & VCENTER) != 0) ty += fm.getAscent() - fm.getHeight() / 2;
        else if ((anchor & TOP) != 0) ty += fm.getAscent();
        else ty += fm.getAscent();

        g.drawString(s, tx, ty);
        if ((font.getStyle() & Font.STYLE_UNDERLINED) != 0) {
            g.drawLine(tx, ty + 1, tx + w, ty + 1);
        }
    }

    public void drawSubstring(String s, int offset, int len, int x, int y, int anchor) {
        drawString(s.substring(offset, Math.min(s.length(), offset + len)), x, y, anchor);
    }
    public void drawChar(char c, int x, int y, int anchor) { drawString(String.valueOf(c), x, y, anchor); }
    public void drawChars(char[] ch, int offset, int len, int x, int y, int anchor) {
        drawString(new String(ch, offset, len), x, y, anchor);
    }

    public void setStrokeStyle(int s) { }
    public int getStrokeStyle() { return SOLID; }

    public void setClip(int x, int y, int w, int h) {
        g.setClip(x + translateX, y + translateY, w, h);
    }
    public Rectangle getClip() { return g.getClipBounds(); }
    public int getClipX() { return g.getClipBounds().x; }
    public int getClipY() { return g.getClipBounds().y; }
    public int getClipWidth() { return g.getClipBounds().width; }
    public int getClipHeight() { return g.getClipBounds().height; }
    public void clipRect(int x, int y, int w, int h) { g.clipRect(x + translateX, y + translateY, w, h); }

    public void translate(int x, int y) { translateX += x; translateY += y; }
    public int getTranslateX() { return translateX; }
    public int getTranslateY() { return translateY; }

    public int getMaxClipX() { return image.getWidth(); }
    public int getMaxClipY() { return image.getHeight(); }

    /** Extension used by the built-in firmware shell to draw host-owned bitmaps. */
    public void drawImage(java.awt.Image img, int x, int y, int w, int h) {
        if (img != null) g.drawImage(img, x + translateX, y + translateY, w, h, null);
    }

    void dispose() { g.dispose(); }
}
