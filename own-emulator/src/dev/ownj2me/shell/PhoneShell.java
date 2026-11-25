package dev.ownj2me.shell;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

/**
 * The "firmware UI" of the phone: status bar, application grid and softkeys.
 * It is an internal MIDlet drawn by the emulator core itself — not a web page.
 */
public class PhoneShell extends MIDlet {
    private static PhoneShell instance;
    private ShellCanvas canvas;

    public PhoneShell() { instance = this; }
    public static PhoneShell instance() { return instance; }
    public ShellCanvas canvas() { return canvas; }

    protected void startApp() {
        canvas = new ShellCanvas();
        Display.getDisplay(this).setCurrent(canvas);
    }

    protected void pauseApp() { }
    protected void destroyApp(boolean u) { notifyDestroyed(); }

    public static final class ShellCanvas extends Canvas {
        private static final int GRID = 0, TEXT = 1;

        /* Оффсеты реальных PNG-иконок внутри образа C3520XF_Open_Europe_Common_FS_2.image */
        private static final java.util.Map<String, Long> FW_ICON = new java.util.HashMap<>();
        static {
            FW_ICON.put("snake", 0x004b7747L);   // музыка
            FW_ICON.put("bricks", 0x004b7447L);  // камера
            FW_ICON.put("ship", 0x004b7e64L);    // индикатор
            FW_ICON.put("gear", 0x004b6ad7L);    // настройки
            FW_ICON.put("info", 0x004b6c9cL);    // контакты
            FW_ICON.put("disk", 0x004b7136L);    // питание
            FW_ICON.put("box", 0x004b7c45L);     // приложения
        }

        private final List<App> apps = new ArrayList<>();
        private int selected;
        private int page = GRID;
        private String textTitle = "";
        private String[] textLines = new String[0];

        ShellCanvas() {
            AssetLoader.load();
            loadApps();
        }

        private void loadApps() {
            File dir = dev.ownj2me.Emulator.gamesDir();
            File[] jars = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars != null) {
                java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File jar : jars) {
                    String name = jar.getName().replaceAll("\\.jar$", "");
                    try {
                        Properties p = dev.ownj2me.MidletLoader.loadProperties(jar);
                        name = dev.ownj2me.MidletLoader.midletName(p);
                    } catch (Exception ignored) { }
                    apps.add(new App(name, iconFor(jar.getName()), jar));
                }
            }
            apps.add(new App("Настройки", "gear", null));
            apps.add(new App("О телефоне", "info", null));
            apps.add(new App("Прошивки", "disk", null));
            apps.add(new App("Прошивка", "disk", null));
            apps.add(new App("Ассеты", "box", null));
        }

        private static String iconFor(String jarName) {
            String n = jarName.toLowerCase();
            if (n.contains("snake")) return "snake";
            if (n.contains("brick")) return "bricks";
            if (n.contains("star") || n.contains("fight")) return "ship";
            return "box";
        }

        private static final class App {
            final String name, icon;
            final File jar;
            App(String name, String icon, File jar) { this.name = name; this.icon = icon; this.jar = jar; }
        }

        public void keyPressed(int key) {
            int a = getGameAction(key);
            // Стрелки и цифры 2/4/6/8 работают независимо от профиля.
            boolean up = key == Canvas.KEY_UP || key == 50 || a == Canvas.UP;
            boolean down = key == Canvas.KEY_DOWN || key == 56 || a == Canvas.DOWN;
            boolean left = key == Canvas.KEY_LEFT || key == 52 || a == Canvas.LEFT;
            boolean right = key == Canvas.KEY_RIGHT || key == 54 || a == Canvas.RIGHT;
            boolean fire = key == Canvas.KEY_FIRE || key == 53 || a == Canvas.FIRE;

            if (page == TEXT) {
                if (fire || key == Canvas.KEY_SOFTLEFT || key == Canvas.KEY_SOFTRIGHT) {
                    page = GRID; repaint();
                }
                return;
            }
            int cols = 3;
            if (left) { selected = (selected - 1 + apps.size()) % apps.size(); repaint(); }
            else if (right) { selected = (selected + 1) % apps.size(); repaint(); }
            else if (up) { selected = (selected - cols + apps.size()) % apps.size(); repaint(); }
            else if (down) { selected = (selected + cols) % apps.size(); repaint(); }
            else if (fire || key == Canvas.KEY_SOFTRIGHT) { activate(); }
            else if (key >= 49 && key <= 57) {
                int i = key - 49;
                if (i < apps.size()) { selected = i; activate(); }
            }
        }

        private void activate() {
            if (selected < 0 || selected >= apps.size()) return;
            App app = apps.get(selected);
            if (app.jar != null) {
                try {
                    dev.ownj2me.Emulator.launchGame(app.jar);
                } catch (Exception e) {
                    showText("ОШИБКА", new String[]{"Не удалось запустить:", app.name, String.valueOf(e.getMessage())});
                }
            } else if ("Настройки".equals(app.name)) {
                showText("НАСТРОЙКИ", new String[]{
                    "Ядро: OwnJ2ME 1.0", "CLDC 1.0 / MIDP 1.0-2.0",
                    "Экран: " + getWidth() + "x" + getHeight(),
                    "Профиль: " + dev.ownj2me.DeviceProfile.name,
                    "Звук: вкл", "Контраст: 50%"
                });
            } else if ("О телефоне".equals(app.name)) {
                showText("О ТЕЛЕФОНЕ", new String[]{
                    "OwnJ2ME Phone", "Версия 2.0 (demo)",
                    "Прошивка: своя, с нуля", "Память: 64 МБ",
                    "Сеть: J2ME.NET", "Игр: " + (apps.size() - 3)
                });
            } else if ("Прошивки".equals(app.name)) {
                showText("ПРОШИВКИ", new String[]{
                    "Экран собран из", "ресурсов прошивки",
                    "C3520 XELJ1.", "Иконки вычитаны",
                    "напрямую из FS_2.image.", "Код не используется."
                });
            } else if ("Прошивка".equals(app.name)) {
                String info = FirmwareLoader.describe();
                showText("ПРОШИВКА", new String[]{
                    "C3520XELJ1", info,
                    "Иконки: из FS_2.image",
                    "Режим: firmware-driven", "эмуляция UI"
                });
            } else {
                String path = AssetLoader.directory().getAbsolutePath();
                showText("АССЕТЫ", new String[]{
                    "Найдено PNG: " + AssetLoader.count(),
                    "Папка:", path.length() > 22 ? path.substring(0, 21) : path,
                    "wallpaper.png — фон", "snake/bricks/ship...", "— иконки"
                });
            }
        }

        private void showText(String title, String[] lines) {
            textTitle = title; textLines = lines; page = TEXT; repaint();
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (AssetLoader.wallpaper() != null) g.drawImage(AssetLoader.wallpaper(), 0, 0, w, h);
            Font small = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            Font bold = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_SMALL);

            // --- статус-бар ---
            g.setColor(0x102a4d); g.fillRect(0, 0, w, 18);
            g.setColor(0x8fd6ff); g.setFont(small);
            g.drawString("J2ME.NET", 4, 9, Graphics.LEFT | Graphics.VCENTER);
            for (int i = 0; i < 4; i++) {
                g.setColor(i < 3 ? 0x7dff8a : 0x35506b);
                g.fillRect(w - 76 + i * 5, 12 - i * 3, 3, 3 + i * 3);
            }
            g.setColor(0x7dff8a); g.fillRect(w - 48, 5, 18, 9);
            g.setColor(0x1c4a2a); g.fillRect(w - 30, 7, 4, 5);
            g.setColor(0xd8e8ff);
            String time = new SimpleDateFormat("HH:mm").format(new Date());
            g.drawString(time, w - 4, 9, Graphics.RIGHT | Graphics.VCENTER);

            if (page == TEXT) { paintText(g, w, h, bold, small); return; }

            // --- заголовок ---
            g.setColor(0x1d5c96); g.fillRect(0, 18, w, 22);
            g.setColor(0xffffff); g.setFont(bold);
            g.drawString("МЕНЮ", 6, 29, Graphics.LEFT | Graphics.VCENTER);
            g.setColor(0x8fd6ff);
            g.drawString((selected + 1) + "/" + apps.size(), w - 6, 29, Graphics.RIGHT | Graphics.VCENTER);

            // --- сетка приложений ---
            int cols = 3, top = 46;
            int cw = (w - 16) / cols, ch = 66;
            for (int i = 0; i < apps.size(); i++) {
                int row = i / cols, col = i % cols;
                int x = 8 + col * cw + 3, y = top + row * (ch + 4) + 2;
                boolean on = i == selected;
                g.setColor(on ? 0x2f7ac2 : 0x163254);
                g.fillRoundRect(x, y, cw - 6, ch, 8, 8);
                g.setColor(on ? 0x8fd6ff : 0x2d5c8a);
                g.drawRoundRect(x, y, cw - 6, ch, 8, 8);
                drawIcon(g, apps.get(i).icon, x + (cw - 6) / 2, y + 26);
                g.setColor(on ? 0xffffff : 0x9fc4e8);
                g.setFont(small);
                String name = apps.get(i).name;
                if (name.length() > 11) name = name.substring(0, 10) + ".";
                g.drawString(name, x + (cw - 6) / 2, y + ch - 12, Graphics.HCENTER | Graphics.VCENTER);
            }

            // --- софт-клавиши ---
            g.setColor(0x102a4d); g.fillRect(0, h - 24, w, 24);
            g.setColor(0x7dff8a); g.setFont(bold);
            g.drawString("НАЗАД", 6, h - 12, Graphics.LEFT | Graphics.VCENTER);
            g.drawString("ОТКРЫТЬ", w - 6, h - 12, Graphics.RIGHT | Graphics.VCENTER);
        }

        private void paintText(Graphics g, int w, int h, Font bold, Font small) {
            g.setColor(0x0b1c33); g.fillRect(0, 18, w, h - 18);
            g.setColor(0x1d5c96); g.fillRect(0, 18, w, 22);
            g.setColor(0xffffff); g.setFont(bold);
            g.drawString(textTitle, 6, 29, Graphics.LEFT | Graphics.VCENTER);
            g.setColor(0xcfe4ff); g.setFont(small);
            for (int i = 0; i < textLines.length; i++) {
                g.drawString(textLines[i], 10, 54 + i * 16, Graphics.LEFT | Graphics.VCENTER);
            }
            g.setColor(0x102a4d); g.fillRect(0, h - 24, w, 24);
            g.setColor(0x7dff8a);
            g.drawString("НАЗАД", 6, h - 12, Graphics.LEFT | Graphics.VCENTER);
        }

        private void drawIcon(Graphics g, String kind, int cx, int cy) {
            Long offset = FW_ICON.get(kind);
            java.awt.image.BufferedImage fw = offset == null ? null : FirmwareLoader.at(offset);
            if (fw != null) { g.drawImage(fw, cx - 13, cy - 13, 26, 26); return; }
            java.awt.image.BufferedImage asset = AssetLoader.icon(kind);
            if (asset != null) { g.drawImage(asset, cx - 13, cy - 13, 26, 26); return; }
            if ("snake".equals(kind)) {
                g.setColor(0x39b84b);
                g.fillRect(cx - 12, cy - 2, 10, 8);
                g.fillRect(cx - 2, cy - 2, 10, 8);
                g.setColor(0xa8ff70); g.fillRect(cx + 8, cy - 2, 6, 8);
                g.setColor(0xff5555); g.fillRect(cx - 2, cy - 12, 6, 6);
            } else if ("bricks".equals(kind)) {
                int[] c = {0xff4455, 0xffaa33, 0x66dd55, 0x44aaff};
                for (int r = 0; r < 2; r++) for (int i = 0; i < 3; i++) {
                    g.setColor(c[(r + i) % 4]);
                    g.fillRect(cx - 15 + i * 11, cy - 10 + r * 10, 9, 8);
                }
            } else if ("ship".equals(kind)) {
                g.setColor(0x44ddff);
                g.fillRect(cx - 9, cy - 2, 18, 8);
                g.fillRect(cx - 3, cy - 9, 6, 9);
                g.setColor(0xffffff); g.fillRect(cx - 2, cy + 6, 4, 7);
            } else if ("gear".equals(kind)) {
                g.setColor(0xa9c2dd);
                g.fillArc(cx - 11, cy - 11, 22, 22, 0, 360);
                g.setColor(0x163254); g.fillArc(cx - 5, cy - 5, 10, 10, 0, 360);
                g.setColor(0xa9c2dd);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.fillRect(cx + (int) (Math.cos(a) * 14) - 2, cy + (int) (Math.sin(a) * 14) - 2, 4, 4);
                }
            } else if ("info".equals(kind)) {
                g.setColor(0x66b8ff); g.fillArc(cx - 11, cy - 11, 22, 22, 0, 360);
                g.setColor(0xffffff); g.drawString("i", cx, cy, Graphics.HCENTER | Graphics.VCENTER);
            } else if ("disk".equals(kind)) {
                g.setColor(0x697f99); g.fillRect(cx - 12, cy - 11, 24, 22);
                g.setColor(0xcfe4ff); g.fillRect(cx - 6, cy - 11, 11, 9);
                g.setColor(0x2c4c70); g.fillRect(cx - 8, cy + 1, 16, 10);
            } else {
                g.setColor(0x6b8fb8); g.fillRect(cx - 11, cy - 11, 22, 22);
                g.setColor(0xcfe4ff); g.fillRect(cx - 5, cy - 5, 10, 10);
            }
        }
    }
}
