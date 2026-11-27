//! ARM926EJ-S (ARMv5TE) integer interpreter — точный порт ARM.java.
use crate::memory::Memory;

#[inline] fn bit(v: u32, n: u32) -> bool { ((v >> n) & 1) != 0 }
#[inline] fn sx8(v: u32) -> i32 { (v as i8) as i32 }
#[inline] fn sx16(v: u32) -> i32 { (v as i16) as i32 }

pub struct Arm<'a> {
    pub r: [u32; 16],
    pub cpsr: u32,
    pub spsr: u32,
    pub mem: &'a mut Memory,
    pub instructions: u64,
    pub halted: bool,
    pub halt_reason: String,
    pub cp15: [u32; 16],
    pub banked: [[u32; 2]; 32],
    pub fiq: [u32; 5],
    pub itcm_reg: u32,
    pub dtcm_reg: u32,
    shifter_carry: u32,
    thumb_bl_insn: u32,
    thumb_bl_addr: u32,
    thumb_bl_pending: bool,
    irq_count: u32,
    pub irq_limit: u32,
    pub zero_run: i32,
    pub msr_dbg: u32,
    pub bad_call_returns: u64,
    pub trace_all: bool,
    pub wfi_count: u64,
    pub irq_open_steps: u64,
    pub data_aborts: u64,
    pub prefetch_aborts: u64,
    pub strict_aborts: bool,
    pub after_zero_fill: bool,
    tlb_va_tag: i64,
    tlb_pa_base: u32,
    tlb_mask: u32,
    tlb_cache: Vec<(u32, u32)>, // direct-mapped, 256 entries, tag = va & !0xFFF
    fetch_pc: u32, fetch_pa: u32, fetch_insn: u32,
}

impl<'a> Arm<'a> {
    pub fn new(mem: &'a mut Memory) -> Self {
        let mut a = Self {
            r: [0; 16], cpsr: 0xD3, spsr: 0, mem, instructions: 0, halted: false,
            halt_reason: String::new(), cp15: [0; 16], banked: [[0; 2]; 32], fiq: [0; 5],
            itcm_reg: 0, dtcm_reg: 0, shifter_carry: 0, thumb_bl_insn: 0, thumb_bl_addr: 0,
            thumb_bl_pending: false, irq_count: 0, irq_limit: 3, zero_run: 0, msr_dbg: 0, bad_call_returns: 0,
            trace_all: false, wfi_count: 0, irq_open_steps: 0, data_aborts: 0,
            prefetch_aborts: 0, strict_aborts: false, after_zero_fill: false,
            tlb_va_tag: -1, tlb_pa_base: 0, tlb_mask: 0xFFF,
            tlb_cache: vec![(0xFFFF_FFFF, 0); 256],
            fetch_pc: 0xFFFF_FFFF, fetch_pa: 0, fetch_insn: 0,
        };
        a.cp15[0] = 0x4106_9265;   // Main ID: ARM926EJ-S rev 5
        a.r[13] = 0x4000_1000;
        a.banked[1][0] = 0x4000_0E00;
        a.banked[2][0] = 0x4000_0F00;
        a.banked[3][0] = 0x4000_0D00;
        a
    }

    #[inline] pub fn cond(&self, c: u32) -> bool {
        let n = bit(self.cpsr, 31); let z = bit(self.cpsr, 30);
        let cflag = bit(self.cpsr, 29); let v = bit(self.cpsr, 28);
        match c {
            0 => z, 1 => !z, 2 => cflag, 3 => !cflag,
            4 => n, 5 => !n, 6 => v, 7 => !v,
            8 => cflag && !z, 9 => !cflag || z, 10 => n == v, 11 => n != v,
            12 => !z && n == v, 13 => z || n != v, 15 => false, _ => true,
        }
    }

    #[inline] fn read_reg(&self, i: u32) -> u32 {
        if i == 15 { self.r[15].wrapping_add(4) } else { self.r[i as usize] }
    }
    #[inline] fn write_reg(&mut self, i: u32, v: u32) {
        if i == 15 { self.r[15] = v & 0xFFFF_FFFC; } else { self.r[i as usize] = v; }
    }
    #[inline] fn set_nz(&mut self, v: u32) {
        if bit(v, 31) { self.cpsr |= 1 << 31; } else { self.cpsr &= !(1 << 31); }
        if v == 0 { self.cpsr |= 1 << 30; } else { self.cpsr &= !(1 << 30); }
    }
    #[inline] fn set_c(&mut self, c: bool) { if c { self.cpsr |= 1 << 29; } else { self.cpsr &= !(1 << 29); } }
    #[inline] fn set_v(&mut self, v: bool) { if v { self.cpsr |= 1 << 28; } else { self.cpsr &= !(1 << 28); } }

    fn bank_index(mode: u32) -> usize {
        match mode & 0x1F {
            0x10 | 0x1F => 0, 0x11 => 1, 0x12 => 2,
            0x13 => 3, 0x17 => 4, 0x1B => 5, _ => 6,
        }
    }

    fn switch_mode(&mut self, new_mode: u32) {
        let old_mode = self.cpsr & 0x1F;
        let old = Self::bank_index(old_mode);
        self.banked[old][0] = self.r[13];
        self.banked[old][1] = self.r[14];
        if old_mode == 0x11 { for k in 0..5 { self.fiq[k] = self.r[8 + k]; } }
        self.cpsr = (self.cpsr & 0xF800_00E0) | (new_mode & 0x1F);
        let nw = Self::bank_index(new_mode);
        self.r[13] = self.banked[nw][0];
        self.r[14] = self.banked[nw][1];
        if new_mode & 0x1F == 0x11 { for k in 0..5 { self.r[8 + k] = self.fiq[k]; } }
    }

    // MSR cpsr_c: bil stays in the control field (I/F) must be writable.
    fn msr_control(&mut self, v: u32) {
        let old_mode = self.cpsr & 0x1F;
        let old = Self::bank_index(old_mode);
        self.banked[old][0] = self.r[13];
        self.banked[old][1] = self.r[14];
        if old_mode == 0x11 { for k in 0..5 { self.fiq[k] = self.r[8 + k]; } }
        let t = self.cpsr & 0x20;
        self.cpsr = (self.cpsr & 0xFFFF_FF00) | (v & 0xC0) | t | (v & 0x1F);
        let nm = self.cpsr & 0x1F;
        let nw = Self::bank_index(nm);
        self.r[13] = self.banked[nw][0];
        self.r[14] = self.banked[nw][1];
        if nm == 0x11 { for k in 0..5 { self.r[8 + k] = self.fiq[k]; } }
    }

    // Эмуляция повреждённого функционального указателя: если переход
    // попал в данные/несуществующий код, возвращаемся по LR как из callback.
    // Повреждённый функциональный указатель/данные вместо кода:
    // пропускаем текущее слово и продолжаем линейно.
    // Раньше здесь был «пропуск неизвестных слов». Теперь любой выход
    // в данные/несуществующий код — мгновенный abort.
    fn recover_bad_call(&mut self) -> bool {
        self.bad_call_returns += 1;
        false
    }

    fn raise_svc(&mut self) {
        let ret = self.r[15];
        self.spsr = self.cpsr;
        self.switch_mode(0x13);
        self.cpsr |= 0x80;
        self.cpsr &= !0x20;
        self.r[14] = ret;
        let base: u32 = if bit(self.cp15[1], 13) { 0xFFFF_0000 } else { 0 };
        let pa = self.translate(base.wrapping_add(8));
        self.r[15] = pa & 0xFFFF_FFFC;
    }

    fn raise_irq(&mut self, return_addr: u32) {
        self.spsr = self.cpsr;
        self.switch_mode(0x12);
        self.cpsr |= 0x80;
        self.cpsr &= !0x20;
        self.r[14] = return_addr;
        let vbase: u32 = if bit(self.cp15[1], 13) { 0xFFFF_0000 } else { 0 };
        self.r[15] = vbase.wrapping_add(0x18);
        if self.irq_count < self.irq_limit {
            self.irq_count += 1;
        }
    }

    fn raise_abort(&mut self, data: bool) {
        if data { self.data_aborts += 1; } else { self.prefetch_aborts += 1; }
        if !self.strict_aborts { return; }
        self.spsr = self.cpsr;
        self.switch_mode(0x17);
        self.cpsr |= 0x80;
        self.cpsr &= !0x20;
        self.r[14] = self.r[15].wrapping_add(if data { 4 } else { 4 });
        let vbase: u32 = if bit(self.cp15[1], 13) { 0xFFFF_0000 } else { 0 };
        self.r[15] = vbase.wrapping_add(if data { 0x10 } else { 0x0C });
    }

    fn translate(&mut self, va: u32) -> u32 {
        if bit(self.itcm_reg, 0) && va < crate::memory::TCM_SIZE { return va; }
        if bit(self.dtcm_reg, 0) {
            let base = self.dtcm_reg & 0xFFFF_F000;
            let size = 1u32 << (((self.dtcm_reg >> 2) & 0x1F) + 9);
            if va >= base && va < base.wrapping_add(size) { return va; }
        }
        if !bit(self.cp15[1], 0) { return va; }
        let tag = va & !0xFFF;
        let idx = ((va >> 12) & 0xFF) as usize;
        let (ctag, cpa) = self.tlb_cache[idx];
        if ctag == tag { return cpa | (va & 0xFFF); }
        if (va as i64 & !(self.tlb_mask as i64)) == self.tlb_va_tag {
            return self.tlb_pa_base | (va & self.tlb_mask);
        }
        let ttbr = self.cp15[2] & 0xFFFF_C000;
        let l1 = self.mem.read32(ttbr.wrapping_add((va >> 20) << 2));
        let typ = l1 & 3;
        if typ == 2 {
            let pa = (l1 & 0xFFF0_0000) | (va & 0x000F_FFFF);
            self.tlb_cache[idx] = (tag, pa & !0xFFF);
            return pa;
        }
        if typ == 1 {
            let l2base = l1 & 0xFFFF_F000;
            let l2 = self.mem.read32(l2base.wrapping_add(((va >> 12) & 0xFF) << 2));
            let t = l2 & 3;
            if t == 2 {
                self.tlb_va_tag = (va & !0xFFF) as i64;
                self.tlb_pa_base = l2 & 0xFFFF_F000;
                self.tlb_mask = 0xFFF;
                self.tlb_cache[idx] = (tag, self.tlb_pa_base);
                return self.tlb_pa_base | (va & 0xFFF);
            }
            if t == 1 {
                self.tlb_va_tag = (va & !0xFFFF) as i64;
                self.tlb_pa_base = l2 & 0xFFFF_0000;
                self.tlb_mask = 0xFFFF;
                self.tlb_cache[idx] = (tag, self.tlb_pa_base | (va & 0xF000));
                return self.tlb_pa_base | (va & 0xFFFF);
            }
        }
        if self.strict_aborts { self.raise_abort(bit(l1, 10)); }
        va
    }

    pub fn mmu_debug(&mut self, va: u32) -> (u32, u32, u32, u32, u32, u32) {
        let ttbr = self.cp15[2] & 0xFFFF_C000;
        let l1a = ttbr.wrapping_add((va >> 20) << 2);
        let l1 = self.mem.read32(l1a);
        let mut l2a = 0u32; let mut l2 = 0u32; let mut pa = va;
        let typ = l1 & 3;
        if typ == 2 {
            pa = (l1 & 0xFFF0_0000) | (va & 0x000F_FFFF);
        } else if typ == 1 {
            let l2b = l1 & 0xFFFF_F000;
            l2a = l2b.wrapping_add(((va >> 12) & 0xFF) << 2);
            l2 = self.mem.read32(l2a);
            let t = l2 & 3;
            if t == 2 { pa = (l2 & 0xFFFF_F000) | (va & 0xFFF); }
            else if t == 1 { pa = (l2 & 0xFFFF_0000) | (va & 0xFFFF); }
        }
        (ttbr, l1a, l1, l2a, l2, pa)
    }

    pub fn va2pa(&mut self, va: u32) -> u32 { self.translate(va) }

    #[inline] fn rd32(&mut self, a: u32) -> u32 {
        let pa = self.translate(a & !3);
        let w = self.mem.read32(pa);
        if a & 3 == 0 { w } else { w.rotate_right((a & 3) * 8) }
    }
    #[inline] fn wr32(&mut self, a: u32, v: u32) {
        let w = if a & 3 == 0 { v } else { v.rotate_left((a & 3) * 8) };
        let pa = self.translate(a & !3);
        let tt = self.cp15[2] & 0xFFFF_C000;
        if pa >= tt && pa < tt.wrapping_add(0x1_0000) {
            for e in self.tlb_cache.iter_mut() { *e = (0xFFFF_FFFF, 0); }
            self.tlb_va_tag = -1;
        }
        if pa == self.fetch_pa { self.fetch_pc = 0xFFFF_FFFF; }
        self.mem.write32(pa, w);
    }
    #[inline] fn rd16(&mut self, a: u32) -> u32 { let pa = self.translate(a); self.mem.read16(pa) }
    #[inline] fn wr16(&mut self, a: u32, v: u32) { let pa = self.translate(a); self.mem.write16(pa, v) }
    #[inline] fn rd8(&mut self, a: u32) -> u32 { let pa = self.translate(a); self.mem.read8(pa) }
    #[inline] fn wr8(&mut self, a: u32, v: u32) { let pa = self.translate(a); self.mem.write8(pa, v) }

    fn shift(&mut self, value: u32, typ: u32, amount: u32) -> u32 {
        self.shifter_carry = bit(self.cpsr, 29) as u32;
        if amount == 0 { return value; }
        match typ {
            0 => {
                if amount >= 32 {
                    if amount == 32 { self.shifter_carry = value & 1; } else { self.shifter_carry = 0; }
                    return 0;
                }
                self.shifter_carry = (value >> (32 - amount)) & 1;
                value.wrapping_shl(amount)
            }
            1 => {
                if amount >= 32 {
                    if amount == 32 { self.shifter_carry = (value >> 31) & 1; } else { self.shifter_carry = 0; }
                    return 0;
                }
                self.shifter_carry = (value >> (amount - 1)) & 1;
                value >> amount
            }
            2 => {
                if amount >= 32 {
                    self.shifter_carry = if bit(value, 31) { 1 } else { 0 };
                    return if bit(value, 31) { 0xFFFF_FFFF } else { 0 };
                }
                self.shifter_carry = (value >> (amount - 1)) & 1;
                ((value as i32) >> amount) as u32
            }
            _ => {
                let a = amount & 31;
                if a == 0 { self.shifter_carry = (value >> 31) & 1; return value; }
                self.shifter_carry = (value >> (a - 1)) & 1;
                value.rotate_right(a)
            }
        }
    }

    fn operand2(&mut self, insn: u32) -> u32 {
        if bit(insn, 25) {
            let imm = insn & 0xFF;
            let rot = ((insn >> 8) & 0xF) * 2;
            let v = imm.rotate_right(rot);
            self.shifter_carry = if rot == 0 { bit(self.cpsr, 29) as u32 } else { (v >> 31) & 1 };
            return v;
        }
        let rm = insn & 0xF;
        let value = self.read_reg(rm);
        let amount = if bit(insn, 4) { self.read_reg((insn >> 8) & 0xF) & 0xFF } else { (insn >> 7) & 0x1F };
        self.shift(value, (insn >> 5) & 3, amount)
    }

    fn alu(&mut self, op: u32, a: u32, b: u32, sf: bool) -> u32 {
        let mut res = 0u32;
        let c = bit(self.cpsr, 29) as u64;
        match op {
            0 => res = a & b,
            1 => res = a ^ b,
            2 => { let t = (a as u64).wrapping_sub(b as u64); res = t as u32;
                   if sf { self.set_c(t <= 0xFFFF_FFFF && t >= 0); self.set_v(((a ^ b) & (a ^ res)) >> 31 != 0); } }
            3 => { let t = (b as u64).wrapping_sub(a as u64); res = t as u32;
                   if sf { self.set_c(t <= 0xFFFF_FFFF && t >= 0); self.set_v(((b ^ a) & (b ^ res)) >> 31 != 0); } }
            4 => { let t = a as u64 + b as u64; res = t as u32;
                   if sf { self.set_c(t > 0xFFFF_FFFF); self.set_v((!(a ^ b) & (a ^ res)) >> 31 != 0); } }
            5 => { let t = a as u64 + b as u64 + c; res = t as u32;
                   if sf { self.set_c(t > 0xFFFF_FFFF); self.set_v((!(a ^ b) & (a ^ res)) >> 31 != 0); } }
            6 => { let t = (a as u64).wrapping_sub(b as u64).wrapping_add(c).wrapping_sub(1); res = t as u32;
                   if sf { self.set_c(t <= 0xFFFF_FFFF); self.set_v(((a ^ b) & (a ^ res)) >> 31 != 0); } }
            7 => { let t = (b as u64).wrapping_sub(a as u64).wrapping_add(c).wrapping_sub(1); res = t as u32;
                   if sf { self.set_c(t <= 0xFFFF_FFFF); self.set_v(((b ^ a) & (b ^ res)) >> 31 != 0); } }
            8 => { res = a & b; if sf { self.set_c(self.shifter_carry != 0); } }
            9 => { res = a ^ b; if sf { self.set_c(self.shifter_carry != 0); } }
            10 => { let t = (a as u64).wrapping_sub(b as u64); res = t as u32;
                    if sf { self.set_c(t <= 0xFFFF_FFFF); self.set_v(((a ^ b) & (a ^ res)) >> 31 != 0); } }
            11 => { let t = a as u64 + b as u64; res = t as u32;
                    if sf { self.set_c(t > 0xFFFF_FFFF); self.set_v((!(a ^ b) & (a ^ res)) >> 31 != 0); } }
            12 => res = a | b,
            13 => res = b,
            14 => res = a & !b,
            15 => res = !b,
            _ => {}
        }
        if sf && matches!(op, 0 | 1 | 12 | 13 | 14 | 15) { self.set_c(self.shifter_carry != 0); }
        // CMP/CMN/TST/TEQ обязаны обновлять N и Z (баг исправлен в Java-порте)
        if sf && matches!(op, 8 | 9 | 10 | 11) { self.set_nz(res); }
        res
    }

    fn shifted_register(&mut self, insn: u32) -> u32 {
        let rm = insn & 0xF;
        let value = self.read_reg(rm);
        let amount = if bit(insn, 4) { self.read_reg((insn >> 8) & 0xF) & 0xFF } else { (insn >> 7) & 0x1F };
        self.shift(value, (insn >> 5) & 3, amount)
    }

    pub fn inject_irq(&mut self) { if !self.halted { let pc = self.r[15]; self.raise_irq(pc); } }

    pub fn step(&mut self, trace: bool) {
        if self.halted { return; }
        // Жёсткий trap: PC вышел за валидные области кода.
        let cur = self.r[15];
        if !(cur < 0x4_0000 || (cur >= 0xC000_0000 && cur < 0xC120_0000)) {
            self.halted = true;
            self.halt_reason = format!("PC escaped code region at {:08x}", cur);
            return;
        }
        if bit(self.cpsr, 5) { self.step_thumb(trace); return; }
        let addr = self.r[15];
        let insn = if self.fetch_pc == addr { self.fetch_insn } else {
            let v = self.rd32(addr);
            self.fetch_pc = addr; self.fetch_pa = self.translate(addr & !3); self.fetch_insn = v;
            v
        };
        if insn == 0 { self.zero_run += 1; if self.zero_run > 500 {
            if !self.recover_bad_call() {
                self.halted = true; self.halt_reason = format!("unmapped memory at {:08x}", addr);
            }
        } } else { self.zero_run = 0; }
        self.r[15] = addr.wrapping_add(4);
        self.instructions += 1;
        if !bit(self.cpsr, 7) { self.irq_open_steps += 1; }

        if insn == 0xEAFF_FFFE && (self.cpsr & 0x1F) != 0x12 && self.irq_count < self.irq_limit {
            self.raise_irq(addr.wrapping_add(4)); return;
        }
        // self-loop с закрытыми прерываниями: состояние не меняется (горячий путь)
        if insn == 0xEAFF_FFFE && bit(self.cpsr, 7) { self.r[15] = addr; return; }
        let is_blx_imm = (insn & 0xFE00_0000) == 0xFA00_0000;
        if !is_blx_imm && !self.cond(insn >> 28) { return; }
        if addr == 0x660 && self.after_zero_fill { self.after_zero_fill = false; }

        let bits = (insn >> 25) & 7;
        if trace && (self.trace_all || self.instructions <= 60) {
            println!("{:08x}: {:08x}", addr, insn);
        }

        // LDRH/STRH/LDRSB/LDRSH (bits 27-25=000)
        if bits == 0 && (insn & 0x0E00_0090) == 0x0000_0090 {
            let p = bit(insn, 24); let u = bit(insn, 23); let i = bit(insn, 22);
            let w = bit(insn, 21); let l = bit(insn, 20);
            let signed = bit(insn, 6); let half = bit(insn, 5);
            let rn = (insn >> 16) & 0xF; let rd = (insn >> 12) & 0xF;
            let base = self.read_reg(rn);
            let mut offset = if i { insn & 0xF } else { self.shifted_register(insn) };
            if !u { offset = offset.wrapping_neg(); }
            let address = if p { base.wrapping_add(offset) } else { base };
            let wb = base.wrapping_add(offset);
            if l {
                let value = if signed {
                    if half { sx16(self.rd16(address)) as u32 } else { sx8(self.rd8(address)) as u32 }
                } else { self.rd16(address) };
                self.write_reg(rd, value);
            } else {
                let v = self.read_reg(rd) & 0xFFFF;
                self.wr16(address, v);
            }
            if (p && w) || !p { self.write_reg(rn, if p { address } else { wb }); }
            return;
        }

        // BX / BLX reg
        if (insn & 0x0FFF_FFF0) == 0x012F_FF10 || (insn & 0x0FFF_FFF0) == 0x012F_FF30 {
            let link = (insn & 0x0FFF_FFF0) == 0x012F_FF30;
            if link { self.r[14] = addr.wrapping_add(4); }
            let target = self.read_reg(insn & 0xF);
            if bit(target, 0) { self.cpsr |= 0x20; self.r[15] = target & !1; }
            else { self.cpsr &= !0x20; self.r[15] = target & 0xFFFF_FFFC; }
            return;
        }
        if (insn & 0x0F00_0000) == 0x0F00_0000 { self.raise_svc(); return; }
        if (insn & 0x0FFF_0FFF) == 0x010F_0000 { self.r[((insn >> 12) & 0xF) as usize] = self.cpsr; return; }
        if (insn & 0x0FB0_FFF0) == 0x0120_F000 {
            let v = self.read_reg(insn & 0xF);
            self.msr_control(v);
            return;
        }
        // MCR/MRC CP15
        if (insn & 0x0F00_0000) == 0x0E00_0000 && ((insn >> 8) & 0xF) == 0xF {
            let crn = (insn >> 16) & 0xF;
            let crm = insn & 0xF;
            let opc2 = (insn >> 5) & 7;
            let rd = (insn >> 12) & 0xF;
            if bit(insn, 20) {
                if crn == 9 && crm == 1 { self.r[rd as usize] = if opc2 == 1 { self.itcm_reg } else { self.dtcm_reg }; }
                else if crn == 0 && crm == 0 {
                    self.r[rd as usize] = match opc2 {
                        0 => 0x4106_9265,  // Main ID Register
                        1 => 0x1D11_2152,  // Cache Type Register
                        2 => 0x0001_0001,  // TCM Status Register
                        _ => self.cp15[0],
                    };
                }
                else { self.r[rd as usize] = self.cp15[crn as usize]; }
            } else {
                let v = self.read_reg(rd);
                if crn == 9 && crm == 1 {
                    if opc2 == 1 { self.itcm_reg = v; } else { self.dtcm_reg = v; }
                }
                self.cp15[crn as usize] = v;
                if crn == 7 && crm == 0 && opc2 == 4 {
                    self.wfi_count += 1;
                }
                if matches!(crn, 1 | 2 | 3 | 7 | 8) {
                    self.tlb_va_tag = -1;
                    for e in self.tlb_cache.iter_mut() { *e = (0xFFFF_FFFF, 0); }
                }
            }
            return;
        }

        if bits == 0 || bits == 1 {
            let opcode = (insn >> 21) & 0xF;
            let s = bit(insn, 20);
            let multiply = (insn & 0x0F00_0090) == 0x0000_0090;
            if multiply {
                let rd = (insn >> 16) & 0xF; let ra = (insn >> 12) & 0xF;
                let rm = insn & 0xF; let rs = (insn >> 8) & 0xF;
                let mut res = self.read_reg(rm).wrapping_mul(self.read_reg(rs));
                if bit(insn, 21) { res = res.wrapping_add(self.read_reg(ra)); }
                self.write_reg(rd, res);
                if s { self.set_nz(res); }
                return;
            }
            if (insn & 0x0FFF_0FFF) == 0x010F_0000 { return; }
            if (insn & 0x0FBF_0FFF) == 0x010F_0000 { return; }
            let rn = (insn >> 16) & 0xF; let rd = (insn >> 12) & 0xF;
            let a = self.read_reg(rn); let b = self.operand2(insn);
            if (8..=11).contains(&opcode) { self.alu(opcode, a, b, true); return; }
            let res = self.alu(opcode, a, b, s);
            if s { self.set_nz(res); }
            self.write_reg(rd, res);
            if rd == 15 && s {
                self.cpsr = self.spsr & 0xF800_00FF;
                if bit(self.cpsr, 5) { self.r[15] &= !1; } else { self.r[15] &= 0xFFFF_FFFC; }
            }
            return;
        }

        if bits == 2 || bits == 3 {
            let p = bit(insn, 24); let u = bit(insn, 23); let i = bit(insn, 25);
            let w = bit(insn, 21); let l = bit(insn, 20); let b = bit(insn, 22);
            let halfword = (insn & 0x0900_00F0) == 0x0000_0090 || (insn & 0x0900_00F0) == 0x0000_00B0;
            let rn = (insn >> 16) & 0xF; let rd = (insn >> 12) & 0xF;
            let base = self.read_reg(rn);
            let mut offset = if i { self.shifted_register(insn) } else { insn & 0xFFF };
            if !u { offset = offset.wrapping_neg(); }
            let address = if p { base.wrapping_add(offset) } else { base };
            let wb = base.wrapping_add(offset);
            if halfword {
                let val = if l { self.rd16(address) } else { self.read_reg(rd) & 0xFFFF };
                if l { self.write_reg(rd, val); } else { self.wr16(address, val); }
            } else if b {
                if l { let v = self.rd8(address); self.write_reg(rd, v); }
                else { let v = self.read_reg(rd) & 0xFF; self.wr8(address, v); }
            } else if l {
                let v = self.rd32(address);
                if rd == 15 {
                    if bit(v, 0) { self.cpsr |= 0x20; self.r[15] = v & !1; }
                    else { self.cpsr &= !0x20; self.r[15] = v & !3; }
                } else { self.write_reg(rd, v); }
            } else {
                let v = self.read_reg(rd); self.wr32(address, v);
            }
            if p && w { self.write_reg(rn, wb); }
            if !p { self.write_reg(rn, wb); }
            return;
        }

        if bits == 4 || bits == 5 {
            if (insn & 0x0F40_00F0) == 0x0140_00D0 {
                let rn = (insn >> 16) & 0xF; let rt = (insn >> 12) & 0xF;
                let mut off = (((insn >> 8) & 0xF) << 4) | (insn & 0xF);
                if !bit(insn, 23) { off = off.wrapping_neg(); }
                let a = self.read_reg(rn).wrapping_add(off);
                let load = !bit(insn, 21);
                if load {
                    let v0 = self.rd32(a); let v1 = self.rd32(a.wrapping_add(4));
                    self.r[rt as usize] = v0; self.r[(rt + 1) as usize] = v1;
                } else {
                    let v0 = self.r[rt as usize]; let v1 = self.r[(rt + 1) as usize];
                    self.wr32(a, v0); self.wr32(a.wrapping_add(4), v1);
                }
                if bit(insn, 21) { self.write_reg(rn, a); }
                return;
            }
            if (insn & 0x0E00_0000) == 0x0800_0000 {
                let p = bit(insn, 24); let u = bit(insn, 23); let w = bit(insn, 21); let l = bit(insn, 20);
                let rn = (insn >> 16) & 0xF; let list = insn & 0xFFFF;
                let restore_spsr = bit(insn, 22);
                let base = self.read_reg(rn);
                let n = list.count_ones();
                let mut a = if u {
                    base.wrapping_add(if p { 4 } else { 0 })
                } else {
                    base.wrapping_sub(4 * n).wrapping_add(if p { 0 } else { 4 })
                };
                for i in 0..16 {
                    if bit(list, i) {
                        if l {
                            let v = self.rd32(a);
                            if i == 15 {
                                if bit(v, 0) { self.cpsr |= 0x20; self.r[15] = v & !1; }
                                else { self.cpsr &= !0x20; self.r[15] = v & 0xFFFF_FFFC; }
                            } else { self.r[i as usize] = v; }
                        } else {
                            let v = self.r[i as usize]; self.wr32(a, v);
                        }
                        a = a.wrapping_add(4);
                    }
                }
                if l && restore_spsr { self.cpsr = self.spsr & 0xF800_00FF; }
                if w {
                    let wb = if u { base.wrapping_add(4 * n) } else { base.wrapping_sub(4 * n) };
                    self.write_reg(rn, wb);
                }
                return;
            }
            if (insn & 0xFE00_0000) == 0xFA00_0000 {
                let imm24 = insn & 0x00FF_FFFF;
                let imm24 = if bit(imm24, 23) { imm24 | 0xFF00_0000 } else { imm24 };
                let off = (imm24 << 2) | (((insn >> 24) & 1) << 1);
                self.r[14] = addr.wrapping_add(4);
                self.cpsr |= 0x20;
                self.r[15] = addr.wrapping_add(8).wrapping_add(off) & !1;
                return;
            }
            let link = bit(insn, 24);
            let off = (((insn << 8) as i32) >> 6) as u32;
            if link { self.r[14] = addr.wrapping_add(4); }
            self.r[15] = self.r[15].wrapping_add(4).wrapping_add(off);
            return;
        }

        if (insn & 0x0E00_0000) == 0x0C00_0000 || (insn & 0x0F00_0000) == 0x0F00_0000 { return; }
        self.bad_call_returns += 1;
        self.halted = true;
        self.halt_reason = format!("Unhandled ARM opcode {:08x} at {:08x}", insn, addr);
    }

    fn step_thumb(&mut self, trace: bool) {
        let addr = self.r[15] & !1;
        let insn = self.rd16(addr);
        let bl_was_pending = self.thumb_bl_pending;
        self.thumb_bl_pending = false;
        if insn == 0 { self.zero_run += 1; if self.zero_run > 500 {
            if !self.recover_bad_call() {
                self.halted = true; self.halt_reason = format!("unmapped Thumb memory at {:08x}", addr);
            }
        } } else { self.zero_run = 0; }
        self.r[15] = addr.wrapping_add(2);
        self.instructions += 1;
        if !bit(self.cpsr, 7) { self.irq_open_steps += 1; }
        if trace && (self.trace_all || self.instructions <= 60) {
            println!("{:08x}: {:04x} (T)", addr, insn);
        }
        if insn == 0xE7FE && bit(self.cpsr, 7) { self.r[15] = addr; return; }

        if (self.cpsr & 0x1F) != 0x12 && (insn & 0xF800) == 0xE000 {
            let mut off = (insn & 0x7FF) << 1;
            if bit(off, 11) { off |= 0xFFFF_F000; }
            if addr.wrapping_add(4).wrapping_add(off) == addr && self.irq_count < self.irq_limit {
                self.irq_count += 1;
                self.raise_irq(addr.wrapping_add(2));
                return;
            }
        }

        let top = insn >> 12;
        if insn < 0x1800 {
            let typ = if insn < 0x0800 { 0 } else if insn < 0x1000 { 1 } else { 2 };
            let imm = (insn >> 6) & 0x1F;
            let rm = (insn >> 3) & 7; let rd = insn & 7;
            let amt = if typ == 0 { imm } else if imm == 0 { 32 } else { imm };
            let res = self.shift(self.read_reg(rm), typ, amt);
            self.set_nz(res); self.set_c(self.shifter_carry != 0);
            self.r[rd as usize] = res;
            return;
        }
        if top == 1 {
            let mut imm = (insn >> 6) & 7;
            let kind = (insn >> 9) & 3;
            if kind < 2 { imm = self.read_reg(imm); }
            let rn = (insn >> 3) & 7; let rd = insn & 7;
            let res = if kind & 1 == 0 {
                self.alu(4, self.read_reg(rn), imm, true)
            } else { self.alu(2, self.read_reg(rn), imm, true) };
            self.set_nz(res); self.r[rd as usize] = res;
            return;
        }
        if top == 2 || top == 3 {
            let op = (insn >> 11) & 3;
            let rd = (insn >> 8) & 7; let imm = insn & 0xFF;
            if op == 0 { self.r[rd as usize] = imm; self.set_nz(imm); }
            else if op == 1 { self.alu(10, self.read_reg(rd), imm, true); }
            else {
                let res = self.alu(if op == 2 { 4 } else { 2 }, self.read_reg(rd), imm, true);
                self.set_nz(res); self.r[rd as usize] = res;
            }
            return;
        }
        if (insn >> 10) == 0x10 {
            let op = (insn >> 6) & 0xF;
            let rm = (insn >> 3) & 7; let rd = insn & 7;
            let a = self.read_reg(rd); let b = self.read_reg(rm);
            match op {
                0 | 1 | 5 | 6 | 8 | 10 | 11 | 12 | 14 => {
                    let res = self.alu(if op == 8 { 8 } else { op }, a, b, true);
                    self.set_nz(res);
                    if op != 8 && op != 10 && op != 11 { self.r[rd as usize] = res; }
                    return;
                }
                2 | 3 | 4 | 7 => {
                    let typ = if op == 2 { 0 } else if op == 3 { 1 } else if op == 4 { 2 } else { 3 };
                    let res = self.shift(a, typ, b & 0xFF);
                    self.set_nz(res); self.set_c(self.shifter_carry != 0); self.r[rd as usize] = res;
                    return;
                }
                9 => { let res = self.alu(3, a, b, true); self.set_nz(res); self.r[rd as usize] = res; return; }
                13 => { let res = a.wrapping_mul(b); self.r[rd as usize] = res; self.set_nz(res); return; }
                15 => { let res = !b; self.set_nz(res); self.r[rd as usize] = res; return; }
                _ => {}
            }
        }
        if (insn >> 10) == 0x11 {
            let op = (insn >> 8) & 3;
            let rd = (insn & 7) | ((insn >> 4) & 8);
            let rm = (insn >> 3) & 0xF;
            if op == 0 { let v = self.read_reg(rd).wrapping_add(self.read_reg(rm)); self.write_reg(rd, v); return; }
            if op == 1 { self.alu(10, self.read_reg(rd), self.read_reg(rm), true); return; }
            if op == 2 { let v = self.read_reg(rm); self.write_reg(rd, v); return; }
            let target = self.read_reg(rm);
            if bit(insn, 7) {
                self.r[14] = (addr.wrapping_add(2)) | 1;
                if bit(target, 0) { self.cpsr |= 0x20; self.r[15] = target & !1; }
                else { self.cpsr &= !0x20; self.r[15] = target & !3; }
            } else {
                if bit(target, 0) { self.cpsr |= 0x20; } else { self.cpsr &= !0x20; }
                self.r[15] = target & !1;
            }
            return;
        }
        if (insn & 0xF800) == 0x4800 {
            let rd = (insn >> 8) & 7;
            let base = (addr.wrapping_add(4)) & !3;
            let v = self.rd32(base.wrapping_add((insn & 0xFF) << 2));
            self.r[rd as usize] = v;
            return;
        }
        if top == 5 {
            let rm = (insn >> 6) & 7; let rn = (insn >> 3) & 7; let rd = insn & 7;
            let a = self.read_reg(rn).wrapping_add(self.read_reg(rm));
            match (insn >> 9) & 7 {
                0 => { let v = self.read_reg(rd); self.wr32(a, v); }
                1 => { let v = self.read_reg(rd) & 0xFFFF; self.wr16(a, v); }
                2 => { let v = self.read_reg(rd) & 0xFF; self.wr8(a, v); }
                3 => { let v = sx8(self.rd8(a)) as u32; self.r[rd as usize] = v; }
                4 => { let v = self.rd32(a); self.r[rd as usize] = v; }
                5 => { let v = self.rd16(a); self.r[rd as usize] = v; }
                6 => { let v = self.rd8(a); self.r[rd as usize] = v; }
                7 => { let v = sx16(self.rd16(a)) as u32; self.r[rd as usize] = v; }
                _ => {}
            }
            return;
        }
        if top == 6 || top == 7 || top == 8 {
            let rd = insn & 7; let rn = (insn >> 3) & 7;
            let imm = (insn >> 6) & 0x1F;
            let a = self.read_reg(rn).wrapping_add(if top == 6 { imm << 2 } else if top == 8 { imm << 1 } else { imm });
            if top == 6 {
                if bit(insn, 11) { let v = self.rd32(a); self.r[rd as usize] = v; }
                else { let v = self.read_reg(rd); self.wr32(a, v); }
            } else if top == 7 {
                if bit(insn, 11) { let v = self.rd8(a); self.r[rd as usize] = v; }
                else { let v = self.read_reg(rd) & 0xFF; self.wr8(a, v); }
            } else {
                if bit(insn, 11) { let v = self.rd16(a); self.r[rd as usize] = v; }
                else { let v = self.read_reg(rd) & 0xFFFF; self.wr16(a, v); }
            }
            return;
        }
        if top == 9 {
            let rd = (insn >> 8) & 7;
            let a = self.read_reg(13).wrapping_add((insn & 0xFF) << 2);
            if bit(insn, 11) { let v = self.rd32(a); self.r[rd as usize] = v; }
            else { let v = self.read_reg(rd); self.wr32(a, v); }
            return;
        }
        if (top == 0xA || top == 0xB)
            && (insn & 0xFE00) != 0xB400 && (insn & 0xFE00) != 0xBC00
            && (insn & 0xFF00) != 0xB000 {
            let rd = (insn >> 8) & 7;
            let imm = (insn & 0xFF) << 2;
            let base = if bit(insn, 11) { self.read_reg(13) } else { addr.wrapping_add(4) & !3 };
            self.r[rd as usize] = base.wrapping_add(imm);
            return;
        }
        if (insn & 0xFF00) == 0xB000 {
            let imm = (insn & 0x7F) << 2;
            self.r[13] = if bit(insn, 7) { self.r[13].wrapping_sub(imm) } else { self.r[13].wrapping_add(imm) };
            return;
        }
        if (insn & 0xFE00) == 0xB400 || (insn & 0xFE00) == 0xBC00 {
            let push = !bit(insn, 11);
            let list = insn & 0xFF;
            let extra = bit(insn, 8);
            let count = list.count_ones() + extra as u32;
            if push {
                self.r[13] = self.r[13].wrapping_sub(4 * count);
                let mut a = self.r[13];
                for i in 0..=7 { if bit(list, i) { let v = self.r[i as usize]; self.wr32(a, v); a = a.wrapping_add(4); } }
                if extra { let v = self.r[14]; self.wr32(a, v); }
            } else {
                let mut a = self.r[13];
                for i in 0..=7 { if bit(list, i) { let v = self.rd32(a); self.r[i as usize] = v; a = a.wrapping_add(4); } }
                if extra {
                    let newpc = self.rd32(a);
                    if bit(newpc, 0) { self.cpsr |= 0x20; self.r[15] = newpc & !1; }
                    else { self.cpsr &= !0x20; self.r[15] = newpc & !3; }
                }
                self.r[13] = self.r[13].wrapping_add(4 * count);
            }
            return;
        }
        if top == 0xC {
            let ldm = bit(insn, 11);
            let rn = (insn >> 8) & 7;
            let list = insn & 0xFF;
            let mut a = self.read_reg(rn);
            for i in 0..=7 {
                if bit(list, i) {
                    if ldm { let v = self.rd32(a); self.r[i as usize] = v; a = a.wrapping_add(4); }
                    else { let v = self.r[i as usize]; self.wr32(a, v); a = a.wrapping_add(4); }
                }
            }
            self.write_reg(rn, a);
            return;
        }
        if top == 0xD && ((insn >> 8) & 0xF) < 0xE {
            let c = (insn >> 8) & 0xF;
            let mut imm = (insn & 0xFF) << 1;
            if bit(imm, 8) { imm |= 0xFFFF_FE00; }
            if self.cond(c) { self.r[15] = self.r[15].wrapping_add(imm).wrapping_add(2); }
            return;
        }
        if top == 0xD && ((insn >> 8) & 0xF) == 0xF { self.raise_svc(); return; }
        if (insn & 0xF800) == 0xE000 {
            let mut imm = (insn & 0x7FF) << 1;
            if bit(imm, 11) { imm |= 0xFFFF_F000; }
            self.r[15] = self.r[15].wrapping_add(imm).wrapping_add(2);
            return;
        }
        if (insn & 0xF800) == 0xE800 {
            if !bl_was_pending {
                let mut imm = (insn & 0x7FF) << 1;
                if bit(imm, 11) { imm |= 0xFFFF_F000; }
                self.r[15] = self.r[15].wrapping_add(imm).wrapping_add(2);
                return;
            }
            let base = self.thumb_bl_addr.wrapping_add(4).wrapping_add(
                ((self.thumb_bl_insn << 21) as i32 >> 9) as u32);
            let target = base.wrapping_add((insn & 0x7FF) << 1) & !3;
            self.r[14] = (addr.wrapping_add(2)) | 1;
            self.cpsr &= !0x20;
            self.r[15] = target;
            return;
        }
        if (insn & 0xF800) == 0xF000 {
            self.thumb_bl_insn = insn;
            self.thumb_bl_addr = addr;
            self.thumb_bl_pending = true;
            return;
        }
        if (insn & 0xF800) == 0xF800 {
            if !bl_was_pending { return; }
            let s2 = (self.thumb_bl_insn >> 10) & 1;
            let i1 = (((insn >> 13) & 1) ^ s2) ^ 1;
            let i2 = (((insn >> 11) & 1) ^ s2) ^ 1;
            let mut imm32 = (s2 << 24) | (i1 << 23) | (i2 << 22)
                | ((self.thumb_bl_insn & 0x3FF) << 12) | ((insn & 0x7FF) << 1);
            if s2 != 0 { imm32 |= 0xFE00_0000; }
            let target = self.thumb_bl_addr.wrapping_add(4).wrapping_add(imm32);
            self.r[14] = (addr.wrapping_add(2)) | 1;
            self.cpsr |= 0x20;
            self.r[15] = target & !1;
            return;
        }
    }

    pub fn dump_regs(&self) -> String {
        let mut s = String::new();
        for i in 0..16 { s.push_str(&format!("r{:<2}={:08x}  ", i, self.r[i])); }
        s.push_str(&format!("\nCPSR={:08x} instructions={}", self.cpsr, self.instructions));
        s.push_str(&format!("\nCP15: c1={:08x} c2(TTBR)={:08x} c3={:08x} ITCM={:08x} DTCM={:08x}",
            self.cp15[1], self.cp15[2], self.cp15[3], self.itcm_reg, self.dtcm_reg));
        s
    }
}
