//! QMDC/QMDU распаковщик .cla (бит-в-бит порт qmdc_decode2.py).
use std::fs;
use std::path::Path;

struct CBit<'a> { d: &'a [u8], nbits: u32, buf: u32, pos: usize }
impl<'a> CBit<'a> {
    fn new(d: &'a [u8]) -> Self { Self { d, nbits: 0, buf: 0, pos: 0 } }
    fn bit(&mut self) -> u32 {
        if self.nbits < 2 {
            let b = self.d.get(self.pos).copied().unwrap_or(0);
            self.buf = (self.buf << 8) | b as u32;
            self.pos += 1; self.nbits += 8;
        }
        self.nbits -= 1;
        (self.buf >> self.nbits) & 1
    }
}
struct Stream<'a> { d: &'a [u8], pos: usize }
impl<'a> Stream<'a> {
    fn new(d: &'a [u8]) -> Self { Self { d, pos: 0 } }
    fn byte(&mut self) -> Result<u8, ()> {
        let v = *self.d.get(self.pos).ok_or(())?; self.pos += 1; Ok(v)
    }
    fn u16le(&mut self) -> Result<u16, ()> {
        if self.pos + 2 > self.d.len() { return Err(()); }
        let v = u16::from_le_bytes([self.d[self.pos], self.d[self.pos + 1]]);
        self.pos += 2; Ok(v)
    }
    fn raw16(&mut self, n: usize) -> Result<Vec<u8>, ()> {
        if self.pos + n > self.d.len() { return Err(()); }
        let v = self.d[self.pos..self.pos + n].to_vec(); self.pos += n; Ok(v)
    }
}

pub struct Qmdc<'a> {
    global: [u16; 256],
    boot: &'a [u8],
}

impl<'a> Qmdc<'a> {
    pub fn new(boot: &'a [u8]) -> Self {
        let mut g = [0u16; 256];
        if boot.len() >= 0x9AB0 + 512 {
            for i in 0..256 {
                g[i] = u16::from_le_bytes([boot[0x9AB0 + i*2], boot[0x9AB0 + i*2 + 1]]);
            }
        }
        Self { global: g, boot }
    }

    fn decode(&self, c: &[u8], d: &[u8], lit: &[u8], b: usize) -> Result<Vec<u8>, ()> {
        let mut cb = CBit::new(c);
        let mut ds = Stream::new(d);
        let mut ls = Stream::new(lit);
        let mut out = ls.raw16(16)?;
        let mut dist: usize = 1;
        let dom = |out: &[u8], src: usize| -> u16 {
            if src < out.len().saturating_sub(1) {
                u16::from_le_bytes([out[src], out[src + 1]])
            } else { 0 }
        };
        while out.len() < b {
            if cb.bit() == 0 {
                if cb.bit() == 1 {
                    for _ in 0..8 {
                        let src = out.len().saturating_sub(2 * dist);
                        let v = dom(&out, src);
                        out.extend_from_slice(&v.to_le_bytes());
                    }
                } else {
                    out.extend_from_slice(&ls.raw16(16)?);
                }
                continue;
            }
            let flags = ds.byte()?;
            for ib in (0..8).rev() {
                if ib & 1 == 1 {
                    if cb.bit() == 1 {
                        // расстояние то же
                    } else if cb.bit() == 1 {
                        dist = ds.byte()? as usize;
                    } else {
                        dist = ls.u16le()? as usize;
                    }
                }
                let src = out.len().saturating_sub(2 * dist);
                let base = dom(&out, src);
                let v = if (flags >> ib) & 1 == 1 { base }
                    else if cb.bit() == 1 { ls.u16le()? }
                    else { self.global[ds.byte()? as usize] ^ base };
                out.extend_from_slice(&v.to_le_bytes());
                if out.len() >= b { break; }
            }
        }
        out.truncate(b);
        Ok(out)
    }

    /// Распаковывает .cla-контейнер: заголовок 0x2000, слоты по 0x1000.
    pub fn unpack_cla(&self, blob: &[u8]) -> Vec<u8> {
        if blob.len() < 0x2000 { return blob.to_vec(); }
        let size = u32::from_le_bytes(blob[0..4].try_into().unwrap()) as usize;
        let payload_end = (0x2000 + size).min(blob.len());
        let payload = &blob[0x2000..payload_end];
        let mut out = Vec::new();
        let mut off = 0usize;
        while off + 0x1000 <= payload.len() {
            let slot = &payload[off..off + 0x1000];
            if &slot[..4] == b"QMDC" {
                let a = u32::from_le_bytes(slot[4..8].try_into().unwrap()) as usize;
                let b = u32::from_le_bytes(slot[8..12].try_into().unwrap()) as usize;
                let c = u32::from_le_bytes(slot[12..16].try_into().unwrap()) as usize;
                let d = u32::from_le_bytes(slot[16..20].try_into().unwrap()) as usize;
                let pay = &slot[0x14..(0x14 + a).min(slot.len())];
                let cs = &pay[..c.min(pay.len())];
                let ds = &pay[c.min(pay.len())..(c + d).min(pay.len())];
                let lit = &pay[(c + d).min(pay.len())..];
                match self.decode(cs, ds, lit, b) {
                    Ok(v) => out.extend_from_slice(&v),
                    Err(_) => out.extend_from_slice(slot),
                }
            } else if &slot[..4] == b"QMDU" {
                let n = u32::from_le_bytes(slot[4..8].try_into().unwrap()) as usize;
                let end = (8 + n).min(slot.len());
                out.extend_from_slice(&slot[8..end]);
            } else {
                out.extend_from_slice(slot);
            }
            off += 0x1000;
        }
        if off < payload.len() { out.extend_from_slice(&payload[off..]); }
        out
    }

    pub fn unpack_bmp(&self, blob: &[u8]) -> Vec<u8> { self.unpack_cla(blob) }
}

pub fn parse_ref_lines(text: &str) -> Vec<u32> {
    text.lines().map(|l| u32::from_str_radix(l.trim(), 16).unwrap_or(0)).collect()
}

pub fn load_ranges(mem: &mut crate::memory::Memory, prefix: &str, ranges: &[(u32, u32)]) {
    for (a, b) in ranges {
        let path = format!("{}_{:08x}.bin", prefix, a);
        if let Ok(data) = fs::read(Path::new(&path)) {
            mem.load(&data, *a);
        }
    }
}
