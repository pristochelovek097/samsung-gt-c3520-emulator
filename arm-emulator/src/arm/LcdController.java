package arm;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;

/**
 * Контроллер LCD PNX4910: 240x320 RGB565.
 *
 * Адреса портов взяты из заводского модуля LCDACT
 * (C3520XELaFleur.bootlcdact): 0x74002000 / 0x74002100 (EBI) и
 * 0x7401900E (пара адрес/данные на внешней шине: младший бит выбирает
 * команду или поток 16-битных пикселей RGB565). Старое окно 0x7402xxxx
 * тоже поддерживается для совместимости.
 */
public final class LcdController {
    public static final int WIDTH = 240, HEIGHT = 320;
    public static final int EBI_BASE = 0x74020000;   // legacy
    public static final int EBI_CMD  = 0x74002000;   // LCDACT
    public static final int EBI_DATA = 0x74002100;   // LCDACT
    public static final int EBI_ALT  = 0x7401900E;   // LCDACT: cmd/data по A1

    private final short[] frame = new short[WIDTH * HEIGHT];
    private int cursor;                 // текущий указатель в буфере
    private int command;
    private long commands, pixels;
    private long frames;

    public void reset() {
        cursor = 0;
        command = 0;
        commands = 0;
        pixels = 0;
        frames = 0;
        java.util.Arrays.fill(frame, (short) 0);
    }

    /** Запись в регистр/порт EBI LCD. */
    public void write(int addr, int value) {
        int a = addr & 0xFFFFF;
        if (a == EBI_CMD) {                      // команда ILI93xx/S6Dxxxx
            command = value & 0xFFFF;
            commands++;
            if (command == 0x2C || command == 0x38) cursor = 0; // RAMWR/WRITE RAM
            return;
        }
        if (a == EBI_DATA) {                     // поток RGB565
            push(value & 0xFFFF);
            return;
        }
        if ((a & ~1) == (EBI_ALT & ~1)) {        // 0x7401900C / 0x7401900E
            if ((a & 1) != 0) push(value & 0xFFFF);
            else { command = value & 0xFFFF; commands++; if (command == 0x2C || command == 0x38) cursor = 0; }
            return;
        }
        int off = addr - EBI_BASE;
        if (off == 0x28) {
            if ((command & 0x100) != 0) push(value & 0xFFFF);
            else { command = value; commands++; }
        } else if (off == 0x2c) {
            command = value; commands++;
        } else if (off == 0x30) {
            cursor = value;
        }
    }

    public void push(int rgb565) {
        if (cursor >= 0 && cursor < frame.length) {
            frame[cursor++] = (short) rgb565;
            pixels++;
        }
    }

    public void setPixel(int x, int y, int rgb565) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) frame[y * WIDTH + x] = (short) rgb565;
    }

    public int readFramebuffer(int addr) {
        int off = addr & 0xFFFF;
        int idx = off / 2;
        if (idx < 0 || idx >= frame.length) return 0;
        return frame[idx] & 0xFFFF;
    }

    public long frames() { return frames; }
    public long commands() { return commands; }
    public long pixels() { return pixels; }

    public BufferedImage image() {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_USHORT_565_RGB);
        short[] dst = ((DataBufferUShort) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(frame, 0, dst, 0, Math.min(dst.length, frame.length));
        return img;
    }

    /** Тестовая заставка: вертикальный градиент + рамка. */
    public void testPattern() {
        for (int y = 0; y < HEIGHT; y++) {
            int r = (y * 31 / HEIGHT) & 0x1F;
            int g = (y * 63 / HEIGHT) & 0x3F;
            int b = 0x1F - ((y * 31 / HEIGHT) & 0x1F);
            for (int x = 0; x < WIDTH; x++) {
                boolean border = x < 4 || x >= WIDTH - 4 || y < 4 || y >= HEIGHT - 4;
                setPixel(x, y, border ? 0xFFFF : ((r << 11) | (g << 5) | b));
            }
        }
    }
}
