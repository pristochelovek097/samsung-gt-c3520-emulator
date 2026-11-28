//! Плоская память PNX4910 + TCM + MMIO + разреженные страницы. Порт Memory.java 1-в-1.
use std::collections::HashMap;
use crate::lcd::LcdController;
use crate::nand::NandController;

pub static WORKAROUNDS: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
pub const TCM_SIZE: u32 = 0x0004_0000;
const DRAM_BASE: u32 = 0xC000_0000;
const DRAM_LEN: usize = 0x0400_0000;
const SRAM_BASE: u32 = 0xC990_0000;
const SRAM_LEN: usize = 0x0020_0000;

pub struct Memory {
    pub dram: Vec<u8>,   // 0xC0000000..0xC4000000 (flash+SDRAM, read/write)
    pub itcm: Vec<u8>,
    pub sram: Vec<u8>,
    pub pages: HashMap<u32, Box<[u8; 4096]>>,
    pub mmio: HashMap<u32, u32>,
    pub nand: NandController,
    pub lcd: LcdController,
    pub io_seen: HashMap<u32, (u32, u32)>, // addr -> (reads, writes)
    // Диагностика записей: точный адрес-ловушка и защита секции кода.
    pub watch_addr: u32,
    pub watch_pc: u32,
    pub watch_hits: Vec<(u32, u32, u32, u32)>,
    pub code_watch_enabled: bool,
    pub code_writes: Vec<(u32, u32, u32, u32)>,
    pub text_readonly: bool,
    pub pt_watch_enabled: bool,
    pub pt_writes: Vec<(u32, u32, u32, u32)>,
    pub fifo_bytes: Vec<(u32, u8)>,
    pub pmu_writes: Vec<(u32, u32)>,
}

impl Memory {
    pub fn new(dir: &str) -> Self {
        Self {
            dram: vec![0; DRAM_LEN],
            itcm: vec![0; TCM_SIZE as usize],
            sram: vec![0; SRAM_LEN],
            pages: HashMap::new(),
            mmio: HashMap::new(),
            nand: NandController::new(dir),
            lcd: LcdController::new(),
            io_seen: HashMap::new(),
            watch_addr: 0, watch_pc: 0, watch_hits: Vec::new(),
            code_watch_enabled: false, code_writes: Vec::new(),
            text_readonly: false,
            pt_watch_enabled: false, pt_writes: Vec::new(), fifo_bytes: Vec::new(), pmu_writes: Vec::new(),
        }
    }

    #[inline]
    fn page_mut(&mut self, addr: u32) -> &mut [u8; 4096] {
        let key = addr >> 12;
        self.pages.entry(key).or_insert_with(|| Box::new([0u8; 4096]))
    }

    fn flat_idx(&self, addr: u32) -> Option<(usize, usize)> {
        if addr < TCM_SIZE { return Some((0, addr as usize)); }
        if addr >= DRAM_BASE && addr < DRAM_BASE + DRAM_LEN as u32 {
            return Some((1, (addr - DRAM_BASE) as usize));
        }
        if addr >= SRAM_BASE && addr < SRAM_BASE + SRAM_LEN as u32 {
            return Some((2, (addr - SRAM_BASE) as usize));
        }
        None
    }

    #[inline]
    pub fn set_mmio(&mut self, addr: u32, value: u32) { self.mmio.insert(addr & !3, value); }

    pub fn load(&mut self, data: &[u8], address: u32) {
        for (i, b) in data.iter().enumerate() { self.write8(address.wrapping_add(i as u32), *b as u32); }
    }
    pub fn load_at(&mut self, data: &[u8], offset: usize, length: usize, address: u32) {
        for i in 0..length {
            if offset + i < data.len() { self.write8(address.wrapping_add(i as u32), data[offset + i] as u32); }
        }
    }

    #[inline]
    fn note_write(&mut self, addr: u32, old: u32, new: u32) {
        if old == new { return; }
        if self.watch_addr != 0 && (addr == self.watch_addr || (addr & !3) == (self.watch_addr & !3)) {
            if self.watch_hits.len() < 64 {
                self.watch_hits.push((self.watch_pc, addr, old, new));
            }
        }
        if self.pt_watch_enabled && ((addr >= 0xC3BC_4000 && addr < 0xC3BC_8000)
            || (addr >= 0xC3BCA_000 && addr < 0xC3BD_0000 && addr < 0xC3BC_F000)) {
            if self.pt_writes.len() < 80 { self.pt_writes.push((self.watch_pc, addr, old, new)); }
        }
        if self.code_watch_enabled && addr >= 0xC000_0000 && addr < 0xC11C_0000 {
            if self.code_writes.len() < 64 {
                self.code_writes.push((self.watch_pc, addr, old, new));
            }
        }
    }

    #[inline]
    pub fn read32_raw(&self, addr: u32) -> u32 {
        let a = addr & !3;
        if let Some((bank, o)) = self.flat_idx(a) {
            let sl = match bank { 0 => &self.itcm, 1 => &self.dram, _ => &self.sram };
            return u32::from_le_bytes([sl[o], sl[o+1], sl[o+2], sl[o+3]]);
        }
        if let Some(p) = self.pages.get(&(a >> 12)) {
            let o = (a & 4095) as usize;
            if o + 4 <= 4096 { return u32::from_le_bytes([p[o], p[o+1], p[o+2], p[o+3]]); }
        }
        0
    }

    #[inline]
    pub fn read32(&mut self, addr: u32) -> u32 {
        if addr == 0x7400_202C {
            if self.nand.pio_remaining > 0 || self.nand.pio_done { return self.nand.read(addr); }
            return *self.mmio.get(&addr).unwrap_or(&0x8000_0200);
        }
        if (addr ^ crate::nand::BASE) & !0xFFFF == 0 {
            self.io_seen.entry(addr & !3).or_insert((1, 0)).0 += 1;
            return self.nand.read(addr);
        }
        if (addr >= 0x7000_0000 && addr < 0x7002_0000)
            || (addr >= 0x7800_0000 && addr < 0x7802_0000)
            || (addr >= 0x2FFE_0000 && addr < 0x3000_0000) {
            if let Some(v) = self.mmio.get(&(addr & !3)) { return *v; }
        }
        let a = addr & !3;
        if let Some((bank, o)) = self.flat_idx(a) {
            let s = match bank { 0 => &self.itcm, 1 => &self.dram, _ => &self.sram };
            return u32::from_le_bytes([s[o], s[o + 1], s[o + 2], s[o + 3]]);
        }
        let p = self.pages.get(&(a >> 12));
        if let Some(p) = p {
            let o = (a & 4095) as usize;
            if o + 4 <= 4096 { return u32::from_le_bytes([p[o], p[o+1], p[o+2], p[o+3]]); }
        }
        let b0 = self.read8(a); let b1 = self.read8(a+1);
        let b2 = self.read8(a+2); let b3 = self.read8(a+3);
        (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24)
    }

    #[inline]
    pub fn write32(&mut self, addr: u32, value: u32) {
        // Базовый DMA: запись бита завершения/стата в 0x70004008 завершает трансфер.
        if (addr & !3) == 0x7000_4008 && (value & 0x4000_0000) != 0
            && (self.watch_pc == 0xC038_7500 || self.watch_pc == 0xC01C_24B4) {
            self.lcd.frames += 1;
        }
        // GiSPort: команда старта трансфера 0x7400213C=1 → статус завершения.
        if (addr & !3) == 0x7400_213C && (value & 0xFF) == 1 {
            self.mmio.insert(0x7400_202C, 0x8000_0600);
        }
        // INTC (0x70001000..0x70001050): записи прошивки в регистры подтверждения
        // должны обновлять ту же mmio-карту, откуда читается активный source.
        if (addr & !3) >= 0x7000_1000 && (addr & !3) <= 0x7000_1050 {
            self.mmio.insert(addr & !3, value);
        }
        // Уровневая модель INTC: подтверждение NAND/DMA по 0x7400202C или
        // 0x74008004/08 снимает активный источник src=17 в 0x7000102C.
        if (addr & !3) == 0x7400_202C || (addr & !3) == 0x7400_8004 || (addr & !3) == 0x7400_8008 {
            self.mmio.insert(0x7000_102C, 0);
        }
        let old = if self.watch_addr != 0 || self.code_watch_enabled { self.read32_raw(addr) } else { 0 };
        self.note_write(addr & !3, old, value);
        if ((addr >= 0x7800_0000 && addr < 0x7802_0000) || (addr >= 0x7000_0000 && addr < 0x7002_0000))
            && addr != 0x7800_8010 && addr != 0x7800_0010 {
            if self.pmu_writes.len() < 256 { self.pmu_writes.push((addr, value)); }
        }
        if self.text_readonly && (addr & !3) >= 0xC000_0000 && (addr & !3) < 0xC11C_0000 { return; }
        if self.lcd_port(addr) { self.lcd.write(addr, value); self.io_seen.entry(addr & !3).or_insert((0,1)).1 += 1; return; }
        if (addr & !3) == 0x7000_2018 { return; } // auto-clear go bit
        if (addr ^ crate::nand::BASE) & !0xFFFF == 0 {
            self.io_seen.entry(addr & !3).or_insert((0,1)).1 += 1;
            self.nand.write(addr, value); return;
        }
        let a = addr & !3;
        if let Some((bank, o)) = self.flat_idx(a) {
            let s = match bank { 0 => &mut self.itcm, 1 => &mut self.dram, _ => &mut self.sram };
            s[o..o+4].copy_from_slice(&value.to_le_bytes());
            return;
        }
        let p = self.page_mut(a);
        let o = (a & 4095) as usize;
        if o + 4 <= 4096 {
            p[o..o+4].copy_from_slice(&value.to_le_bytes());
        } else {
            for i in 0..4 { self.write8(a + i, value >> (8*i)); }
        }
    }

    #[inline]
    pub fn read16(&mut self, addr: u32) -> u32 {
        let a = addr & !1;
        if let Some((bank, o)) = self.flat_idx(a) {
            let s = match bank { 0 => &self.itcm, 1 => &self.dram, _ => &self.sram };
            return u16::from_le_bytes([s[o], s[o+1]]) as u32;
        }
        let b0 = self.read8(a); let b1 = self.read8(a+1);
        (b0 & 0xFF) | ((b1 & 0xFF) << 8)
    }

    #[inline]
    pub fn write16(&mut self, addr: u32, value: u32) {
        let old = self.read16(addr);
        self.note_write(addr & !1, old, value & 0xFFFF);
        if self.text_readonly && (addr & !1) >= 0xC000_0000 && (addr & !1) < 0xC11C_0000 { return; }
        if self.lcd_port(addr) { self.lcd.write(addr, value & 0xFFFF); return; }
        let a = addr & !1;
        if let Some((bank, o)) = self.flat_idx(a) {
            let s = match bank { 0 => &mut self.itcm, 1 => &mut self.dram, _ => &mut self.sram };
            s[o..o+2].copy_from_slice(&(value as u16).to_le_bytes());
            return;
        }
        self.write8(addr, value);
        self.write8(addr + 1, value >> 8);
    }

    #[inline]
    pub fn read8(&mut self, addr: u32) -> u32 {
        if addr == 0xC1B5_1B40 { return 2; }
        if (addr ^ crate::nand::BASE) & !0xFFFF == 0 { return self.nand.read(addr) & 0xFF; }
        if let Some((bank, o)) = self.flat_idx(addr) {
            let s = match bank { 0 => &self.itcm, 1 => &self.dram, _ => &self.sram };
            return s[o] as u32;
        }
        match self.pages.get(&(addr >> 12)) {
            Some(p) => p[(addr & 4095) as usize] as u32,
            None => 0,
        }
    }

    #[inline]
    pub fn write8(&mut self, addr: u32, value: u32) {
        if addr == 0x7800_8010 || addr == 0x7800_0010 {
            if self.fifo_bytes.len() < 512 { self.fifo_bytes.push((addr, value as u8)); }
        }
        let old = self.read8(addr);
        self.note_write(addr, old, value & 0xFF);
        if self.text_readonly && addr >= 0xC000_0000 && addr < 0xC11C_0000 { return; }
        if self.lcd_port(addr) { self.lcd.write(addr, value & 0xFF); return; }
        if (addr ^ crate::nand::BASE) & !0xFFFF == 0 { self.nand.write(addr, value); return; }
        if let Some((bank, o)) = self.flat_idx(addr) {
            let s = match bank { 0 => &mut self.itcm, 1 => &mut self.dram, _ => &mut self.sram };
            s[o] = value as u8;
            return;
        }
        self.page_mut(addr)[(addr & 4095) as usize] = value as u8;
    }

    fn lcd_port(&self, addr: u32) -> bool {
        addr == 0x7400_2000 || addr == 0x7400_2100
            || (addr & !1) == 0x7401_900C
            || (addr & 0xFFFF_0000) == 0x7402_0000
    }

    pub fn memset(&mut self, from: u32, to: u32, value: u32) {
        let mut a = from;
        while a < to { self.write8(a, value); a = a.wrapping_add(1); }
    }
    pub fn memcpy(&mut self, dst: u32, src: u32, length: u32) {
        for i in 0..length {
            let v = self.read8(src.wrapping_add(i));
            self.write8(dst.wrapping_add(i), v);
        }
    }
}
