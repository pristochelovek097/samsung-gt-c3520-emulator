package dev.ownj2me;

import java.awt.event.KeyEvent;
import javax.microedition.lcdui.Canvas;

/** Device profile: screen defaults, key mapping and game actions. */
public final class DeviceProfile {
    public static String name = "Standard";
    public static int defaultWidth = 240;
    public static int defaultHeight = 320;

    private DeviceProfile() { }

    public static void configure(String profile, int width, int height) {
        if (profile != null) name = profile;
        defaultWidth = width;
        defaultHeight = height;
    }

    public static int gameAction(int key) {
        // Стрелки работают в любом профиле; цифры 2/4/6/8 — дополнительно.
        switch (key) {
            case Canvas.KEY_UP: return Canvas.UP;
            case Canvas.KEY_DOWN: return Canvas.DOWN;
            case Canvas.KEY_LEFT: return Canvas.LEFT;
            case Canvas.KEY_RIGHT: return Canvas.RIGHT;
            case Canvas.KEY_FIRE: return Canvas.FIRE;
            case 50: return Canvas.UP;     // '2'
            case 56: return Canvas.DOWN;   // '8'
            case 52: return Canvas.LEFT;   // '4'
            case 54: return Canvas.RIGHT;  // '6'
            case 53: return Canvas.FIRE;   // '5'
            case 49: return Canvas.GAME_A;
            case 51: return Canvas.GAME_B;
            case 55: return Canvas.GAME_C;
            case 57: return Canvas.GAME_D;
            default: return 0;
        }
    }

    /** Translate an AWT key event into a J2ME phone key code. */
    public static int awtToJ2me(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP: return Canvas.KEY_UP;
            case KeyEvent.VK_DOWN: return Canvas.KEY_DOWN;
            case KeyEvent.VK_LEFT: return Canvas.KEY_LEFT;
            case KeyEvent.VK_RIGHT: return Canvas.KEY_RIGHT;
            case KeyEvent.VK_ENTER:
            case KeyEvent.VK_ACCEPT:
            case KeyEvent.VK_SPACE: return Canvas.KEY_FIRE;
            case KeyEvent.VK_Q: return Canvas.KEY_SOFTLEFT;
            case KeyEvent.VK_W: return Canvas.KEY_SOFTRIGHT;
            case KeyEvent.VK_ESCAPE: return Canvas.KEY_SOFTLEFT;
            case KeyEvent.VK_ASTERISK:
            case KeyEvent.VK_MULTIPLY: return 42;
            case KeyEvent.VK_NUMBER_SIGN: return 35;
            default:
                int c = e.getKeyCode();
                if (c >= KeyEvent.VK_0 && c <= KeyEvent.VK_9) return 48 + (c - KeyEvent.VK_0);
                return 0;
        }
    }
}
