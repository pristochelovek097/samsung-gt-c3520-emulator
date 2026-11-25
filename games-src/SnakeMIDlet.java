import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

public class SnakeMIDlet extends MIDlet implements CommandListener {
    private SnakeCanvas canvas;

    public void startApp() {
        canvas = new SnakeCanvas();
        canvas.addCommand(new Command("Выход", Command.EXIT, 1));
        canvas.setCommandListener(this);
        Display.getDisplay(this).setCurrent(canvas);
    }

    public void pauseApp() { canvas.pause(); }
    public void destroyApp(boolean unconditional) { canvas.stop(); }

    public void commandAction(Command c, Displayable d) {
        if (c.getCommandType() == Command.EXIT) {
            destroyApp(true);
            notifyDestroyed();
        }
    }
}

class SnakeCanvas extends Canvas implements Runnable {
    private static final int GW = 20, GH = 20;
    private final int[] sx = new int[GW * GH];
    private final int[] sy = new int[GW * GH];
    private int len, dir, score, best;
    private int fx, fy;
    private boolean running, over;
    private Thread thread;
    private long tick = 110L;

    SnakeCanvas() {
        reset();
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    private void reset() {
        len = 4;
        dir = 1;
        score = 0;
        over = false;
        for (int i = 0; i < len; i++) { sx[i] = 8 - i; sy[i] = 10; }
        placeFood();
    }

    private void placeFood() {
        for (int tries = 0; tries < 100; tries++) {
            fx = (int) (Math.random() * GW);
            fy = (int) (Math.random() * GH);
            boolean hit = false;
            for (int i = 0; i < len; i++) if (sx[i] == fx && sy[i] == fy) hit = true;
            if (!hit) return;
        }
    }

    void pause() { running = false; }
    void stop() { running = false; }

    public void run() {
        while (running) {
            if (!over) step();
            repaint();
            try { Thread.sleep(tick); } catch (InterruptedException e) { break; }
        }
    }

    private void step() {
        int nx = sx[0], ny = sy[0];
        if (dir == 1) nx++; else if (dir == 2) nx--;
        else if (dir == 3) ny++; else ny--;

        if (nx < 0 || ny < 0 || nx >= GW || ny >= GH) { over = true; return; }
        for (int i = 0; i < len; i++) if (sx[i] == nx && sy[i] == ny) { over = true; return; }

        for (int i = len; i > 0; i--) { sx[i] = sx[i - 1]; sy[i] = sy[i - 1]; }
        sx[0] = nx; sy[0] = ny;

        if (nx == fx && ny == fy) {
            len = Math.min(len + 1, sx.length - 1);
            score += 10;
            if (score > best) best = score;
            if (tick > 55L) tick -= 2L;
            placeFood();
        }
    }

    public void keyPressed(int k) {
        int a = getGameAction(k);
        if (a == Canvas.UP && dir != 3) dir = 4;
        else if (a == Canvas.DOWN && dir != 4) dir = 3;
        else if (a == Canvas.LEFT && dir != 1) dir = 2;
        else if (a == Canvas.RIGHT && dir != 2) dir = 1;
        else if (a == Canvas.FIRE && over) reset();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x08160b); g.fillRect(0, 0, w, h);

        int cell = Math.max(4, Math.min(w / GW, (h - 26) / GH));
        int ox = (w - cell * GW) / 2;
        int oy = (h - cell * GH) / 2 + 20;

        g.setColor(0x12351a);
        for (int x = 0; x <= GW; x++) g.drawLine(ox + x * cell, oy, ox + x * cell, oy + GH * cell);
        for (int y = 0; y <= GH; y++) g.drawLine(ox, oy + y * cell, ox + GW * cell, oy + y * cell);

        g.setColor(0xff4444);
        g.fillRect(ox + fx * cell + 1, oy + fy * cell + 1, cell - 2, cell - 2);

        for (int i = len - 1; i >= 0; i--) {
            g.setColor(i == 0 ? 0xa8ff70 : (i % 2 == 0 ? 0x4fd45f : 0x39b84b));
            g.fillRect(ox + sx[i] * cell + 1, oy + sy[i] * cell + 1, cell - 2, cell - 2);
        }

        g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL));
        g.setColor(0xa8ff70);
        g.drawString("SNAKE  |  " + score + "  |  BEST " + best, 4, 3, Graphics.LEFT | Graphics.TOP);
        if (over) {
            g.setColor(0xffcc33);
            g.drawString("GAME OVER - FIRE", w / 2, h / 2 - 8, Graphics.HCENTER | Graphics.VCENTER);
        }
    }
}
