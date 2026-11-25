import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

public class BricksMIDlet extends MIDlet implements CommandListener {
    private BricksCanvas canvas;
    public void startApp() {
        canvas = new BricksCanvas();
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

class BricksCanvas extends Canvas implements Runnable {
    private static final int COLS = 8, ROWS = 5;
    private final boolean[][] bricks = new boolean[COLS][ROWS];
    private float px, bx, by, vx = 1.7f, vy = -2.1f;
    private int pw = 46, lives = 3, score, state; // 0 ready, 1 play, 2 over
    private boolean left, right, running = true;
    private Thread t;

    BricksCanvas() { reset(); t = new Thread(this); t.start(); }

    private void reset() {
        px = getWidth() / 2f - pw / 2f;
        bx = getWidth() / 2f; by = getHeight() - 40;
        vx = 1.7f; vy = -2.1f; lives = 3; score = 0; state = 0;
        for (int x = 0; x < COLS; x++) for (int y = 0; y < ROWS; y++) bricks[x][y] = true;
    }
    void pause() { running = false; }
    void stop() { running = false; }

    public void run() {
        while (running) {
            if (state == 1) step();
            repaint();
            try { Thread.sleep(16); } catch (InterruptedException e) { break; }
        }
    }

    private void step() {
        int w = getWidth(), h = getHeight();
        if (left) px -= 4.2f; if (right) px += 4.2f;
        if (px < 2) px = 2; if (px > w - pw - 2) px = w - pw - 2;

        bx += vx; by += vy;
        if (bx < 3 || bx > w - 3) vx = -vx;
        if (by < 3) vy = -vy;
        if (by > h - 26 && by < h - 14 && bx >= px && bx <= px + pw && vy > 0) {
            vy = -vy;
            vx += ((bx - (px + pw / 2f)) / (pw / 2f)) * 0.9f;
            if (vx > 3.2f) vx = 3.2f; if (vx < -3.2f) vx = -3.2f;
        }
        if (by > h + 8) {
            lives--;
            if (lives <= 0) state = 2;
            else { bx = w / 2f; by = h - 40; vx = 1.7f; vy = -2.1f; state = 0; }
        }

        int bw = (w - 16) / COLS, bh = 13;
        int col = (int) ((bx - 8) / bw), row = (int) ((by - 30) / bh);
        if (col >= 0 && col < COLS && row >= 0 && row < ROWS && bricks[col][row]) {
            bricks[col][row] = false;
            vy = -vy;
            score += (ROWS - row) * 10;
            boolean clear = true;
            for (int x = 0; x < COLS; x++) for (int y = 0; y < ROWS; y++) if (bricks[x][y]) clear = false;
            if (clear) state = 2;
        }
    }

    public void keyPressed(int k) {
        int a = getGameAction(k);
        if (a == Canvas.LEFT) left = true;
        else if (a == Canvas.RIGHT) right = true;
        else if (a == Canvas.FIRE) { if (state == 0) state = 1; else if (state == 2) reset(); }
    }
    public void keyReleased(int k) {
        int a = getGameAction(k);
        if (a == Canvas.LEFT) left = false;
        if (a == Canvas.RIGHT) right = false;
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x080c1c); g.fillRect(0, 0, w, h);
        int bw = (w - 16) / COLS, bh = 13;
        int[] colors = {0xff4455, 0xff9933, 0xffdd44, 0x66dd55, 0x44aaff};
        for (int x = 0; x < COLS; x++) for (int y = 0; y < ROWS; y++) if (bricks[x][y]) {
            g.setColor(colors[y]);
            g.fillRect(8 + x * bw + 1, 30 + y * bh + 1, bw - 2, bh - 2);
        }
        g.setColor(0x44ffcc); g.fillRect((int) px, h - 20, pw, 6);
        g.setColor(0xffffff); g.fillRect((int) bx - 3, (int) by - 3, 6, 6);
        g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL));
        g.setColor(0x44ffcc);
        g.drawString("BRICKS " + score + "  x" + lives, 4, 4, Graphics.LEFT | Graphics.TOP);
        if (state == 0) g.drawString("FIRE - START", w / 2, h / 2, Graphics.HCENTER | Graphics.VCENTER);
        if (state == 2) g.drawString(score > 0 && lives > 0 ? "WIN! FIRE" : "GAME OVER - FIRE", w / 2, h / 2, Graphics.HCENTER | Graphics.VCENTER);
    }
}
