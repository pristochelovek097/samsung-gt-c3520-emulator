package arm;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Виртуальный NAND-контроллер PNX4910 (окно 0x7D800000..0x7D81FFFF).
 *
 * Карта разделов берётся из factory .ptt (All Data in Hexadecimal):
 *   IMAGE   0x020000 / 0x020000  -> 0xC0020000
 *   LCDACT  0x040000 / 0x020000  -> 0xC0000000 (exec)
 *   ROOT    0x060000 / 0x020000  -> 0xC0000000 (exec)
 *   MAIN    0x080000 / 0xC00000  -> 0xC0020000
 *   DSP     0xC80000 / 0x060000  -> 0xC1920000
 *   PAGE    0xCE0000 / 0x600000  -> 0xA0000000 (load=0)
 *   DATA_RW 0x12E0000 / 0x040000 -> 0xC1AB5000
 *   FS a/b/c/d, sysv — дальше, здесь не нужны.
 *
 * Контроллер отдаёт:
 *   READ ID    (0x90) — ID 128 МБ SLC NAND Samsung (2 КБ страница, 128 КБ блок);
 *   READ STATUS(0x70) — 0xC0 (готово, запись не защищена);
 *   READ PAGE  (0x00 -> 0x30) — 2048 байт страницы из виртуального образа.
 * Регистры 0x14/0xC0/0x408 — фиксированные ответы, которые ждёт драйвер:
 *   0x7D800014 == 3            (тип/готовность — проверка 0xC01E5596)
 *   0x7D8000C0 == 0x16C9       (верификация 0xC01C24D4: +0xFFFFE937 == 0)
 *   0x7D800408 == 0xFFFFFFFF   (ожидание в низкоуровневом тримминге)
 */
public final class NandController {
    public static final int BASE = 0x7D800000;
    public static final int SIZE = 0x00020000;
    public static final int NAND_SIZE = 0x08000000; // 128 МБ

    private static final int PAGE = 2048;
    private final Map<Integer, byte[]> sparse = new HashMap<>();
    private final byte[] fifo = new byte[PAGE];

    // Состояние протокола команд.
    private int command = 0xFF;
    private int column = 0;
    private int row = 0;
    private int fifoPos = 0;
    // Статус меняется после команды включения (запись 1 в 0x10):
    // стартовый ID=3 (проверка 0xC01E5596), затем готовность=1 (опрос в ITCM).
    private int status14 = 3;

    public NandController(String dir) {
        // Пустая NAND ( стёртое состояние ) — 0xFF.
        add(0x000000, fill(0x20000, (byte) 0xFF)); // EFSPARTI / boot area
        add(0x020000, load(dir, "C3520XELaFleur.bmp", 0x20000));
        add(0x040000, load(dir, "C3520XELaFleur.bootlcdact", 0x20000));
        add(0x060000, load(dir, "C3520XELaFleur.cla/ER_FLASH_CB_ROOT", 0x20000));
        add(0x080000, load(dir, "C3520XELaFleur.cla/ER_FLASH_CB_MAIN", 0xC00000));
        add(0xC80000, load(dir, "C3520XELaFleur.cla/ER_FLASH_NCB_DSP", 0x60000));
        add(0xCE0000, load(dir, "C3520XELaFleur.cla/ER_FLASH_CB_PAGE1", 0x600000));
        add(0x12E0000, load(dir, "C3520XELaFleur.cla/ER_RAM_CB", 0x40000));
    }

    private static byte[] fill(int n, byte v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, v);
        return b;
    }

    private static byte[] load(String dir, String rel, int cap) {
        File f = new File(dir + "/C3520XFLJ1_BIN/" + rel);
        if (!f.exists()) return fill(cap, (byte) 0xFF);
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            int off = raw.length > 0x2000 ? 0x2000 : 0; // .cla header
            byte[] b = fill(cap, (byte) 0xFF);
            int n = Math.min(cap, raw.length - off);
            System.arraycopy(raw, off, b, 0, n);
            return b;
        } catch (Exception e) {
            return fill(cap, (byte) 0xFF);
        }
    }

    private void add(int off, byte[] b) { sparse.put(off, b); }

    /** Загрузить уже распакованный QMDC-образ (приоритет над сжатым .cla). */
    public void loadDecompressed(int off, byte[] b) {
        if (b != null && b.length > 0) sparse.put(off, b);
    }

    private int readImage(int addr) {
        for (Map.Entry<Integer, byte[]> e : sparse.entrySet()) {
            int o = addr - e.getKey();
            byte[] b = e.getValue();
            if (o >= 0 && o < b.length) return b[o] & 0xFF;
        }
        return 0xFF;
    }

    private void latchPage() {
        int addr = (row * PAGE) + column;
        for (int i = 0; i < PAGE; i++) fifo[i] = (byte) readImage(addr + i);
        fifoPos = 0;
    }

    public int read(int addr) {
        int off = addr - BASE;
        if (off < 0 || off >= SIZE) return 0;
        switch (off) {
            case 0x014: return status14;     // Ready / тип NAND
            case 0x0C0: return 0x16C9;       // статус верификации
            case 0x408: return 0xFFFFFFFF;   // ожидание trim
            case 0x100: return 0;            // DMA/контроль
            default: break;
        }
        if (off >= 0x800 && off < 0x800 + PAGE) {
            int v = fifo[fifoPos & (PAGE - 1)] & 0xFF;
            fifoPos++;
            return v;
        }
        return 0;
    }

    public void write(int addr, int value) {
        int off = addr - BASE;
        if (off < 0 || off >= SIZE) return;
        switch (off) {
            case 0x000: command = value & 0xFF; break;
            case 0x004: column = value & 0xFFF; fifoPos = 0; break;
            case 0x008: row = value & 0xFFFF; break;
            case 0x00C: row |= (value & 0xFF) << 16; break;
            case 0x010: status14 = value & 3; break;      // команда → статус ready
            case 0x030: break;               // включение
            default:
                if (command == 0x90 || command == 0x70) {
                    // READ ID / STATUS: ответ уже в фиксированных регистрах.
                }
                break;
        }
        if ((value & 0xFF) == 0x30 && command == 0x00) latchPage();
        if ((value & 0xFF) == 0x00) { command = 0x00; fifoPos = 0; }
        if ((value & 0xFF) == 0x90) command = 0x90;
        if ((value & 0xFF) == 0x70) command = 0x70;
    }
}
