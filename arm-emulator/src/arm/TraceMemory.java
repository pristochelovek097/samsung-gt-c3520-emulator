package arm;

import java.util.HashSet;
import java.util.Set;

/** Память с логированием периферии и больших записей в RAM. */
public final class TraceMemory extends Memory {
    private final Set<Integer> ioSeen = new HashSet<>();
    private final Set<Integer> pcPages = new HashSet<>();
    private int logCount;
    private int dbgTabW;
    public int currentPC;
    public boolean traceWindow;
    public long windowStep;

    private void io(int addr, String kind, int value) {
        int key = addr & ~3;
        if (!ioSeen.add(key)) return;
        if (logCount++ < 300)
            System.out.printf("[IO] pc=%08x %s %08x = %08x%n", currentPC, kind, addr, value);
    }

    @Override
    public int read8(int addr) {
        int v = super.read8(addr);
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "rd8", v);
        return v;
    }

    private void uart(int addr, int value) {
        if (addr >= 0x70004000 && addr <= 0x7000400f) {
            int c = value & 0xFF;
            System.out.printf("[UART] %08x <- %02x '%s'%n", addr, c,
                    (c >= 32 && c < 127) ? String.valueOf((char) c) : ".");
        }
    }

    @Override
    public void write8(int addr, int value) {
        uart(addr, value);
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "wr8", value & 0xFF);
        super.write8(addr, value);
    }

    @Override
    public int read16(int addr) {
        int v = super.read16(addr);
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "rd16", v);
        return v;
    }

    @Override
    public void write16(int addr, int value) {
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "wr16", value & 0xFFFF);
        super.write16(addr, value);
    }

    @Override
    public int read32(int addr) {
        int v = super.read32(addr);
        if (traceWindow && ((addr >>> 24) == 0x78 || (addr >>> 24) == 0x70))
            System.out.printf("[WIN] %d pc=%08x rd32 %08x = %08x%n", windowStep, currentPC, addr, v);
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "rd32", v);
        return v;
    }

    @Override
    public void write32(int addr, int value) {
        if (traceWindow && ((addr >>> 24) == 0x78 || (addr >>> 24) == 0x70))
            System.out.printf("[WIN] %d pc=%08x wr32 %08x = %08x%n", windowStep, currentPC, addr, value);
        uart(addr, value);
        if (addr >= 0xc22cc000 && addr <= 0xc22cd000 && dbgTabW++ < 10)
            System.out.printf("[TABW] pc=%08x addr=%08x val=%08x%n", currentPC, addr, value);
        if ((addr & 0xF0000000) == 0x70000000) io(addr, "wr32", value);
        // EOI: подтверждение прерывания снимает pending-бит в INTC
        if (addr == 0x70001038) setMMIO(0x7000102c, 0);
        super.write32(addr, value);
    }

    public void notePC(int pc) {
        currentPC = pc;
        int page = pc & 0xFFFFF000;
        if (pcPages.add(page) && logCount < 400)
            System.out.printf("[PC] entered page %08x at %08x%n", page, pc);
    }
}
