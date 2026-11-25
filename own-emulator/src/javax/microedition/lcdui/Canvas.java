package javax.microedition.lcdui;

import java.awt.image.BufferedImage;

/** MIDP Canvas. Drawing happens into an off-screen buffer, then is blitted by the host. */
public abstract class Canvas extends Displayable {
    public static final int UP = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 5;
    public static final int DOWN = 6;
    public static final int FIRE = 8;
    public static final int GAME_A = 9;
    public static final int GAME_B = 10;
    public static final int GAME_C = 11;
    public static final int GAME_D = 12;

    public static final int KEY_UP = -1;
    public static final int KEY_DOWN = -2;
    public static final int KEY_LEFT = -3;
    public static final int KEY_RIGHT = -4;
    public static final int KEY_FIRE = -5;
    public static final int KEY_SOFTLEFT = -6;
    public static final int KEY_SOFTRIGHT = -7;

    private BufferedImage buffer;
    private BufferedImage screen;
    private int bufferedW = -1, bufferedH = -1;

    protected abstract void paint(Graphics g);

    /** Immediately renders the canvas into its buffer and refreshes the host window. */
    public final void repaint() {
        synchronized (this) {
            ensureBuffer();
            Graphics g = new Graphics(buffer);
            try { paint(g); } finally { g.dispose(); }
            screen = copy(buffer);
        }
        if (host != null) host.repaintScreen();
    }

    public final void serviceRepaints() { repaint(); }

    private void ensureBuffer() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        if (buffer == null || w != bufferedW || h != bufferedH) {
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            screen = copy(buffer);
            bufferedW = w; bufferedH = h;
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    public BufferedImage screenImage() {
        synchronized (this) { return screen; }
    }

    public void keyPressed(int keyCode) { }
    public void keyReleased(int keyCode) { }
    public void keyRepeated(int keyCode) { keyPressed(keyCode); }

    public int getGameAction(int keyCode) { return dev.ownj2me.DeviceProfile.gameAction(keyCode); }

    public String getKeyName(int keyCode) {
        switch (keyCode) {
            case KEY_UP: return "Up";
            case KEY_DOWN: return "Down";
            case KEY_LEFT: return "Left";
            case KEY_RIGHT: return "Right";
            case KEY_FIRE: return "Fire";
            case KEY_SOFTLEFT: return "Soft-left";
            case KEY_SOFTRIGHT: return "Soft-right";
            default:
                if (keyCode >= 48 && keyCode <= 57) return String.valueOf((char) keyCode);
                if (keyCode == 42) return "*";
                if (keyCode == 35) return "#";
                return String.valueOf(keyCode);
        }
    }

    public void setFullScreenMode(boolean mode) { }
    public boolean isFullScreenMode() { return false; }
    public boolean hasPointerEvents() { return false; }
    public boolean hasPointerMotionEvents() { return false; }
    public boolean hasRepeatEvents() { return true; }
    public boolean isDoubleBuffered() { return true; }
}
