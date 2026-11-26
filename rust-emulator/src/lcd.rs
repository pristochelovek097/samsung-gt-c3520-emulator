//! LcdController 240x320 RGB565 + MMIO 0x74002000/0x74002100/0x7401900E.
pub struct LcdController {
    pub frame: Vec<u16>,
    pub cursor: usize,
    pub command: u32,
    pub commands: u64,
    pub pixels: u64,
    pub frames: u64,
    pub writes: Vec<(u32, u32)>, // последние записи для логов
}

impl LcdController {
    pub fn new() -> Self {
        Self { frame: vec![0; 240 * 320], cursor: 0, command: 0,
               commands: 0, pixels: 0, frames: 0, writes: Vec::new() }
    }

    pub fn reset(&mut self) {
        self.cursor = 0; self.command = 0; self.commands = 0;
        self.pixels = 0; self.frames = 0; self.frame.fill(0);
    }

    pub fn write(&mut self, addr: u32, value: u32) {
        if self.writes.len() < 4096 { self.writes.push((addr, value)); }
        if addr == 0x7400_2000 {
            self.command = value & 0xFFFF;
            self.commands += 1;
            if self.command == 0x2C || self.command == 0x38 { self.cursor = 0; }
        } else if addr == 0x7400_2100 {
            self.push((value & 0xFFFF) as u16);
        } else if (addr & !1) == 0x7401_900C {
            if (addr & 1) != 0 { self.push((value & 0xFFFF) as u16); }
            else {
                self.command = value & 0xFFFF;
                self.commands += 1;
                if self.command == 0x2C || self.command == 0x38 { self.cursor = 0; }
            }
        } else if (addr & 0xFFFF_0000) == 0x7402_0000 {
            let off = addr & 0xFFFF;
            if off == 0x28 {
                if (self.command & 0x100) != 0 { self.push((value & 0xFFFF) as u16); }
                else { self.command = value; self.commands += 1; }
            } else if off == 0x2C { self.command = value; self.commands += 1; }
            else if off == 0x30 { self.cursor = value as usize; }
        }
    }

    pub fn push(&mut self, rgb565: u16) {
        if self.cursor < self.frame.len() {
            self.frame[self.cursor] = rgb565;
            self.cursor += 1;
            self.pixels += 1;
            if self.cursor == self.frame.len() { self.frames += 1; }
        }
    }

    pub fn test_pattern(&mut self) {
        for y in 0..320usize {
            let r = (y * 31 / 320) as u16;
            let g = (y * 63 / 320) as u16;
            let b = 31 - (y * 31 / 320) as u16;
            for x in 0..240usize {
                let border = x < 4 || x >= 236 || y < 4 || y >= 316;
                self.frame[y * 240 + x] = if border { 0xFFFF } else { (r << 11) | (g << 5) | b };
            }
        }
    }

    pub fn save_png(&self, path: &str) -> std::io::Result<()> {
        let w = 240usize; let h = 320usize;
        let mut raw = Vec::with_capacity((w * 3 + 1) * h);
        for y in 0..h {
            raw.push(0);
            for x in 0..w {
                let p = self.frame[y * w + x];
                let r = ((p >> 11) & 0x1F) as u8;
                let g = ((p >> 5) & 0x3F) as u8;
                let b = (p & 0x1F) as u8;
                raw.push((r << 3) | (r >> 2));
                raw.push((g << 2) | (g >> 4));
                raw.push((b << 3) | (b >> 2));
            }
        }
        let mut z = Vec::new();
        z.push(0x78); z.push(0x01);
        // stored deflate blocks
        let mut pos = 0;
        while pos < raw.len() {
            let n = (raw.len() - pos).min(65535);
            let final_block = (pos + n == raw.len()) as u8;
            z.push(final_block);
            z.extend_from_slice(&(n as u16).to_le_bytes());
            z.extend_from_slice(&(!(n as u16)).to_le_bytes());
            z.extend_from_slice(&raw[pos..pos + n]);
            pos += n;
        }
        let mut adler = 1u32;
        for byte in &raw {
            adler = (adler + *byte as u32) % 65521;
        }
        let mut b2 = 0u32;
        for byte in &raw {
            b2 = (b2 + adler) % 65521;
        }
        z.extend_from_slice(&((b2 << 16) | adler).to_be_bytes());

        let mut png = Vec::new();
        png.extend_from_slice(b"\x89PNG\r\n\x1a\n");
        let mut chunk = |kind: &[u8; 4], data: &[u8], out: &mut Vec<u8>| {
            out.extend_from_slice(&(data.len() as u32).to_be_bytes());
            out.extend_from_slice(kind);
            out.extend_from_slice(data);
            let mut crc_data = Vec::new(); crc_data.extend_from_slice(kind); crc_data.extend_from_slice(data);
            out.extend_from_slice(&crc32(&crc_data).to_be_bytes());
        };
        let mut ihdr = Vec::new();
        ihdr.extend_from_slice(&(w as u32).to_be_bytes());
        ihdr.extend_from_slice(&(h as u32).to_be_bytes());
        ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
        chunk(b"IHDR", &ihdr, &mut png);
        chunk(b"IDAT", &z, &mut png);
        chunk(b"IEND", &[], &mut png);
        std::fs::write(path, png)
    }
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for b in data {
        crc ^= *b as u32;
        for _ in 0..8 {
            crc = if (crc & 1) != 0 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
        }
    }
    !crc
}
