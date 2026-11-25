import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

public class StarfighterMIDlet extends MIDlet implements CommandListener {
    private StarCanvas canvas;
    public void startApp() {
        canvas = new StarCanvas();
        canvas.addCommand(new Command("Выход", Command.EXIT, 1));
        canvas.setCommandListener(this);
        Display.getDisplay(this).setCurrent(canvas);
    }
    public void pauseApp() { canvas.pause(); }
    public void destroyApp(boolean u) { canvas.stop(); }
    public void commandAction(Command c, Displayable d) {
        if (c.getCommandType() == Command.EXIT) { destroyApp(true); notifyDestroyed(); }
    }
}

class StarCanvas extends Canvas implements Runnable {
    private static final int ENEMIES = 12, SHOTS = 6;
    private final float[] ex = new float[ENEMIES], ey = new float[ENEMIES];
    private final boolean[] alive = new boolean[ENEMIES];
    private final float[] bx = new float[SHOTS], by = new float[SHOTS];
    private final boolean[] shot = new boolean[SHOTS];
    private float sx, phase;
    private int score, lives, wave;
    private boolean left, right, fire, over, running = true;
    private Thread t;

    StarCanvas() { reset(); t = new Thread(this); t.start(); }

    private void reset() {
        sx = getWidth() / 2f; score = 0; lives = 3; wave = 1; over = false; phase = 0;
        for (int i = 0; i < ENEMIES; i++) { alive[i] = true; ey[i] = 30 + (i / 4) * 26; }
        for (int i = 0; i < SHOTS; i++) shot[i] = false;
    }
    void pause() { running = false; }
    void stop() { running = false; }

    public void run() {
        while (running) {
            if (!over) step();
            repaint();
            try { Thread.sleep(22); } catch (InterruptedException e) { break; }
        }
    }

    private void step() {
        int w = getWidth(), h = getHeight();
        phase += 0.045f;
        if (left) sx -= 4.5f; if (right) sx += 4.5f;
        if (sx < 10) sx = 10; if (sx > w - 10) sx = w - 10;

        if (fire) {
            for (int i = 0; i < SHOTS; i++) if (!shot[i]) {
                shot[i] = true; bx[i] = sx; by[i] = h - 30; break;
            }
            fire = false;
        }
        for (int i = 0; i < SHOTS; i++) if (shot[i]) {
            by[i] -= 7f;
            if (by[i] < 0) shot[i] = false;
        }

        boolean anyAlive = false;
        for (int i = 0; i < ENEMIES; i++) {
            if (!alive[i]) continue;
            anyAlive = true;
            ex[i] = w / 2f + (float) Math.sin(phase + i * 0.55f) * (w / 2f - 26);
            ey[i] += 0.16f + wave * 0.07f;
            if (ey[i] > h - 34) { lives--; alive[i] = false; if (lives <= 0) over = true; }
            for (int j = 0; j < SHOTS; j++) if (shot[j] && Math.abs(bx[j] - ex[i]) < 14 && Math.abs(by[j] - ey[i]) < 12) {
                alive[i] = false; shot[j] = false; score += 25; break;
            }
        }
        if (!anyAlive && !over) {
            wave++;
            for (int i = 0; i < ENEMIES; i++) { alive[i] = true; ey[i] = 30 + (i / 4) * 26; }
        }
    }

    public void keyPressed(int k) {
        int a = getGameAction(k);
        if (a == Canvas.LEFT) left = true;
        else if (a == Canvas.RIGHT) right = true;
        else if (a == Canvas.FIRE) { if (over) reset(); else fire = true; }
    }
    public void keyReleased(int k) {
        int a = getGameAction(k);
        if (a == Canvas.LEFT) left = false;
        if (a == Canvas.RIGHT) right = false;
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x050818); g.fillRect(0, 0, w, h);
        for (int i = 0; i < 24; i++) {
            int y = (i * 37 + (int) (phase * 12)) % h;
            g.setColor(i % 3 == 0 ? 0x445577 : 0x223355);
            g.fillRect((i * 71) % w, y, 1, 1);
        }
        for (int i = 0; i < ENEMIES; i++) if (alive[i]) {
            g.setColor(i % 3 == 0 ? 0xff5577 : (i % 3 == 1 ? 0xffaa44 : 0xaa66ff));
            g.fillRect((int) ex[i] - 8, (int) ey[i] - 5, 16, 10);
            g.setColor(0xffffff); g.fillRect((int) ex[i] - 2, (int) ey[i] - 2, 4, 4);
        }
        for (int i = 0; i < SHOTS; i++) if (shot[i]) {
            g.setColor(0x66ffcc); g.fillRect((int) bx[i] - 1, (int) by[i], 2, 8);
        }
        g.setColor(0x44ddff);
        g.fillRect((int) sx - 10, h - 26, 20, 8);
        g.fillRect((int) sx - 3, h - 32, 6, 8);
        g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL));
        g.setColor(0x66ffcc);
        g.drawString("STARFIGHTER " + score + " W" + wave + " x" + lives, 4, 4, Graphics.LEFT | Graphics.TOP);
        if (over) g.drawString("GAME OVER - FIRE", w / 2, h / 2, Graphics.HCENTER | Graphics.VCENTER);
    }
}
