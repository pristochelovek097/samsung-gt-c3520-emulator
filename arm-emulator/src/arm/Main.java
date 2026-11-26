package arm;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** CLI: run the Samsung C3520 bootloader in the ARM9 interpreter. */
public final class Main {
    private static boolean addrIsLcd(int a) {
        int p = a & 0xFFFFF;
        return (a & 0xFFFF0000) == 0x74020000 || p == 0x74002000 || p == 0x74002100
            || (p & ~1) == 0x7401900C;
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/home/bogdan/Downloads/C3520XELJ1";
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        boolean trace = args.length < 3 || !"quiet".equals(args[2]);

        String boot = dir + "/C3520XFLJ1_BIN/C3520XELaFleur.bootloader";
        boolean traceIO = false;
        for (String a : args) if ("--trace-mmio".equals(a)) traceIO = true;
        boolean saveSnap = false;
        for (String a : args) if ("--save-snap".equals(a)) saveSnap = true;
        boolean gui = false;
        for (String a : args) if ("--gui".equals(a)) gui = true;
        boolean noFast = false;
        for (String a : args) if ("--no-fast".equals(a)) noFast = true;
        boolean startRoot = false;
        for (String a : args) if ("--start-root".equals(a)) startRoot = true;
        TraceMemory tracer = traceIO ? new TraceMemory() : null;
        Memory mem = tracer != null ? tracer : new Memory();
        // Виртуальный NAND 0x7D80xxxx поfactory-карте .ptt (128 МБ, 2 КБ/страница).
        NandController nand = new NandController(dir);
        mem.setNandController(nand);
        // При холодном старте из ROOT физический адрес 0 не содержит USB-агента.
        if (!startRoot) mem.load(boot, 0);
        // При холодном старте из ROOT быстрая SRAM 0x2FFE0000 не содержит USB-агента.
        if (!startRoot) mem.load(boot, 0x2ffe0000);
        else {
            byte[] root = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(dir + "/C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_ROOT"));
            mem.load(root, 0x2000, 0x10000, 0);   // физический 0x0 зеркалит ROOT
        }
        // Загружаем полезную нагрузку .cla-образов по их физическим адресам
        // (заголовок 0x2000 байт: u32[0]=размер, u32[2]=физический адрес).
        java.io.File cla = new java.io.File(dir + "/C3520XFLJ1_BIN/C3520XELaFleur.cla");
        java.io.File[] parts = cla.listFiles();
        if (parts != null) {
            for (java.io.File f : parts) {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                if (b.length < 0x2000) continue;
                int size = java.nio.ByteBuffer.wrap(b, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                int phys = java.nio.ByteBuffer.wrap(b, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                java.io.File dec = new java.io.File("/tmp/opencode/dec/" + f.getName() + ".bin");
                if (dec.exists()) {
                    byte[] d = java.nio.file.Files.readAllBytes(dec.toPath());
                    mem.load(d, 0, d.length, phys);
                    System.out.printf("loaded DECOMPRESSED %-20s %08x .. %08x (%x bytes)%n",
                            f.getName(), phys, phys + d.length, d.length);
                } else {
                    mem.load(b, 0x2000, size, phys);
                    System.out.printf("loaded %-20s %08x .. %08x%n", f.getName(), phys, phys + size);
                }
            }
        }

        // Экспериментальная загрузка: --load <file> <fileOffset> <length> <virtualAddr>
        // Применяется после цикла обнуления RAM (PC=0x660), иначе загрузчик его затрёт.
        java.util.List<Runnable> deferred = new java.util.ArrayList<>();
        for (int i = 3; i + 4 < args.length; i++) {
            if (!"--load".equals(args[i])) continue;
            final String f0 = args[i + 1];
            final int off0 = (int) Long.decode(args[i + 2]).longValue();
            final int len0 = (int) Long.decode(args[i + 3]).longValue();
            final int va0 = (int) Long.decode(args[i + 4]).longValue();
            deferred.add(() -> {
                try {
                    byte[] blob = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(f0));
                    if (off0 >= 0 && off0 < blob.length) {
                        int len = Math.min(len0, blob.length - off0);
                        mem.load(blob, off0, len, va0);
                        System.out.printf("extra load (deferred): %s +%x -> %08x (%x bytes)%n", f0, off0, va0, len);
                    }
                } catch (Exception e) { System.out.println("load failed: " + e); }
            });
        }

        // Регистр ожидания готовности NAND/DMA: бит 19 означает "готово".
        mem.setMMIO(0x70002188, 0x00080000);
        // Битовая маска доступных разделов/устройств в контроллере (0x7001b000).
        mem.setMMIO(0x7001b038, 0xFFFFFFFF);
        mem.setMMIO(0x7001b03c, 0xFFFFFFFF);
        mem.setMMIO(0x7001b014, 0x00040000);   // готовность контроллера (bit18)
        // Таблица сервисных указателей в RAM (BLX r0 в 0x2FFE6F3C):
        // индекс берётся из [0x7000102c] (по умолчанию 0).
        deferred.add(() -> {
            for (int a = 0x3001d000; a < 0x3001d400; a += 8) mem.write32(a, 0x2ffe0ae4);
            mem.write8(0x3001d270, 0xFF);   // "последний обслуженный IRQ" — не совпадает с новым
        });

        boolean fromMain = false;
        for (String a : args) if ("--from-main".equals(a)) fromMain = true;
        boolean fromSnap = false;
        for (String a : args) if ("--from-snap".equals(a)) fromSnap = true;
        boolean snapped = false;
        boolean mainSaved = false;
        int dbgFirst = 0;
        final LcdController lcd = new LcdController();
        mem.setLcdController(lcd);
        final EmulatorWindow window = gui ? new EmulatorWindow(lcd) : null;
        if (window != null) lcd.testPattern();
        if (startRoot || fromSnap) mem.lockFlash();
        ARM cpu = new ARM(mem);
        if (noFast) cpu.fastPaths = false;
        cpu.irqLimit = 0;   // только таймерные IRQ, никакой слепой инъекции на wait-циклах
        if (fromMain) {
            try {
                String[] ln = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/tmp/opencode/ref_main_entry.bin")).toArray(new String[0]);
                for (int k = 0; k < 16; k++) cpu.r[k] = (int) Long.parseUnsignedLong(ln[k].trim(),16);
                cpu.cpsr = (int) Long.parseUnsignedLong(ln[16].trim(),16);
                for (int k = 0; k < 16; k++) cpu.cp15[k] = (int) Long.parseUnsignedLong(ln[17+k].trim(),16);
                if (ln.length >= 35) {
                    cpu.itcmReg = (int) Long.parseUnsignedLong(ln[33].trim(),16);
                    cpu.dtcmReg = (int) Long.parseUnsignedLong(ln[34].trim(),16);
                } else { // старый формат снимка
                    cpu.itcmReg = cpu.cp15[9];
                    cpu.dtcmReg = 0;
                }
                int[][] rg = {{0x0,0x10000},{0xc0000000,0xc0020000},{0xc0020000,0xc2000000},
                              {0xc2000000,0xc4000000},{0xc9900000,0xc9c00000},{0x30000000,0x30020000},
                              {0x70000000,0x70020000},{0x74000000,0x74020000},{0x7d800000,0x7d820000}};
                for (int[] r2 : rg) {
                    byte[] blob = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        String.format("/tmp/opencode/ref_main_%08x.bin", r2[0])));
                    mem.load(blob, 0, blob.length, r2[0]);
                }
            } catch (Exception e) { System.out.println("[MAIN] load failed " + e); }
        }
        if (fromSnap) {
            try {
                String[] lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/tmp/opencode/ref_entry_4a0.bin")).toArray(new String[0]);
                for (int k = 0; k < 16; k++) cpu.r[k] = (int) Long.parseUnsignedLong(lines[k].trim(), 16);
                cpu.cpsr = (int) Long.parseUnsignedLong(lines[16].trim(), 16);
                int[][] ranges = {{0x0,0x10000},{0xc0000000,0xc0010000},{0xc3bc0000,0xc3be0000},
                                  {0xc99a0000,0xc99e0000},{0x30000000,0x30020000},{0x70000000,0x70020000}};
                for (int[] rg : ranges) {
                    byte[] blob = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        String.format("/tmp/opencode/ref_entry_%08x.bin", rg[0])));
                    mem.load(blob, 0, blob.length, rg[0]);
                }
                snapped = true;
            } catch (Exception e) { System.out.println("[SNAP] load failed " + e); }
        }
        if (startRoot && !fromSnap) {
            cpu.r[15] = 0xc0000000;   // нормальная загрузка: сброс из ROOT, не USB-агент
            cpu.r[13] = 0x40001000;
            cpu.cpsr = 0xd3;
        }
        for (String a : args) if ("--trace-all".equals(a)) cpu.traceAll = true;
        if (!deferred.isEmpty()) cpu.afterZeroFill = () -> deferred.forEach(Runnable::run);

        // Вариант А: вызвать функцию прошивки с заданными аргументами.
        for (int i = 3; i + 5 < args.length; i++) {
            if (!"--call".equals(args[i])) continue;
            int target = (int) Long.decode(args[i + 1]).longValue();
            int a0 = (int) Long.decode(args[i + 2]).longValue();
            int a1 = (int) Long.decode(args[i + 3]).longValue();
            int a2 = (int) Long.decode(args[i + 4]).longValue();
            int n = Integer.parseInt(args[i + 5]);
            cpu.r[0] = a0; cpu.r[1] = a1; cpu.r[2] = a2;
            cpu.r[14] = 0xDEAD0000;
            cpu.cpsr = 0x10;
            cpu.r[15] = target & ~1;
            if ((target & 1) != 0) cpu.cpsr |= 0x20;
            for (int k = 0; k < n && !cpu.halted; k++) cpu.step(false);
            System.out.printf("call %08x(%08x,%08x,%08x) -> pc=%08x halted=%b %s%n",
                    target, a0, a1, a2, cpu.r[15], cpu.halted, cpu.haltReason);
            System.out.print("dest: ");
            for (int k = 0; k < 32; k++) System.out.printf("%02x", mem.read8(a1 + k));
            System.out.println();
            return;
        }
        // Фаза 1: заводской LCDACT + IMAGE — ранний бутскрин Samsung.
        // В реальном телефоне этот раздел исполняется до ROOT/MAIN и сам
        // инициализирует экран через 0x740xxxxx, затем выводит логотип.
        if (startRoot) {
            try {
                byte[] act = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        dir + "/C3520XFLJ1_BIN/C3520XELaFleur.bootlcdact"));
                int n = act.length - 0x2000;
                // Отдельная память: LCDACT перестраивает MMU/вектора и не должна
                // портить состояние, с которого стартует ROOT → MAIN.
                Memory bm = new Memory();
                bm.setLcdController(lcd);
                bm.load(act, 0x2000, n, 0xC0000000);    // exec @ .ptt load address
                bm.load(act, 0x2000, n, 0x0);            // зеркало ITCM/векторов
                ARM lc = new ARM(bm);
                lc.fastPaths = false;
                lc.r[15] = 0xC0000000;
                lc.r[13] = 0xC1AB0000;
                lc.cpsr = 0xD3;                          // ARM, SVC, I/F masked
                for (int k = 0; k < 200000 && !lc.halted; k++) lc.step(false);
                System.out.printf("[LCDACT] executed %d instr, halted=%s%n",
                        lc.instructions, lc.haltReason);

                // IMAGE (.bmp, QMDC) уже распакован в RGB565 — шлём его через
                // тот же MMIO-порт данных, что и заводской код (0x74002100).
                byte[] rgb = java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("/tmp/opencode/image_rgb565.bin"));
                bm.write32(0x74002000, 0x2C);            // RAMWR
                for (int k = 0; k + 1 < rgb.length; k += 2) {
                    int v = (rgb[k] & 0xFF) | ((rgb[k + 1] & 0xFF) << 8);
                    bm.write32(0x74002100, v);
                }
                System.out.printf("[BOOTSCREEN] commands=%d pixels=%d%n",
                        lcd.commands(), lcd.pixels());
                BufferedImage img = lcd.image();
                ImageIO.write(img, "PNG", new File("/tmp/opencode/bootscreen.png"));
                if (window != null) window.update(lc, 0);
            } catch (Exception e) {
                System.out.println("[BOOTSCREEN] failed: " + e);
            }
        }
        System.out.println("ARM9 emulator — C3520 bootloader");
        System.out.println("reset vector: " + String.format("%08x", mem.read32(0)));
        java.io.PrintWriter regTrace = null;
        for (String a : args) if ("--trace-regs".equals(a)) regTrace = new java.io.PrintWriter(fromMain ? "/tmp/opencode/java_main_trace.txt" : "/tmp/opencode/java_trace.txt");
        int dbgX = 0;
        boolean[] dbgSrv = new boolean[1];
        boolean[] dbgBm = new boolean[1];
        boolean[] dbgShut = new boolean[1];
        long irqOpenSteps = 0;
        boolean irqOpenPrev = true;
        int dbgInit = 0;
        String[] ring = new String[256]; int ringN = 0; boolean enteredLow = false;
        java.util.HashMap<Integer,Integer> pcHist = new java.util.HashMap<>();
        for (int i = 0; i < steps && !cpu.halted; i++) {
            if (fromMain) {
                if ((cpu.cpsr & 0x80) == 0) irqOpenSteps++;
                if (((cpu.cpsr & 0x80) == 0) != irqOpenPrev) { System.out.printf("[IRQMASK] instr=%d I=%d pc=%08x%n", cpu.instructions, (cpu.cpsr&0x80)==0?0:1, cpu.r[15]); irqOpenPrev=(cpu.cpsr&0x80)==0; }
            }
            if (startRoot || fromMain) { int k = cpu.r[15] & 0xFFFFFFFE; pcHist.merge(k, 1, Integer::sum); }
            if (saveSnap && !snapped && cpu.r[15] == 0xc00004a0) {
                snapped = true;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 16; k++) sb.append(String.format("%08x%n", cpu.r[k]));
                sb.append(String.format("%08x%n", cpu.cpsr));
                try {
                    java.nio.file.Path ref = java.nio.file.Paths.get("/tmp/opencode/ref_entry_4a0.bin");
                    if (!java.nio.file.Files.exists(ref))
                        java.nio.file.Files.write(ref, sb.toString().getBytes());
                    int[][] ranges = {{0x0,0x10000},{0xc0000000,0xc0010000},{0xc3bc0000,0xc3be0000},
                                      {0xc99a0000,0xc99e0000},{0x30000000,0x30020000},{0x70000000,0x70020000}};
                    for (int[] rg : ranges) {
                        byte[] blob = new byte[rg[1]-rg[0]];
                        for (int a = rg[0]; a < rg[1]; a++) blob[a-rg[0]] = (byte) mem.read8(a);
                        java.nio.file.Path bp = java.nio.file.Paths.get(
                            String.format("/tmp/opencode/ref_entry_%08x.bin", rg[0]));
                        if (!java.nio.file.Files.exists(bp)) java.nio.file.Files.write(bp, blob);
                    }
                    System.out.println("[SNAP] saved at pc=0xc00004a0 cpsr=" + Integer.toHexString(cpu.cpsr));
                } catch (Exception e) { System.out.println("[SNAP] failed " + e); }
            }
            if (regTrace != null && !(((cpu.cpsr & 0x20) != 0) &&
                    (((mem.read16(cpu.r[15]) & 0xF800) == 0xF800) ||
                     ((mem.read16(cpu.r[15]) & 0xF800) == 0xE800)))) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 16; k++) sb.append(String.format(" %08x", cpu.r[k]));
                regTrace.printf("%08x %08x%s %08x%n", cpu.r[15], mem.read32(cpu.r[15] & ~1), sb, cpu.cpsr);
            }
            // Системный тик: раз в 50000 инструкций INTC выставляет pending IRQ.
            if (!fromSnap && i > 1000 && (i % 100000) == 0 && (cpu.cpsr & 0x80) == 0) {
                mem.setMMIO(0x7000102c, 1);
                cpu.injectIRQ();
            }
            if (startRoot && cpu.r[15] == 0xc00588b8 && !mainSaved) {
                mainSaved = true;
                try {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < 16; k++) sb.append(String.format("%08x%n", cpu.r[k]));
                    sb.append(String.format("%08x%n", cpu.cpsr));
                    for (int k = 0; k < 16; k++) sb.append(String.format("%08x%n", cpu.cp15[k]));
                    sb.append(String.format("%08x%n", cpu.itcmReg));
                    sb.append(String.format("%08x%n", cpu.dtcmReg));
                    java.nio.file.Path rp = java.nio.file.Paths.get("/tmp/opencode/ref_main_entry.bin");
                    if (!java.nio.file.Files.exists(rp)) java.nio.file.Files.write(rp, sb.toString().getBytes());
                    int[][] rg2 = {{0x0,0x10000},{0xc0000000,0xc0020000},{0xc0020000,0xc2000000},
                                   {0xc2000000,0xc4000000},{0xc9900000,0xc9c00000},{0x30000000,0x30020000},
                                   {0x70000000,0x70020000},{0x74000000,0x74020000},{0x7d800000,0x7d820000}};
                    for (int[] r2 : rg2) {
                        byte[] blob = new byte[r2[1]-r2[0]];
                        for (int a = r2[0]; a < r2[1]; a++) blob[a-r2[0]] = (byte) mem.read8(a);
                        java.nio.file.Path p2 = java.nio.file.Paths.get(String.format("/tmp/opencode/ref_main_%08x.bin", r2[0]));
                        if (!java.nio.file.Files.exists(p2)) java.nio.file.Files.write(p2, blob);
                    }
                    System.out.println("[MAINSNAP] saved");
                } catch (Exception e) { System.out.println("[MAINSNAP] " + e); }
            }
            if (startRoot && cpu.r[15] >= 0xc0020000 && cpu.r[15] < 0xc2000000 && dbgFirst++ < 12)
                System.out.printf("[FIRST] %08x cpsr=%08x%n", cpu.r[15], cpu.cpsr);
            if (fromMain && dbgX++ < 1) {
                int va = 0xca2ccabc;
                int tt = cpu.cp15[2] & 0xFFFFC000;
                int l1 = mem.read32(tt + ((va>>>20)<<2));
                System.out.printf("[MMU] va=%08x pa=%08x L1=%08x l2=%08x%n", va, cpu.va2pa(va), l1,
                    mem.read32((l1 & 0xFFFFFC00) + (((va>>>12)&0xFF)<<2)));
            }
            if (tracer != null) { tracer.notePC(cpu.r[15]); tracer.currentPC = cpu.r[15]; }
            if (tracer != null && tracer instanceof TraceMemory) {
                TraceMemory tm = (TraceMemory) tracer;
                tm.windowStep = cpu.instructions;
                tm.traceWindow = fromMain && cpu.instructions >= 34850 && cpu.instructions <= 34920;
            }
            if (addrIsLcd(cpu.r[15])) lcd.write(cpu.r[15], mem.read32(cpu.r[15]));

            if ((cpu.r[15] & 0xF0000000) == 0 && cpu.instructions > 1000000000L && !enteredLow) {
                enteredLow = true;
                System.out.printf("[ENTERLOW] instr=%d pc=%08x lr=%08x%n", cpu.instructions, cpu.r[15], cpu.r[14]);
                for (int k = 0; k < ring.length; k++) {
                    String e = ring[(ringN + k) % ring.length];
                    if (e != null) System.out.println(e);
                }
            }
            {
            int[] rr = cpu.r.clone();
            StringBuilder rb = new StringBuilder(String.format("%d %08x %08x", cpu.instructions, cpu.r[15], mem.read32(cpu.r[15] & ~1)));
            for (int k = 0; k < 16; k++) rb.append(String.format(" %08x", rr[k]));
            rb.append(String.format(" cpsr=%08x", cpu.cpsr));
            ring[ringN++ % ring.length] = rb.toString();
            }
                System.out.printf("[REG] step=%d r0=%08x r1=%08x lr=%08x%n", cpu.instructions, cpu.r[0], cpu.r[1], cpu.r[14]);
            // Прошивка запрашивает сервис №10 (0xC0411874) — подаём его
            // заводской дескриптор: obj -> vptr-значение 0xC0D4CDCC, byte+8=1.
            if ((startRoot || fromMain) && cpu.r[15] == 0xc0228680 && cpu.r[0] == 0xa && !dbgSrv[0]) {
                dbgSrv[0] = true;
                mem.write32(0xc1ab0100, 0xc0d4cdcc);
                mem.write32(0xc1ab0000, 0xc1ab0100);
                mem.write8(0xc1ab0008, 1);
                mem.write32(0xc1ab0010, 0xc1ab0000);
                mem.write32(0xc1c76c30, 0xc1ab0010);
                System.out.printf("[SERVICE10] step=%d%n", cpu.instructions);
            }
            if ((startRoot || fromMain) && cpu.instructions == 1000 && !dbgBm[0]) {
                dbgBm[0] = true;
                mem.write32(0xc1ca0178, 0x20000000);
                System.out.printf("[BOOTMODE] step=%d val=%08x%n", cpu.instructions, mem.read32(0xc1ca0178));
            }
            cpu.step(trace);
            if (window != null && (i % 2000) == 0) window.update(cpu, 0);
        }
        if (regTrace != null) regTrace.close();
        System.out.println();
        System.out.printf("vectors: 00=%08x 18=%08x 20=%08x 24=%08x 28=%08x%n",
                mem.read32(0), mem.read32(0x18), mem.read32(0x20), mem.read32(0x24), mem.read32(0x28));
        System.out.printf("mem[2ffe23dc]=%08x mem[2ffe0794]=%08x mem[40000000]=%08x%n",
                mem.read32(0x2ffe23dc), mem.read32(0x2ffe0794), mem.read32(0x40000000));
        System.out.println(cpu.dumpRegs());
        if (startRoot) {
            System.out.print("[L1] ");
            for (int idx : new int[]{0x000,0x2ff,0x300,0xa00,0xc00,0xfff})
                System.out.printf("%03x=%08x ", idx, mem.read32(0xc3bc4000 + idx*4));
            System.out.println();
        }
        if (startRoot || fromMain) {
            System.out.println("[HOTPC] " + pcHist.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue())).limit(12)
                .map(e -> String.format("%08x:%d", e.getKey(), e.getValue()))
                .reduce("", (a,b) -> a + " " + b));
            int bestBase = -1, bestNz = 0;
            for (int a = 0x30000000; a < 0x32000000; a += 0x1000) {
                int nz = 0;
                for (int k = a; k < a + 0x1000; k += 16) if ((mem.read32(k) & 0xFFFF) != 0) nz++;
                if (nz > bestNz) { bestNz = nz; bestBase = a; }
            }
            System.out.printf("[FB] best SDRAM candidate=%08x nonzero16=%d%n", bestBase, bestNz);
        }
        if (cpu.halted) {
            System.out.println("[LAST]");
            for (int k = 0; k < ring.length; k++) {
                String e = ring[(ringN + k) % ring.length];
                if (e != null) System.out.println(e);
            }
        }
        if (fromMain) System.out.println("[IRQOPEN] steps=" + irqOpenSteps);
        if (startRoot || fromMain) {
            int tabPa = cpu.va2pa(0xca2ccabc);
            int nz = 0;
            System.out.printf("[TAB40] va=ca2ccabc pa=%08x:", tabPa);
            for (int k = 0; k < 40; k++) {
                int v = mem.read32(tabPa + k * 0x14 + 4);
                if (v == 0) System.out.printf("[TAB40] ZERO slot=%d addr=%08x%n", k, 0xca2ccabc + k*0x14);
                if ((v & 0xF0000000) == 0xC0000000) nz++;
                if (k < 8) System.out.printf(" %08x", v);
            }
            System.out.printf(" ... live=%d/40%n", nz);
        }
        System.out.printf("[LCD] commands=%d pixels=%d frames=%d%n", lcd.commands(), lcd.pixels(), lcd.frames());
        System.out.println("halted: " + cpu.halted + " " + cpu.haltReason);
    }
}
