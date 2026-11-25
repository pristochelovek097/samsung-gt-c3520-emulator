package dev.ownj2me;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Panel;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

/** AWT host window: renders the MIDP canvas, routes keys and soft commands. */
public final class HostFrame extends java.awt.Frame {
    private final int screenW, screenH, scale;
    private Displayable current;
    private final View view;
    private final Button leftSoft = new Button("");
    private final Button rightSoft = new Button("");

    public HostFrame(final Display display, final MIDlet midlet, String title,
                     int screenW, int screenH, int scale) {
        super(title == null ? "OwnJ2ME" : title);
        this.screenW = screenW;
        this.screenH = screenH;
        this.scale = scale;

        setLayout(new BorderLayout());
        setBackground(new Color(12, 18, 30));

        view = new View();
        view.setBackground(Color.BLACK);
        view.setPreferredSize(new Dimension(screenW * scale, screenH * scale));
        add(view, BorderLayout.CENTER);

        Panel bar = new Panel(new BorderLayout());
        leftSoft.setFocusable(false);
        rightSoft.setFocusable(false);
        leftSoft.addActionListener(e -> {
            if (current != null) {
                if (Emulator.softLeft(current)) return;
                Command c = current.commandAt(true);
                if (c != null) current.fireCommand(c);
            }
        });
        rightSoft.addActionListener(e -> {
            if (current != null) {
                Command c = current.commandAt(false);
                if (c != null) current.fireCommand(c);
            }
        });
        bar.add(leftSoft, BorderLayout.WEST);
        bar.add(rightSoft, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);

        view.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int key = DeviceProfile.awtToJ2me(e);
                if (key == javax.microedition.lcdui.Canvas.KEY_SOFTLEFT && current != null) {
                    if (Emulator.softLeft(current)) return;
                    javax.microedition.lcdui.Command c = current.commandAt(true);
                    if (c != null) { current.fireCommand(c); return; }
                }
                if (key == javax.microedition.lcdui.Canvas.KEY_SOFTRIGHT && current != null) {
                    javax.microedition.lcdui.Command c = current.commandAt(false);
                    if (c != null) { current.fireCommand(c); return; }
                }
                if (key != 0 && current instanceof javax.microedition.lcdui.Canvas) {
                    ((javax.microedition.lcdui.Canvas) current).keyPressed(key);
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                int key = DeviceProfile.awtToJ2me(e);
                if (key != 0 && current instanceof javax.microedition.lcdui.Canvas) {
                    ((javax.microedition.lcdui.Canvas) current).keyReleased(key);
                }
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                Emulator.exit();
            }
        });

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        view.requestFocus();
    }

    public int screenW() { return screenW; }
    public int screenH() { return screenH; }
    public void setTitleText(String t) { setTitle(t == null ? "OwnJ2ME" : t); }

    public void show(Displayable d) {
        current = d;
        Command left = d == null ? null : d.commandAt(true);
        Command right = d == null ? null : d.commandAt(false);
        leftSoft.setLabel(left == null ? "SOFT1" : left.getLabel());
        rightSoft.setLabel(right == null ? "SOFT2" : right.getLabel());
        view.repaint();
    }

    public void repaintScreen() { view.repaint(); }

    public void disposeHost() { dispose(); }

    private final class View extends Canvas {
        @Override public void paint(Graphics g) {
            BufferedImage img = null;
            if (current instanceof javax.microedition.lcdui.Canvas) {
                img = ((javax.microedition.lcdui.Canvas) current).screenImage();
            }
            if (img == null) {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
            } else {
                g.drawImage(img, 0, 0, getWidth(), getHeight(), this);
            }
        }
    }
}
