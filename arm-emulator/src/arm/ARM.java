package arm;

/** ARM9 (ARMv5TE) integer interpreter — first stage of the C3520 full emulator. */
public final class ARM {
    public final int[] r = new int[16];
    public int cpsr = 0x10;          // ARM state, user mode
    public final Memory mem;
    public long instructions;
    public boolean halted;
    public String haltReason = "";

    private int shifterCarry;
    private int thumbBlInsn;
    private int thumbBlAddr;
    private boolean irqTriggered;
    public int irqLimit = 3;
    private int irqCount;
    private int spsr;
    public Runnable afterZeroFill;
    public boolean traceAll;
    public boolean fastPaths = false;   // ручные ускорители ломают регистры — по умолчанию ВЫКЛ
    private long zeroRun;

    public void injectIRQ() { if (!halted) raiseIRQ(r[15]); }

    private void raiseSVC() {
        int ret = r[15];                 // ARM: addr+4, Thumb: addr+2 (уже в r15)
        spsr = cpsr;
        switchMode(0x13);                // SVC: банковские r13/r14
        cpsr |= 0x80;                    // I=1
        cpsr &= ~0x20;                   // вектор исполняется в ARM-состоянии
        r[14] = ret;
        int base = (cp15[1] & 0x2000) != 0 ? 0xFFFF0000 : 0;  // V bit
        int va = base + 8;
        int pa = translate(va);
        r[15] = pa & 0xFFFFFFFC;
    }

    private void raiseIRQ(int returnAddr) {
        // IRQ может приходить многократно — сбрасываем флаг при каждом новом wait-loop.
        spsr = cpsr;
        switchMode(0x12);              // IRQ mode
        cpsr |= 0x80;                  // I bit
        cpsr &= ~0x20;                 // ARM state
        r[14] = returnAddr;            // LR_irq
        int vbase = (cp15[1] & 0x2000) != 0 ? 0xFFFF0000 : 0;
        r[15] = vbase + 0x18;   // ARM: PC = адрес вектора, там сама инструкция
        System.out.println("IRQ: " + Integer.toHexString(r[15]));
    }

    /** Минимальное состояние CP15: c1=control, c2=TTBR, c3=domain, c7/cache ops. */
    public final int[] cp15 = new int[16];

    /* Банкированные SP/LR для режимов ARM (USR/FIQ/IRQ/SVC/ABT/UND/SYS). */
    private final int[][] banked = new int[32][2];
    private final int[] fiq = new int[5];   // FIQ банкирует r8..r12

    private static int bankIndex(int mode) {
        switch (mode & 0x1F) {
            case 0x10: case 0x1F: return 0; // USR / SYS
            case 0x11: return 1;            // FIQ
            case 0x12: return 2;            // IRQ
            case 0x13: return 3;            // SVC
            case 0x17: return 4;            // ABT
            case 0x1B: return 5;            // UND
            default: return 6;
        }
    }

    private void switchMode(int newMode) {
        int oldMode = cpsr & 0x1F;
        int old = bankIndex(oldMode);
        banked[old][0] = r[13];
        banked[old][1] = r[14];
        if (oldMode == 0x11) for (int k = 0; k < 5; k++) fiq[k] = r[8 + k];
        cpsr = (cpsr & 0xFFFFFF00) | (newMode & 0xFF);
        int nw = bankIndex(newMode);
        r[13] = banked[nw][0];
        r[14] = banked[nw][1];
        if ((newMode & 0x1F) == 0x11) for (int k = 0; k < 5; k++) r[8 + k] = fiq[k];
    }

    private int tlbVaTag = -1, tlbPaBase = 0, tlbMask = 0xFFF;

    private int translate(int va) {
        if ((cp15[1] & 1) == 0) return va;         // MMU выключен
        if ((va & ~tlbMask) == tlbVaTag) return tlbPaBase | (va & tlbMask);
        int ttbr = cp15[2] & 0xFFFFC000;
        int l1 = mem.read32(ttbr + ((va >>> 20) << 2));
        int type = l1 & 3;
        if (type == 2) return (l1 & 0xFFF00000) | (va & 0x000FFFFF); // section 1 MB
        if (type == 1) {                                             // coarse page table
            int l2base = l1 & 0xFFFFF000;
            int l2 = mem.read32(l2base + (((va >>> 12) & 0xFF) << 2));
            int t = l2 & 3;
            if (t == 2) { tlbVaTag = va & ~0xFFF; tlbPaBase = l2 & 0xFFFFF000; tlbMask = 0xFFF; return tlbPaBase | (va & 0xFFF); }
            if (t == 1) { tlbVaTag = va & ~0xFFFF; tlbPaBase = l2 & 0xFFFF0000; tlbMask = 0xFFFF; return tlbPaBase | (va & 0xFFFF); }
        }
        return va;                                                   // fallback: identity
    }

    public int va2pa(int va) { return translate(va); }

    // ARMv5TE: невыровненный LDR/STR читает выровненное слово и вращает его
    private int rd32(int a) {
        int w = mem.read32(translate(a & ~3));
        return (a & 3) == 0 ? w : Integer.rotateRight(w, (a & 3) * 8);
    }
    private void wr32(int a, int v) {
        mem.write32(translate(a & ~3), (a & 3) == 0 ? v : Integer.rotateLeft(v, (a & 3) * 8));
    }
    private int rd16(int a) { return mem.read16(translate(a)); }
    private void wr16(int a, int v) { mem.write16(translate(a), v); }
    private int rd8(int a) { return mem.read8(translate(a)); }
    private void wr8(int a, int v) { mem.write8(translate(a), v); }

    public ARM(Memory mem) {
        this.mem = mem;
        reset();
    }

    public void reset() {
        for (int i = 0; i < 16; i++) r[i] = 0;
        r[13] = 0x40001000;           //(stack placeholder)
        cpsr = 0xD3;              // ARM9 reset: SVC, I=1, F=1
        instructions = 0;
        halted = false;
        irqTriggered = false;
        banked[1][0] = 0x40000E00;  // FIQ stack
        banked[2][0] = 0x40000F00;  // IRQ stack
        banked[3][0] = 0x40000D00;  // SVC stack
    }

    private boolean bit(int v, int n) { return ((v >>> n) & 1) != 0; }

    private boolean cond(int c) {
        boolean N = bit(cpsr, 31), Z = bit(cpsr, 30), C = bit(cpsr, 29), V = bit(cpsr, 28);
        switch (c) {
            case 0: return Z;
            case 1: return !Z;
            case 2: return C;
            case 3: return !C;
            case 4: return N;
            case 5: return !N;
            case 6: return V;
            case 7: return !V;
            case 8: return C && !Z;
            case 9: return !C || Z;
            case 10: return N == V;
            case 11: return N != V;
            case 12: return !Z && N == V;
            case 13: return Z || N != V;
            case 15: return false;
            default: return true;
        }
    }

    private int readReg(int i) { return i == 15 ? r[15] + 4 : r[i]; }

    private void writeReg(int i, int v) {
        if (i == 15) r[15] = v & 0xFFFFFFFC;
        else r[i] = v;
    }

    private void setNZ(int v) {
        if (v < 0) cpsr |= 1 << 31; else cpsr &= ~(1 << 31);
        if (v == 0) cpsr |= 1 << 30; else cpsr &= ~(1 << 30);
    }

    private void setC(boolean c) { if (c) cpsr |= 1 << 29; else cpsr &= ~(1 << 29); }
    private void setV(boolean v) { if (v) cpsr |= 1 << 28; else cpsr &= ~(1 << 28); }

    private int shift(int value, int type, int amount) {
        shifterCarry = bit(cpsr, 29) ? 1 : 0;
        if (amount == 0) return value;
        switch (type) {
            case 0: // LSL
                if (amount >= 32) { if (amount == 32) shifterCarry = value & 1; else shifterCarry = 0; return 0; }
                shifterCarry = (value >>> (32 - amount)) & 1;
                return value << amount;
            case 1: // LSR
                if (amount >= 32) { if (amount == 32) shifterCarry = (value >>> 31) & 1; else shifterCarry = 0; return 0; }
                shifterCarry = (value >>> (amount - 1)) & 1;
                return value >>> amount;
            case 2: // ASR
                if (amount >= 32) { shifterCarry = value < 0 ? 1 : 0; return value < 0 ? -1 : 0; }
                shifterCarry = (value >>> (amount - 1)) & 1;
                return value >> amount;
            default: { // ROR / RRX
                int a = amount & 31;
                if (a == 0) { shifterCarry = (value >>> 31) & 1; return value; }
                shifterCarry = (value >>> (a - 1)) & 1;
                return Integer.rotateRight(value, a);
            }
        }
    }

    private int operand2(int insn) {
        if (bit(insn, 25)) {
            int imm = insn & 0xFF;
            int rot = ((insn >>> 8) & 0xF) * 2;
            int v = Integer.rotateRight(imm, rot);
            shifterCarry = rot == 0 ? (bit(cpsr, 29) ? 1 : 0) : (v >>> 31) & 1;
            return v;
        }
        int rm = insn & 0xF;
        int value = readReg(rm);
        int amount;
        if (bit(insn, 4)) {
            int rs = (insn >>> 8) & 0xF;
            amount = readReg(rs) & 0xFF;
        } else amount = (insn >>> 7) & 0x1F;
        return shift(value, (insn >>> 5) & 3, amount);
    }

    private int alu(int op, int a, int b, boolean setFlags) {
        int res = 0;
        long tmp;
        switch (op) {
            case 0: res = a & b; break;                                    // AND
            case 1: res = a ^ b; break;                                    // EOR
            case 2: tmp = (a & 0xFFFFFFFFL) - (b & 0xFFFFFFFFL); res = (int) tmp;
                    if (setFlags) { setC(tmp >= 0 && tmp <= 0xFFFFFFFFL);
                        setV(((a ^ b) & (a ^ res)) < 0); } break;          // SUB
            case 3: tmp = (b & 0xFFFFFFFFL) - (a & 0xFFFFFFFFL); res = (int) tmp;
                    if (setFlags) { setC(tmp >= 0 && tmp <= 0xFFFFFFFFL);
                        setV(((b ^ a) & (b ^ res)) < 0); } break;          // RSB
            case 4: tmp = (a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL); res = (int) tmp;
                    if (setFlags) { setC(tmp > 0xFFFFFFFFL);
                        setV((~(a ^ b) & (a ^ res)) < 0); } break;         // ADD
            case 5: { int c = bit(cpsr, 29) ? 1 : 0;
                    tmp = (a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL) + c; res = (int) tmp;
                    if (setFlags) { setC(tmp > 0xFFFFFFFFL);
                        setV((~(a ^ b) & (a ^ res)) < 0); } break; }       // ADC
            case 6: { int c = bit(cpsr, 29) ? 1 : 0;
                    tmp = (a & 0xFFFFFFFFL) - (b & 0xFFFFFFFFL) + c - 1; res = (int) tmp;
                    if (setFlags) { setC(tmp >= 0 && tmp <= 0xFFFFFFFFL);
                        setV(((a ^ b) & (a ^ res)) < 0); } break; }        // SBC
            case 7: { int c = bit(cpsr, 29) ? 1 : 0;
                    tmp = (b & 0xFFFFFFFFL) - (a & 0xFFFFFFFFL) + c - 1; res = (int) tmp;
                    if (setFlags) { setC(tmp >= 0 && tmp <= 0xFFFFFFFFL);
                        setV(((b ^ a) & (b ^ res)) < 0); } break; }        // RSC
            case 8:  res = a & b; if (setFlags) setC(shifterCarry != 0); break;   // TST
            case 9:  res = a ^ b; if (setFlags) setC(shifterCarry != 0); break;   // TEQ
            case 10: { // CMP — вычитание с флагами
                tmp = (a & 0xFFFFFFFFL) - (b & 0xFFFFFFFFL); res = (int) tmp;
                if (setFlags) { setC(tmp >= 0 && tmp <= 0xFFFFFFFFL);
                    setV(((a ^ b) & (a ^ res)) < 0); }
                break;
            }
            case 11: { // CMN — сложение с флагами
                tmp = (a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL); res = (int) tmp;
                if (setFlags) { setC(tmp > 0xFFFFFFFFL);
                    setV((~(a ^ b) & (a ^ res)) < 0); }
                break;
            }
            case 12: res = a | b; break;                                   // ORR
            case 13: res = b; break;                                       // MOV
            case 14: res = a & ~b; break;                                  // BIC
            case 15: res = ~b; break;                                      // MVN
        }
        if (setFlags && (op == 0 || op == 1 || op == 12 || op == 13 || op == 14 || op == 15)) {
            setC(shifterCarry != 0);
        }
        // TST/TEQ/CMP/CMN должны обновлять N и Z — без этогоConditional branches не работают.
        if (setFlags && (op == 8 || op == 9 || op == 10 || op == 11)) setNZ(res);
        return res;
    }

    private int shiftedRegister(int insn) {
        int rm = insn & 0xF;
        int value = readReg(rm);
        int amount = bit(insn, 4) ? (readReg((insn >>> 8) & 0xF) & 0xFF) : ((insn >>> 7) & 0x1F);
        return shift(value, (insn >>> 5) & 3, amount);
    }

    public void step(boolean trace) {
        if (halted) return;
        if ((cpsr & 0x20) != 0) { stepThumb(trace); return; }
        int addr = r[15];
        int insn = rd32(addr);
        if (insn == 0) { if (++zeroRun > 500) { halted = true; haltReason = "unmapped memory at " + Integer.toHexString(addr); } }
        else zeroRun = 0;
        r[15] = addr + 4;
        instructions++;

        if (insn == 0xEAFFFFFE && (cpsr & 0x1F) != 0x12 && irqCount < irqLimit) { irqCount++; raiseIRQ(addr + 4); return; }

        // BLX immediate использует cond=1111 (NV), но выполняется всегда.
        boolean isBlxImm = (insn & 0xFE000000) == 0xFA000000;
        if (!isBlxImm && !cond(insn >>> 28)) return;

        // Ускоритель циклов memset/memcpy загрузчика (иначе сотни млн итераций).
        if (fastPaths && addr == 0x658 && (insn & 0x0F000000) == 0x03000000) { // strlo r2,[r0],#4
            mem.memset(r[0], r[1], r[2]);
            r[0] = r[1];
            cpsr &= ~(1 << 29);          // C=0 → LO больше не выполняется
            r[15] = 0x660;
            return;
        }
        // ROOT 0xC0001158: STMGE r0!,{r4..fp} — обнуление по 32 байта за итерацию
        if (fastPaths && addr == 0xc0001158 && r[1] >= 32) {
            int n = r[1] >>> 5;
            mem.memset(r[0], r[0] + n * 32, 0);
            r[0] += n * 32; r[1] -= n * 32; r[15] = 0xc0001168;
            return;
        }
        // ROOT: ускорение циклов копирования/обнуления секций (иначе десятки млн итераций)
        if (fastPaths && addr == 0xc00012e4 && (insn & 0x0F000000) == 0x05000000) { // str r3,[r1],#4 (zero)
            int n = r[0] - r[2];
            if (n > 16) {
                mem.memset(r[1], r[1] + 4 * n, r[3]);
                r[1] += 4 * n; r[2] = r[0]; r[15] = 0xc00012f4;
                return;
            }
        }
        if (fastPaths && addr == 0xc00012ac && (insn & 0x0F000000) == 0x04000000) { // ldr ip,[r1],#4
            int n = r[0] - r[2];
            if (n > 16) {
                mem.memcpy(r[3], r[1], 4 * n);
                r[1] += 4 * n; r[3] += 4 * n; r[2] = r[0]; r[15] = 0xc00012c0;
                return;
            }
        }
        if (fastPaths && addr == 0x674 && (insn & 0x0F000000) == 0x04000000) { // ldrlo r2,[r4],#4
            int len = r[1] - r[0];
            mem.memcpy(r[0], r[4], len);
            r[0] = r[1];
            r[4] += len;
            cpsr &= ~(1 << 29);
            r[15] = 0x680;
            return;
        }

        // Загрузчик обнуляет 0x3001a098..0x3001dbb4 — образ ОС кладём после этого.
        if (addr == 0x660 && afterZeroFill != null) { afterZeroFill.run(); afterZeroFill = null; }
        int bits = (insn >>> 25) & 7;
        if (trace && (traceAll || instructions <= 60)) {
            System.out.printf("%08x: %08x%n", addr, insn);
        }

        // Передачи полуслов/знаковых байт (LDRH/STRH/LDRSB/LDRSH) тоже имеют bits 27-25=000,
        // поэтому их надо распознать ДО data-processing, иначе они молча игнорируются.
        if (bits == 0 && (insn & 0x0E000090) == 0x00000090) {
            boolean p = bit(insn, 24), u = bit(insn, 23), i = bit(insn, 22);
            boolean w = bit(insn, 21), l = bit(insn, 20);
            boolean signed = bit(insn, 6), half = bit(insn, 5);
            int rn = (insn >>> 16) & 0xF, rd = (insn >>> 12) & 0xF;
            int base = readReg(rn);
            int offset = i ? (insn & 0xF) : shiftedRegister(insn);
            if (!u) offset = -offset;
            int address = p ? base + offset : base;
            int wb = base + offset;
            if (l) {
                int value;
                if (signed) value = half ? (short) rd16(address) : (byte) rd8(address);
                else value = rd16(address);
                writeReg(rd, value);
            } else wr16(address, readReg(rd) & 0xFFFF);
            if ((p && w) || !p) writeReg(rn, p ? address : wb);
            return;
        }

        // BX / BLX и SWI проверяются раньше data-processing (биты 27-25 тоже 000).
        if ((insn & 0x0FFFFFF0) == 0x012FFF10 || (insn & 0x0FFFFFF0) == 0x012FFF30) { // BX / BLX reg
            boolean link = (insn & 0x0FFFFFF0) == 0x012FFF30;
            if (link) r[14] = addr + 4;
            int target = readReg(insn & 0xF);
            if ((target & 1) != 0) { cpsr |= 0x20; r[15] = target & ~1; }
            else { cpsr &= ~0x20; r[15] = target & 0xFFFFFFFC; }
            return;
        }
        if ((insn & 0x0F000000) == 0x0F000000) { // SWI
            raiseSVC();
            return;
        }
        if ((insn & 0x0FFF0FFF) == 0x010F0000) { // MRS
            r[(insn >>> 12) & 0xF] = cpsr;
            return;
        }
        if ((insn & 0x0FB0FFF0) == 0x0120F000) { // MSR cpsr_<fields>, reg
            switchMode(readReg(insn & 0xF));
            return;
        }
        if ((insn & 0x0F000000) == 0x0E000000 && ((insn >>> 8) & 0xF) == 0xF) { // MCR/MRC CP15
            int crn = (insn >>> 16) & 0xF;              // CRn: биты 19-16
            int rd = (insn >>> 12) & 0xF;               // Rd: биты 15-12
            if (bit(insn, 20)) r[rd] = cp15[crn];       // MRC
            else {
                cp15[crn] = readReg(rd);                 // MCR
                if (crn == 1 || crn == 2 || crn == 3 || crn == 7 || crn == 8) tlbVaTag = -1;
            }
            return;
        }

        // --- data processing / multiply (bits 27-25 = 000 or 001) ---
        if (bits == 0 || bits == 1) {
            int opcode = (insn >>> 21) & 0xF;   // биты 24-21; бит 20 — S
            boolean s = bit(insn, 20);
            boolean multiply = (insn & 0x0F000090) == 0x00000090;
            if (multiply) {
                int rd = (insn >>> 16) & 0xF, ra = (insn >>> 12) & 0xF;
                int rm = insn & 0xF, rs = (insn >>> 8) & 0xF;
                int res = readReg(rm) * readReg(rs);
                if (bit(insn, 21)) res += readReg(ra);
                writeReg(rd, res);
                if (s) setNZ(res);
                return;
            }
            if ((insn & 0x0FFFFFF0) == 0x010F0000 || (insn & 0x0FFF0FFF) == 0x010F0000) return; // MRS-ish
            if ((insn & 0x0FBF0FFF) == 0x010F0000) return; // MSR-ish
            int rn = (insn >>> 16) & 0xF;
            int rd = (insn >>> 12) & 0xF;
            int a = readReg(rn), b = operand2(insn);
            if (opcode >= 8 && opcode <= 11) { alu(opcode, a, b, true); return; }
            int res = alu(opcode, a, b, s);
            if (s) setNZ(res);
            writeReg(rd, res);
            if (rd == 15 && s) {
                cpsr = spsr;
                if ((cpsr & 0x20) != 0) r[15] &= ~1; else r[15] &= 0xFFFFFFFC;
            }
            return;
        }

        // --- load / store ---
        if (bits == 2 || bits == 3) { // bits=3 — регистровый офсет (I=1)
            boolean p = bit(insn, 24), u = bit(insn, 23), i = bit(insn, 25);
            boolean w = bit(insn, 21), l = bit(insn, 20), b = bit(insn, 22);
            boolean halfword = (insn & 0x090000F0) == 0x00000090 || (insn & 0x090000F0) == 0x000000B0;
            int rn = (insn >>> 16) & 0xF, rd = (insn >>> 12) & 0xF;
            int base = readReg(rn);
            int offset = i ? shiftedRegister(insn) : (insn & 0xFFF);
            if (!u) offset = -offset;
            int address = p ? base + offset : base;
            int wb = base + offset;
            if (halfword) {
                int val = l ? rd16(address) : readReg(rd) & 0xFFFF;
                if (l) writeReg(rd, val); else wr16(address, val);
            } else if (b) {
                if (l) writeReg(rd, rd8(address)); else wr8(address, readReg(rd) & 0xFF);
            } else {
                if (l) {
                    int v = rd32(address);
                    if (rd == 15) {
                        if ((v & 1) != 0) { cpsr |= 0x20; r[15] = v & ~1; }
                        else { cpsr &= ~0x20; r[15] = v & ~3; }
                    } else writeReg(rd, v);
                } else wr32(address, readReg(rd));
            }
            if (p && w) writeReg(rn, wb);
            if (!p) writeReg(rn, wb);
            return;
        }

        // --- branch / block transfer ---
        if (bits == 4 || bits == 5) {
            // LDRD / STRD (паровая загрузка 64 бит)
            if ((insn & 0x0F4000F0) == 0x014000D0) {
                int rn = (insn >>> 16) & 0xF, rt = (insn >>> 12) & 0xF;
                int off = (((insn >>> 8) & 0xF) << 4) | (insn & 0xF);
                if (!bit(insn, 23)) off = -off;
                int a = readReg(rn) + off;
                boolean load = !bit(insn, 21); // в этой кодировке LDRD имеет W=1
                if (load) { r[rt] = rd32(a); r[rt + 1] = rd32(a + 4); }
                else { wr32(a, r[rt]); wr32(a + 4, r[rt + 1]); }
                if (bit(insn, 21)) writeReg(rn, a);
                return;
            }
            if ((insn & 0x0E000000) == 0x08000000) { // LDM/STM
                boolean p = bit(insn, 24), u = bit(insn, 23), w = bit(insn, 21), l = bit(insn, 20);
                int rn = (insn >>> 16) & 0xF, list = insn & 0xFFFF;
                boolean restoreSpsr = bit(insn, 22); // '^': CPSR := SPSR
                int base = readReg(rn);
                int n = Integer.bitCount(list);
                // ARM LDM/STM: P/U задают стартовый адрес (IA/IB/DA/DB)
                int a = u ? base + (p ? 4 : 0) : base - 4 * n + (p ? 0 : 4);
                for (int i = 0; i < 16; i++) if (bit(list, i)) {
                    if (l) {
                        int v = rd32(a);
                        // ARMv5 LDM/pop: бит 0 загруженного PC переключает ARM/Thumb.
                        if (i == 15) {
                            if ((v & 1) != 0) { cpsr |= 0x20; r[15] = v & ~1; }
                            else { cpsr &= ~0x20; r[15] = v & 0xFFFFFFFC; }
                        } else r[i] = v;
                    } else wr32(a, r[i]);
                    a += 4;
                }
                if (l && restoreSpsr) cpsr = spsr;
                if (w) writeReg(rn, u ? base + 4 * n : base - 4 * n);
                return;
            }
            // ARM BLX immediate: cond 101 H imm24 — переход в Thumb.
            if ((insn & 0xFE000000) == 0xFA000000) {
                int imm24 = insn & 0xFFFFFF;
                if ((imm24 & 0x800000) != 0) imm24 |= 0xFF000000;
                int off = (imm24 << 2) | (((insn >>> 24) & 1) << 1);
                r[14] = addr + 4;
                cpsr |= 0x20;
                r[15] = (addr + 8 + off) & ~1;
                return;
            }
            boolean link = bit(insn, 24);
            int off = (insn << 8) >> 6;   // знаковое расширение imm24<<2
            if (link) r[14] = addr + 4;
            r[15] = r[15] + 4 + off;
            return;
        }

        // Копроцессорные команды к отсутствующим CP — no-op (ARMv5eterminate).
        if ((insn & 0x0E000000) == 0x0C000000 || (insn & 0x0F000000) == 0x0F000000) return;
        // Неизвестные ARM-опкоды НЕ должны молча игнорироваться — это маскирует баги декодера.
        halted = true;
        haltReason = "Unhandled ARM opcode 0x" + Integer.toHexString(insn) + " at 0x" + Integer.toHexString(addr);
    }

    /** Thumb-16 interpreter (needed as soon as BX/BLX sets the T bit). */
    private void stepThumb(boolean trace) {
        int addr = r[15] & ~1;
        int insn = rd16(addr);
        if (insn == 0) { if (++zeroRun > 500) { halted = true; haltReason = "unmapped Thumb memory at " + Integer.toHexString(addr); } }
        else zeroRun = 0;
        r[15] = addr + 2;
        instructions++;
        if (trace && (traceAll || instructions <= 60)) System.out.printf("%08x: %04x (T)%n", addr, insn);

        if ((cpsr & 0x1F) != 0x12 && (insn & 0xF800) == 0xE000) {
            int off = (insn & 0x7FF) << 1;
            if ((off & 0x800) != 0) off |= 0xFFFFF000;
            if (addr + 4 + off == addr && irqCount < irqLimit) { irqCount++; raiseIRQ(addr + 2); return; }
        }
        int top = insn >>> 12;
        int rd, rm, rn, imm, res;

        if (insn < 0x1800) { // LSL/LSR/ASR immediate: 0x0000..0x17FF; 0x1800+ это ADD/SUB
            // 00000 imm5 Rm Rd = LSLS; 01000... = LSRS; 10000... = ASRS
            int type = insn < 0x0800 ? 0 : insn < 0x1000 ? 1 : 2;
            imm = (insn >>> 6) & 0x1F;
            rm = (insn >>> 3) & 7;
            rd = insn & 7;
            res = shift(readReg(rm), type, type == 0 ? imm : (imm == 0 ? 32 : imm));
            setNZ(res); setC(shifterCarry != 0);
            r[rd] = res;
            return;
        }
        if (top == 1) { // ADDS/SUBS: bits[10:9] 00=ADD reg, 01=SUB reg, 10=ADD imm3, 11=SUB imm3
            imm = (insn >>> 6) & 7;
            int kind = (insn >>> 9) & 3;
            if (kind < 2) imm = readReg(imm);   // register form
            rn = (insn >>> 3) & 7; rd = insn & 7;
            res = (kind & 1) == 0 ? alu(4, readReg(rn), imm, true) : alu(2, readReg(rn), imm, true);
            setNZ(res); r[rd] = res;
            return;
        }
        if (top == 2 || top == 3) { // MOV/CMP/ADD/SUB #imm8
            int op = (insn >>> 11) & 3;
            rd = (insn >>> 8) & 7; imm = insn & 0xFF;
            if (op == 0) { r[rd] = imm; setNZ(imm); }
            else if (op == 1) { alu(10, readReg(rd), imm, true); }
            else { res = alu(op == 2 ? 4 : 2, readReg(rd), imm, true); setNZ(res); r[rd] = res; }
            return;
        }
        if ((insn >>> 10) == 0x10) { // ALU register
            int op = (insn >>> 6) & 0xF;
            rm = (insn >>> 3) & 7; rd = insn & 7;
            int a = readReg(rd), b = readReg(rm);
            switch (op) {
                case 0: case 1: case 5: case 6: case 8: case 10: case 11: case 12: case 14:
                    res = alu(op == 8 ? 8 : op, a, b, true); setNZ(res);
                    if (op != 8 && op != 10 && op != 11) r[rd] = res;
                    return;
                case 2: case 3: case 4: case 7: {
                    int type = op == 2 ? 0 : op == 3 ? 1 : op == 4 ? 2 : 3;
                    res = shift(a, type, b & 0xFF); setNZ(res); setC(shifterCarry != 0); r[rd] = res;
                    return;
                }
                case 9: res = alu(3, a, b, true); setNZ(res); r[rd] = res; return; // RSB #0
                case 13: r[rd] = a * b; setNZ(r[rd]); return;
                case 15: res = ~b; setNZ(res); r[rd] = res; return;
            }
        }
        if ((insn >>> 10) == 0x11) { // special data / BX / BLX
            int op = (insn >>> 8) & 3;
            rd = (insn & 7) | ((insn >>> 4) & 8);
            rm = (insn >>> 3) & 0xF;
            if (op == 0) { r[rd] += readReg(rm); return; }
            if (op == 1) { alu(10, readReg(rd), readReg(rm), true); return; }
            if (op == 2) { r[rd] = readReg(rm); return; }
            int target = readReg(rm);
            if ((insn & 0x80) != 0) { // BLX Rm — switч по целевому адресу
                r[14] = (addr + 2) | 1;
                if ((target & 1) != 0) { cpsr |= 0x20; r[15] = target & ~1; }
                else { cpsr &= ~0x20; r[15] = target & ~3; }
            } else {
                if ((target & 1) != 0) cpsr |= 0x20; else cpsr &= ~0x20;
                r[15] = target & ~1;
            }
            return;
        }
        // Thumb LDR literal: 01001 Rt Imm8 (0x48xx/0x49xx), НЕ top==5!
        if ((insn & 0xF800) == 0x4800) {
            rd = (insn >>> 8) & 7;
            int base = (addr + 4) & ~3;
            r[rd] = rd32(base + ((insn & 0xFF) << 2));
            return;
        }
        if (top == 5) { // 0x5xxx: LDR/STR/LDRB/STRB/LDRH/STRH/LDRSB/LDRSH register offset
            // opcode = bits[11:9]: 0 STR, 1 STRH, 2 STRB, 3 LDRSB, 4 LDR, 5 LDRH, 6 LDRB, 7 LDRSH
            rm = (insn >>> 6) & 7; rn = (insn >>> 3) & 7; rd = insn & 7;
            int a = readReg(rn) + readReg(rm);
            switch ((insn >>> 9) & 7) {
                case 0: wr32(a, readReg(rd)); break;
                case 1: wr16(a, readReg(rd) & 0xFFFF); break;
                case 2: wr8(a, readReg(rd) & 0xFF); break;
                case 3: r[rd] = (byte) rd8(a); break;          // LDRSB
                case 4: r[rd] = rd32(a); break;
                case 5: r[rd] = rd16(a); break;                // LDRH
                case 6: r[rd] = rd8(a); break;                 // LDRB
                case 7: r[rd] = (short) rd16(a); break;        // LDRSH
            }
            return;
        }
        if (top == 6 || top == 7 || top == 8) { // imm5 load/store word/byte/halfword
            rd = insn & 7; rn = (insn >>> 3) & 7;
            imm = (insn >>> 6) & 0x1F;
            int a = readReg(rn) + (top == 6 ? imm << 2 : top == 8 ? imm << 1 : imm);
            if (top == 6) { if ((insn & 0x0800) != 0) r[rd] = rd32(a); else wr32(a, readReg(rd)); }
            else if (top == 7) { if ((insn & 0x0800) != 0) r[rd] = rd8(a); else wr8(a, readReg(rd) & 0xFF); }
            else { if ((insn & 0x0800) != 0) r[rd] = rd16(a); else wr16(a, readReg(rd) & 0xFFFF); }
            return;
        }
        if (top == 9) {
            rd = (insn >>> 8) & 7;
            int a = readReg(13) + ((insn & 0xFF) << 2);
            if ((insn & 0x0800) != 0) r[rd] = rd32(a); else wr32(a, readReg(rd));
            return;
        }
        if ((top == 0xA || top == 0xB)
                && (insn & 0xFE00) != 0xB400 && (insn & 0xFE00) != 0xBC00
                && (insn & 0xFF00) != 0xB000) { // ADD Rd,SP/PC,#imm (0xB0xx — это SUB/ADD SP)
            rd = (insn >>> 8) & 7;
            imm = (insn & 0xFF) << 2;
            // bit11=0: ADD Rd,PC,#imm ; bit11=1: ADD Rd,SP,#imm
            int baseReg = ((insn & 0x0800) != 0) ? 13 : 15;
            int base = baseReg == 15 ? (addr + 4) & ~3 : readReg(13);
            r[rd] = base + imm;
            return;
        }
        if ((insn & 0xFF00) == 0xB000) {
            imm = (insn & 0x7F) << 2;
            r[13] += ((insn & 0x80) != 0) ? -imm : imm;
            return;
        }
        if ((insn & 0xFE00) == 0xB400 || (insn & 0xFE00) == 0xBC00) { // PUSH / POP
            boolean push = (insn & 0x0800) == 0;
            int list = insn & 0xFF;
            boolean extra = ((insn >>> 8) & 1) != 0; // LR for push, PC for pop
            int count = Integer.bitCount(list) + (extra ? 1 : 0);
            if (push) {
                r[13] -= 4 * count;
                int a = r[13];
                for (int i = 0; i <= 7; i++) if ((list & (1 << i)) != 0) { wr32(a, r[i]); a += 4; }
                if (extra) wr32(a, r[14]);
            } else {
                int a = r[13];
                for (int i = 0; i <= 7; i++) if ((list & (1 << i)) != 0) { r[i] = rd32(a); a += 4; }
                if (extra) {
                    int newpc = rd32(a);
                    // Thumb POP{pc}: бит 0 загруженного адреса переключает состояние.
                    if ((newpc & 1) != 0) { cpsr |= 0x20; r[15] = newpc & ~1; }
                    else { cpsr &= ~0x20; r[15] = newpc & ~3; }
                }
                r[13] += 4 * count;
            }
            return;
        }
        if (top == 0xC) { // STM (1100 0) / LDM (1100 1)
            boolean ldm = ((insn >>> 11) & 1) != 0;
            rn = (insn >>> 8) & 7;
            int list = insn & 0xFF;
            int a = readReg(rn);
            for (int i = 0; i <= 7; i++) if ((list & (1 << i)) != 0) {
                if (ldm) { r[i] = rd32(a); a += 4; }
                else { wr32(a, r[i]); a += 4; }
            }
            writeReg(rn, a);
            return;
        }
        if (top == 0xD && ((insn >>> 8) & 0xF) < 0xE) { // B<cond>
            int c = (insn >>> 8) & 0xF;
            imm = (insn & 0xFF) << 1;
            if ((imm & 0x100) != 0) imm |= 0xFFFFFE00;
            // Базой ветвления Thumb является PC = адрес+4 (уже addr+2 в r15).
            if (cond(c)) r[15] += imm + 2;
            return;
        }
        if (top == 0xD && ((insn >>> 8) & 0xF) == 0xF) { // SWI
            raiseSVC();
            return;
        }
        if ((insn & 0xF800) == 0xE000) { // B
            imm = (insn & 0x7FF) << 1;
            if ((imm & 0x800) != 0) imm |= 0xFFFFF000;
            r[15] += imm + 2;
            return;
        }
        if ((insn & 0xF800) == 0xE800) { // BLX immediate — ARM target
            int base = thumbBlAddr + 4 + ((thumbBlInsn << 21) >> 9);
            int target = (base + ((insn & 0x7FF) << 1)) & ~3;
            r[14] = (addr + 2) | 1;
            cpsr &= ~0x20;
            r[15] = target;
            return;
        }
        if ((insn & 0xF800) == 0xF000) { // BL, первая половина (11110 S imm10)
            thumbBlInsn = insn;
            thumbBlAddr = addr;
            return;
        }
        if ((insn & 0xF800) == 0xF800) { // BL, вторая половина
            int s2 = (thumbBlInsn >>> 10) & 1;
            int i1 = (((insn >>> 13) & 1) ^ s2) ^ 1;
            int i2 = (((insn >>> 11) & 1) ^ s2) ^ 1;
            int imm32 = (s2 << 24) | (i1 << 23) | (i2 << 22)
                  | ((thumbBlInsn & 0x3FF) << 12) | ((insn & 0x7FF) << 1);
            if (s2 != 0) imm32 |= 0xFE000000;
            int target = thumbBlAddr + 4 + imm32;
            r[14] = (addr + 2) | 1;
            cpsr |= 0x20;
            r[15] = target & ~1;
            return;
        }
    }

    public String dumpRegs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(String.format("r%-2d=%08x  ", i, r[i]));
        sb.append(String.format("%nCPSR=%08x instructions=%d", cpsr, instructions));
        sb.append(String.format("%nCP15: c1=%08x c2(TTBR)=%08x c3=%08x", cp15[1], cp15[2], cp15[3]));
        return sb.toString();
    }
}
