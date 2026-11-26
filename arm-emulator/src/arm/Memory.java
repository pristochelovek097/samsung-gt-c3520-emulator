package arm;

import java.util.HashMap;
import java.util.Map;

/** Sparse 32-bit little-endian address space with flat arrays for flash/SDRAM/SRAM. */
public class Memory {
    private static final int PAGE_BITS = 12;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    private final Map<Integer, byte[]> pages = new HashMap<>();
    private final Map<Integer, Integer> mmio = new HashMap<>();
    private NandController nand;
    private LcdController lcd;

    public void setNandController(NandController c) { this.nand = c; }
    public void setLcdController(LcdController c) { this.lcd = c; }

    /** Порты дисплея из LCDACT:记录和 команды уходят прямо в контроллер экрана. */
    private boolean lcdPort(int addr) {
        int p = addr & 0x000FFFFF;
        return addr == 0x74002000 || addr == 0x74002100
            || (addr & ~1) == 0x7401900C
            || (addr & 0xFFFF0000) == 0x74020000;
    }

    // Плоские физические области PNX4910: 32 МБ NOR/flash, 32 МБ SDRAM, 1 МБ SRAM.
    private static final int FLASH_BASE = 0xC0000000, FLASH_SIZE = 0x02000000;
    private static final int SDRAM_BASE = 0xC2000000, SDRAM_SIZE = 0x02000000;
    private static final int SRAM_BASE  = 0xC9900000, SRAM_SIZE  = 0x00200000;
    private final byte[] flash = new byte[FLASH_SIZE];
    private final byte[] sdram = new byte[SDRAM_SIZE];
    private final byte[] sram  = new byte[SRAM_SIZE];
    // ARM926EJ-S: ITCM/DTCM — отдельная внутренняя SRAM по младшим адресам,
    // обращения к ней идут мимо таблицы MMU, когда включён CP15 c9.
    public static final int TCM_SIZE = 0x00040000;
    private final byte[] itcm = new byte[TCM_SIZE];

    private byte[] flat(int addr) {
        if (addr >= 0 && addr < TCM_SIZE) return itcm;
        int a = addr & 0xFFFFFFFF;
        if ((a & 0xFE000000) == FLASH_BASE) return flash;
        if ((a & 0xFE000000) == SDRAM_BASE) return sdram;
        if ((a & 0xFFE00000) == SRAM_BASE) return sram;
        return null;
    }

    private int flatOff(int addr) {
        if (addr >= 0 && addr < TCM_SIZE) return addr;
        return addr & 0x01FFFFFF;
    }

    // Весь диапазон 0xC0000000..0xC3FFFFFF — физическая SDRAM, читаемо-пишущая.
    public void lockFlash() { }

    public void setMMIO(int addr, int value) { mmio.put(addr & ~3, value); }

    private byte[] page(int addr, boolean create) {
        int key = addr >>> PAGE_BITS;
        byte[] p = pages.get(key);
        if (p == null && create) { p = new byte[PAGE_SIZE]; pages.put(key, p); }
        return p;
    }

    public void load(String file, int address) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file));
        load(b, address);
    }

    public void load(byte[] b, int address) {
        for (int i = 0; i < b.length; i++) write8(address + i, b[i]);
    }

    public void load(byte[] b, int offset, int length, int address) {
        for (int i = 0; i < length && offset + i < b.length; i++) write8(address + i, b[offset + i]);
    }

    public int read32(int addr) {
        if (nand != null && ((addr ^ NandController.BASE) & ~0xFFFF) == 0)
            return nand.read(addr);
        Integer fixed = mmio.get(addr & ~3);
        if (fixed != null) return fixed;
        int a = addr & ~3;
        byte[] f = flat(a);
        if (f != null) {
            int o = flatOff(a);
            return (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8)
                 | ((f[o + 2] & 0xFF) << 16) | ((f[o + 3] & 0xFF) << 24);
        }
        return (read8(a) & 0xFF) | ((read8(a + 1) & 0xFF) << 8)
             | ((read8(a + 2) & 0xFF) << 16) | ((read8(a + 3) & 0xFF) << 24);
    }

    public void write32(int addr, int value) {
        if (lcd != null && lcdPort(addr)) { lcd.write(addr, value); return; }
        // 0x70002018 — самосбрасывающийся бит «go» NAND/DMA: железо гасит
        // его по завершении операции, драйвер крутится до нуля.
        if ((addr & ~3) == 0x70002018) return;
        if (nand != null && ((addr ^ NandController.BASE) & ~0xFFFF) == 0) {
            nand.write(addr, value);
            return;
        }
        int a = addr & ~3;
        byte[] f = flat(a);
        if (f != null) {
            int o = flatOff(a);
            f[o] = (byte) value; f[o + 1] = (byte) (value >>> 8);
            f[o + 2] = (byte) (value >>> 16); f[o + 3] = (byte) (value >>> 24);
            return;
        }
        for (int i = 0; i < 4; i++) write8(a + i, (byte) (value >>> (8 * i)));
    }

    public int read16(int addr) {
        int a = addr & ~1;
        byte[] f = flat(a);
        if (f != null) {
            int o = flatOff(a);
            return (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8);
        }
        byte[] p = page(a, false);
        if (p == null) return 0;
        int o = a & PAGE_MASK;
        if (o + 1 >= PAGE_SIZE) {
            byte[] q = page(a + 1, false);
            return q == null ? p[o] & 0xFF : (p[o] & 0xFF) | ((q[0] & 0xFF) << 8);
        }
        return (p[o] & 0xFF) | ((p[o + 1] & 0xFF) << 8);
    }

    public void write16(int addr, int value) {
        if (lcd != null && lcdPort(addr)) { lcd.write(addr, value & 0xFFFF); return; }
        int a = addr & ~1;
        byte[] f = flat(a);
        if (f != null) {
            int o = flatOff(a);
            f[o] = (byte) value; f[o + 1] = (byte) (value >>> 8);
            return;
        }
        write8(addr, (byte) value);
        write8(addr + 1, (byte) (value >>> 8));
    }

    public int read8(int addr) {
        if (nand != null && ((addr ^ NandController.BASE) & ~0xFFFF) == 0) return nand.read(addr) & 0xFF;
        byte[] f = flat(addr);
        if (f != null) return f[flatOff(addr)] & 0xFF;
        byte[] p = page(addr, false);
        return p == null ? 0 : p[addr & PAGE_MASK] & 0xFF;
    }

    public void write8(int addr, int value) {
        if (lcd != null && lcdPort(addr)) { lcd.write(addr, value & 0xFF); return; }
        if (nand != null && ((addr ^ NandController.BASE) & ~0xFFFF) == 0) { nand.write(addr, value); return; }
        byte[] f = flat(addr);
        if (f != null) { f[flatOff(addr)] = (byte) value; return; }
        byte[] p = page(addr, true);
        p[addr & PAGE_MASK] = (byte) value;
    }

    public void memset(int from, int to, int value) {
        int v = value & 0xFF;
        for (int a = from; a < to; a++) write8(a, (byte) v);
    }

    public void memcpy(int dst, int src, int length) {
        for (int i = 0; i < length; i++) write8(dst + i, read8(src + i));
    }
}
