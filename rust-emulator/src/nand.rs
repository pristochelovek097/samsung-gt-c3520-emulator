//! Виртуальный NAND-контроллер 0x7D800000 (карта .ptt), 1-в-1 порт NandController.java.
use std::collections::HashMap;
use std::fs;
use std::path::Path;

pub const BASE: u32 = 0x7D80_0000;

pub struct NandController {
    sparse: Vec<(u32, Vec<u8>)>,
    fifo: [u8; 2048],
    command: u32,
    column: u32,
    row: u32,
    fifo_pos: usize,
    status14: u32,
}

impl NandController {
    pub fn new(dir: &str) -> Self {
        let mut n = Self {
            sparse: Vec::new(), fifo: [0; 2048], command: 0xFF,
            column: 0, row: 0, fifo_pos: 0, status14: 3,
        };
        let bin = Path::new(dir).join("C3520XFLJ1_BIN");
        // Блок 0: честная таблица разделов EFSPARTI из factory-дампа .smd.
        // Заголовок файла SMDHEAD0 занимает 0x120 байт; дальше идёт страница 0
        // NAND с magic "EFSPARTI" (0x134) и 12 записями разделов по 0x20 байт.
        let smd = Path::new(dir).join("C3520XFLJ1_C352WOXELJ1.smd");
        let block0 = {
            let mut b = vec![0xFFu8; 0x20000];
            if let Ok(mut f) = fs::File::open(&smd) {
                use std::io::Read;
                let mut page = [0u8; 0x2000];
                let mut tmp = [0u8; 0x2000 + 0x120];
                if f.read(&mut tmp).unwrap_or(0) > 0x120 {
                    page.copy_from_slice(&tmp[0x120..]);
                    b[..0x2000].copy_from_slice(&page);
                }
            }
            b
        };
        n.add(0x000000, block0);
        n.add(0x020000, load_cla(&bin, "C3520XELaFleur.bmp", 0x20000));
        n.add(0x040000, load_raw(&bin, "C3520XELaFleur.bootlcdact", 0x20000));
        n.add(0x060000, load_cla(&bin, "C3520XELaFleur.cla/ER_FLASH_CB_ROOT", 0x20000));
        n.add(0x080000, load_cla(&bin, "C3520XELaFleur.cla/ER_FLASH_CB_MAIN", 0xC00000));
        n.add(0xC80000, load_cla(&bin, "C3520XELaFleur.cla/ER_FLASH_NCB_DSP", 0x60000));
        n.add(0xCE0000, load_cla(&bin, "C3520XELaFleur.cla/ER_FLASH_CB_PAGE1", 0x600000));
        n.add(0x12E0000, load_cla(&bin, "C3520XELaFleur.cla/ER_RAM_CB", 0x40000));
        // Служебный блок 0x3FF (0x07FE0000): 0x68-байтная структура тома,
        // которую 0xC0064C68 читает в 0xC9EF5ACC и проверяет 0xC007A528.
        let mut svc = vec![0u8; 0x800];
        svc[0..4].copy_from_slice(&0x0102_0304u32.to_le_bytes());
        svc[8..12].copy_from_slice(&1u32.to_le_bytes());
        svc[0x64..0x68].copy_from_slice(&0x0102_0304u32.to_le_bytes());
        let sum: u32 = svc[..0x68].iter().map(|&x| x as u32).sum();
        svc[0x60] = ((0u32).wrapping_sub(sum)) as u8;   // сумма 0x68 байт == 0 (mod 256)
        // 0xC00605AC сканирует блоки 0x3EA..0x3FF; каждая итерация читает
        // страницу в 0xC9EF6B40 и копирует 0x68 байт в 0xC9EF5ACC перед
        // проверкой 0xC007A528. All these blocks must carry the structure.
        for blk in 0x3EAu32..=0x3FFu32 {
            n.add(blk.wrapping_shl(0x11), svc.clone());
        }
        n
    }

    fn add(&mut self, off: u32, data: Vec<u8>) { self.sparse.push((off, data)); }

    pub fn load_decompressed(&mut self, off: u32, data: Vec<u8>) {
        self.sparse.retain(|(o, _)| *o != off);
        if !data.is_empty() { self.sparse.push((off, data)); }
    }

    pub fn read_image(&self, addr: u32) -> u8 {
        for (o, b) in &self.sparse {
            if addr >= *o {
                let i = (addr - *o) as usize;
                if i < b.len() { return b[i]; }
            }
        }
        0xFF
    }

    /// Hamming ECC 512-байтного сектора (24 бита чётности по позициям битов).
    /// При равенстве кодов синдром 0xC0A8A870 равен нулю.
    fn sector_ecc(data: &[u8]) -> [u8; 3] {
        let mut parity = [0u8; 24];
        for (i, &b) in data.iter().enumerate() {
            for bit in 0..8u32 {
                if (b >> bit) & 1 != 0 {
                    let pos = (i as u32) * 8 + bit;
                    for k in 0..24u32 { if (pos >> k) & 1 != 0 { parity[k as usize] ^= 1; } }
                }
            }
        }
        let mut out = [0u8; 3];
        for k in 0..24usize { if parity[k] != 0 { out[k / 8] |= 1 << (k % 8); } }
        out
    }

    /// Spare/OOB страницы: 3 байта ECC на каждый из 4 секторов по 512 байт
    /// со смещениями 1, 0x11, 0x21, 0x31 (как читает 0xC0A8A870).
    pub fn spare_for_page(&self, page_addr: u32) -> [u8; 0x40] {
        let mut out = [0xFFu8; 0x40];
        for sec in 0..4u32 {
            let mut buf = [0u8; 512];
            for i in 0..512usize { buf[i] = self.read_image(page_addr.wrapping_add(sec * 512 + i as u32)); }
            let e = Self::sector_ecc(&buf);
            let o = 1usize + sec as usize * 0x10;
            out[o..o + 3].copy_from_slice(&e);
        }
        out
    }

    fn latch(&mut self) {
        let start = self.row.wrapping_mul(2048).wrapping_add(self.column);
        for i in 0..2048 { self.fifo[i] = self.read_image(start.wrapping_add(i as u32)); }
        self.fifo_pos = 0;
    }

    pub fn read(&mut self, addr: u32) -> u32 {
        let off = addr.wrapping_sub(BASE);
        if off > 0xFFFF { return 0; }
        match off {
            0x014 => self.status14,
            0x0C0 => 0x16C9,
            0x408 => 0xFFFF_FFFF,
            0x100 => 0,
            // PIO-чтение NAND: 0x74002020 выдаёт байты защёлкнутой страницы.
            0x20 => {
                let v = self.fifo[self.fifo_pos & 2047] as u32;
                self.fifo_pos += 1;
                v
            }
            _ => {
                if off >= 0x800 && off < 0x800 + 2048 {
                    let v = self.fifo[self.fifo_pos & 2047] as u32;
                    self.fifo_pos += 1;
                    v
                } else { 0 }
            }
        }
    }

    pub fn write(&mut self, addr: u32, value: u32) {
        let off = addr.wrapping_sub(BASE);
        if off > 0xFFFF { return; }
        match off {
            0x000 => self.command = value & 0xFF,
            0x004 => { self.column = value & 0xFFF; self.fifo_pos = 0; }
            0x008 => self.row = value & 0xFFFF,
            0x00C => self.row |= (value & 0xFF) << 16,
            0x010 => self.status14 = value & 3,
            _ => {}
        }
        let v = value & 0xFF;
        if v == 0x30 && self.command == 0x00 { self.latch(); }
        if v == 0x00 { self.command = 0x00; self.fifo_pos = 0; }
        if v == 0x90 { self.command = 0x90; }
        if v == 0x70 { self.command = 0x70; }
    }
}

fn fill(n: usize, v: u8) -> Vec<u8> { vec![v; n] }

fn load_raw(dir: &Path, name: &str, cap: usize) -> Vec<u8> {
    let mut b = fill(cap, 0xFF);
    if let Ok(raw) = fs::read(dir.join(name)) {
        let n = raw.len().min(cap);
        b[..n].copy_from_slice(&raw[..n]);
    }
    b
}

fn load_cla(dir: &Path, name: &str, cap: usize) -> Vec<u8> {
    let mut b = fill(cap, 0xFF);
    if let Ok(raw) = fs::read(dir.join(name)) {
        let off = if raw.len() > 0x2000 { 0x2000 } else { 0 };
        let n = (raw.len() - off).min(cap);
        b[..n].copy_from_slice(&raw[off..off + n]);
    }
    b
}
