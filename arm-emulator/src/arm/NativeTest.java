package arm;

import java.nio.file.*;

/** Вызов родного QMDC-декодера 0x2FFE4190 из .bootloader для отладочной сверки. */
public final class NativeTest {
    public static void main(String[] args) throws Exception {
        String dir = "/home/bogdan/Downloads/C3520XELJ1";
        String claName = args[0];
        long slotOff = Long.decode(args[1]);
        String outPath = args[2];

        byte[] boot = Files.readAllBytes(Paths.get(dir + "/C3520XFLJ1_BIN/C3520XELaFleur.bootloader"));
        byte[] cla = Files.readAllBytes(Paths.get(dir + "/C3520XFLJ1_BIN/C3520XELaFleur.cla/" + claName));

        Memory mem = new Memory();
        mem.load(boot, 0x2ffe0000);
        int CHUNK = 0x31000000;
        int OUT = 0x32000000;
        mem.load(cla, (int) slotOff, 0x1000, CHUNK);
        mem.memset(OUT, OUT + 0x40000, 0);

        ARM cpu = new ARM(mem);
        int hdr = args.length > 3 ? Long.decode(args[3]).intValue() : 8;
        cpu.r[0] = CHUNK + hdr;         // указатель на поля B/C/D
        cpu.r[1] = OUT;                 // выходной буфер
        cpu.r[13] = 0x33000000;         // стек
        cpu.r[14] = 0xDEAD0000;         // возврат
        cpu.cpsr = 0x10;
        cpu.r[15] = 0x2ffe4190;
        for (int i = 0; i < 3000000 && !cpu.halted && cpu.r[15] != 0xDEAD0000; i++) {
            if (i < 2000 && cpu.r[15] == 0x2ffe42dc)
                System.out.printf("flags=%02x r6=%d r5=%08x sl=%08x r8=%08x%n",
                        cpu.r[0] & 0xFF, cpu.r[6], cpu.r[5], cpu.r[10], cpu.r[8]);
            cpu.step(false);
        }
        System.out.printf("pc=%08x halted=%b %s instr=%d%n", cpu.r[15], cpu.halted,
                cpu.haltReason, cpu.instructions);
        byte[] out = new byte[0x40000];
        for (int i = 0; i < out.length; i++) out[i] = (byte) mem.read8(OUT + i);
        Files.write(Paths.get(outPath), out);
    }
}
