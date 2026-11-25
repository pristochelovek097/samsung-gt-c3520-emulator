package arm;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

/** Нативное окно эмулятора: экран 240x320 (2x, без сглаживания) + клавиатура раскладушки. */
public final class EmulatorWindow {
    private final JFrame frame;
    private final Canvas screen;
    private final JLabel status;
    private final LcdController lcd;
    private volatile BufferedImage current;
    private final boolean[] keys = new boolean[64];

    public EmulatorWindow(LcdController lcd) {
        this.lcd = lcd;
        this.current = lcd.image();
        frame = new JFrame("Samsung C3520 — ARM926EJ-S Emulator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        screen = new Canvas() {
            @Override public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                BufferedImage img = current;
                if (img != null) g2.drawImage(img, 0, 0, 480, 640, null);
            }
            @Override public Dimension getPreferredSize() { return new Dimension(480, 640); }
        };
        frame.add(screen, BorderLayout.CENTER);

        JPanel keypad = new JPanel(new GridLayout(4, 5, 4, 4));
        String[] labels = {"1","2","3","4","5","6","7","8","9","*","0","#",
                           "Call","End","SOFT-L","SOFT-R","UP","DOWN","LEFT","RIGHT"};
        for (String s : labels) {
            JButton b = new JButton(s);
            b.addActionListener(e -> { /*_matrix scan placeholder*/ });
            keypad.add(b);
        }
        frame.add(keypad, BorderLayout.SOUTH);

        status = new JLabel("PC=-------- CPSR=--------  instr=0  region=--  fps=0");
        frame.add(status, BorderLayout.NORTH);

        frame.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { key(e, true); }
            @Override public void keyReleased(KeyEvent e) { key(e, false); }
            private void key(KeyEvent e, boolean down) {
                int code = e.getKeyCode();
                if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) keys[code - KeyEvent.VK_0] = down;
                else if (code == KeyEvent.VK_UP) keys[40] = down;
                else if (code == KeyEvent.VK_DOWN) keys[41] = down;
                else if (code == KeyEvent.VK_LEFT) keys[42] = down;
                else if (code == KeyEvent.VK_RIGHT) keys[43] = down;
            }
        });
        screen.setFocusable(true);
        frame.pack();
        frame.setVisible(true);
    }

    public void update(ARM cpu, long fps) {
        current = lcd.image();
        int pc = cpu.r[15];
        String region = (pc & 0xFF000000) == 0xC0000000
                ? (pc >= 0xC0020000 ? "MAIN" : "ROOT")
                : String.format("%08x", pc);
        status.setText(String.format("PC=%08x CPSR=%08x instr=%d region=%s fps=%d",
                pc, cpu.cpsr, cpu.instructions, region, fps));
        screen.repaint();
    }

    public boolean key(int index) { return index >= 0 && index < keys.length && keys[index]; }
}
