mod arm;
mod lcd;
mod memory;
mod nand;
mod qmdc;
static CNT_PA: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static CNT_VAL: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

use arm::Arm;
use memory::Memory;
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::time::Instant;

fn parse_u32(s: &str) -> u32 {
    let s = s.trim_start_matches("0x");
    u32::from_str_radix(s, 16).unwrap_or(0)
}

fn load_partitions(mem: &mut Memory, dir: &str, boot: &[u8]) {
    let cla = Path::new(dir).join("C3520XFLJ1_BIN/C3520XELaFleur.cla");
    let q = qmdc::Qmdc::new(boot);
    if let Ok(rd) = fs::read_dir(&cla) {
        for e in rd.flatten() {
            let path = e.path();
            let Ok(raw) = fs::read(&path) else { continue };
            if raw.len() < 0x2000 { continue; }
            let phys = u32::from_le_bytes(raw[8..12].try_into().unwrap());
            let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
            let dec = Path::new("/tmp/opencode/dec").join(format!("{}.bin", name));
            let data = if dec.exists() {
                fs::read(&dec).unwrap_or_else(|_| q.unpack_cla(&raw))
            } else {
                q.unpack_cla(&raw)
            };
            mem.load(&data, phys);
            println!("loaded {:<24} {:08x}..{:08x} ({} bytes)", name, phys,
                     phys.wrapping_add(data.len() as u32), data.len());
        }
    }
}

fn decompress_image_rgb565(dir: &str, boot: &[u8]) -> Vec<u8> {
    if let Ok(v) = fs::read("/tmp/opencode/image_rgb565.bin") { return v; }
    let bmp_path = Path::new(dir).join("C3520XFLJ1_BIN/C3520XELaFleur.bmp");
    let Ok(raw) = fs::read(bmp_path) else { return Vec::new(); };
    let q = qmdc::Qmdc::new(boot);
    let dec = q.unpack_bmp(&raw);
    if dec.len() < 54 || &dec[..2] != b"BM" { return Vec::new(); }
    let off = u32::from_le_bytes(dec[10..14].try_into().unwrap()) as usize;
    let w = i32::from_le_bytes(dec[18..22].try_into().unwrap());
    let h = i32::from_le_bytes(dec[22..26].try_into().unwrap());
    if w != 240 || h != 320 { return Vec::new(); }
    let mut out = Vec::with_capacity(153_600);
    for y in (0..h as usize).rev() {
        for x in 0..w as usize {
            let i = off + (y * w as usize + x) * 3;
            if i + 2 >= dec.len() { break; }
            let r = dec[i + 2]; let g = dec[i + 1]; let b = dec[i];
            let v = (((r as u16 & 0xF8) << 8) | ((g as u16 & 0xFC) << 3) | (b as u16 >> 3));
            out.extend_from_slice(&v.to_le_bytes());
        }
    }
    out
}

fn boot_screen(dir: &str, boot: &[u8], gui: bool) {
    let act_path = Path::new(dir).join("C3520XFLJ1_BIN/C3520XELaFleur.bootlcdact");
    let Ok(act) = fs::read(act_path) else { return; };
    let mut bm = Memory::new(dir);
    if act.len() > 0x2000 {
        bm.load(&act[0x2000..], 0xC000_0000);
        bm.load(&act[0x2000..], 0x0);
    }
    let mut lc = Arm::new(&mut bm);
    lc.r[15] = 0xC000_0000;
    lc.r[13] = 0xC1AB_0000;
    lc.cpsr = 0xD3;
    lc.irq_limit = 0;
    for _ in 0..200_000 { if lc.halted { break; } lc.step(false); }
    println!("[LCDACT] executed {} instr, halted={}", lc.instructions, lc.halted);

    let rgb = decompress_image_rgb565(dir, boot);
    bm.write32(0x7400_2000, 0x2C);
    for p in rgb.chunks_exact(2) {
        let v = u16::from_le_bytes([p[0], p[1]]) as u32;
        bm.write32(0x7400_2100, v);
    }
    println!("[BOOTSCREEN] commands={} pixels={}", bm.lcd.commands, bm.lcd.pixels);
    let _ = bm.lcd.save_png("/tmp/opencode/bootscreen.png");
    if gui {
        println!("[GUI] кадр сохранён: /tmp/opencode/bootscreen.png (240x320 RGB565)");
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = args.get(1).cloned().unwrap_or_else(|| "/home/bogdan/Downloads/C3520XELJ1".into());
    let steps: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(500);
    let quiet = args.get(3).map(|s| s == "quiet").unwrap_or(false);
    let trace_io = args.iter().any(|a| a == "--trace-mmio");
    let trace_all = args.iter().any(|a| a == "--trace-all");
    let start_root = args.iter().any(|a| a == "--start-root");
    let from_main = args.iter().any(|a| a == "--from-main");
    let gui = args.iter().any(|a| a == "--gui");
    let skip_log = args.iter().any(|a| a == "--skip-log");

    let boot_path = Path::new(&dir).join("C3520XFLJ1_BIN/C3520XELaFleur.bootloader");
    let boot = fs::read(&boot_path).unwrap_or_default();

    let mut mem = Memory::new(&dir);
    if !start_root {
        mem.load(&boot, 0);
        mem.load(&boot, 0x2FFE_0000);
    } else {
        let root_path = Path::new(&dir).join("C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_ROOT");
        if let Ok(root) = fs::read(&root_path) {
            let n = root.len().min(0x10000);
            mem.load_at(&root, 0x2000, n, 0);
        }
    }
    load_partitions(&mut mem, &dir, &boot);
    if start_root { boot_screen(&dir, &boot, gui); }

    // Все подтверждённые MMIO-ответы и boot-mode флаги
    mem.set_mmio(0x7000_2188, 0x8_0000);
    mem.set_mmio(0x7001_B038, 0xFFFF_FFFF);
    mem.set_mmio(0x7001_B03C, 0xFFFF_FFFF);
    mem.set_mmio(0x7001_B014, 0x4_0000);
    mem.set_mmio(0x7001_B034, 0x80);
    mem.write32(0xC3FF_F15C, 1);
    mem.write32(0xC99A_0C68, 0xC046_B861);

    mem.write16(0xC1B5_1BEC, 0x0090);
    for k in 0..0x90u32 { mem.write32(0xC1C7_6BE0 + k*8, 0xC1AB_0000); mem.write32(0xC1C7_6BE4 + k*8, 1); }
    mem.write32(0xC1AB_0000, 0xC1AB_0200);
    for k in 0..64u32 { mem.write32(0xC1AB_0200 + k*4, 0xC1AB_0200); }
    mem.write32(0xC1F2_A254, 0xC1AB_0000);
    let mut cpu = Arm::new(&mut mem);
    cpu.trace_all = trace_all;
    cpu.irq_limit = 0;

    if from_main {
        if let Ok(text) = fs::read_to_string("/tmp/opencode/ref_main_entry.bin") {
            let v = qmdc::parse_ref_lines(&text);
            if v.len() >= 17 {
                for i in 0..16 { cpu.r[i] = v[i]; }
                cpu.cpsr = v[16];
                if v.len() >= 19 {
                    for i in 0..16 { cpu.cp15[i] = v[17 + i]; }
                }
                if v.len() >= 35 {
                    cpu.itcm_reg = v[33]; cpu.dtcm_reg = v[34];
                } else {
                    cpu.itcm_reg = cpu.cp15[9]; cpu.dtcm_reg = 0;
                }
            }
        }
        let ranges = [
            (0x0000_0000u32, 0x0001_0000u32),
            (0xC000_0000, 0xC002_0000), (0xC002_0000, 0xC200_0000),
            (0xC200_0000, 0xC400_0000), (0xC990_0000, 0xC9C0_0000),
            (0x3000_0000, 0x3002_0000), (0x7000_0000, 0x7002_0000),
            (0x7400_0000, 0x7402_0000), (0x7D80_0000, 0x7D82_0000),
        ];
        qmdc::load_ranges(cpu.mem, "/tmp/opencode/ref_main", &ranges);
    }
    if start_root && !from_main {
        cpu.r[15] = 0xC000_0000;
        cpu.r[13] = 0x4000_1000;
        cpu.cpsr = 0xD3;
    }

    println!("[VAPA] count_va=c9b51bec -> {:08x}; table_va=c9c76be0 -> {:08x}; fb={:08x}",
        cpu.va2pa(0xC9B5_1BEC), cpu.va2pa(0xC9C7_6BE0), cpu.va2pa(0xC9F2_A254));
    let mut hist: HashMap<u32, u64> = HashMap::new();
    let mut service10_done = false;
    let mut bootmode_done = false;
    let mut skip_count = 0u32;
    let mut irq_injected = false;
    let mut saw_os_init = false;
    let mut saw_dispatch = false;
    let t0 = Instant::now();

    for i in 0..steps {
        if cpu.halted { break; }
        let pc = cpu.r[15];
        if (start_root || from_main) && (i & 63) == 0 {
            *hist.entry(pc & !1).or_insert(0) += 64;
        }
        if from_main || start_root {
            if pc == 0xC022_8680 && cpu.r[0] == 0xA && !service10_done {
                service10_done = true;
                cpu.mem.write32(0xC1AB_0100, 0xC0D4_CDCC);
                cpu.mem.write32(0xC1AB_0000, 0xC1AB_0100);
                cpu.mem.write8(0xC1AB_0008, 1);
                cpu.mem.write32(0xC1AB_0010, 0xC1AB_0000);
                cpu.mem.write32(0xC1C7_6C30, 0xC1AB_0010);
                if !quiet { println!("[SERVICE10] step={}", cpu.instructions); }
            }
            if cpu.instructions == 1000 && !bootmode_done {
                bootmode_done = true;
                cpu.mem.write32(0xC1CA_0178, 0x2000_0000);
            }
            if skip_log && pc == 0xC035_CB60 && skip_count < 3 {
                skip_count += 1;
                cpu.r[15] = 0xC035_CC06;
            }
        }
        if from_main && pc == 0xC043_3714 { cpu.mem.write16(0xC1B5_1BEC, 0x0090); }
        cpu.step(!quiet && i < 60);
        if pc == 0xC033_219C && !saw_os_init { saw_os_init = true; println!("[OSINIT] step={}", i); }
        if pc == 0xC046_0FF8 && !saw_dispatch { saw_dispatch = true; println!("[DISPATCH] step={}", i); }
        if i == 1_000_000 { cpu.mem.io_seen.clear(); }
        if !irq_injected && (cpu.cpsr & 0x80) == 0 {
            irq_injected = true;
            cpu.mem.set_mmio(0x7000_102C, 1);
            cpu.inject_irq();
            println!("[IRQINJECT] step={} lr={:08x}", cpu.instructions, cpu.r[14]);
        }
        if gui && i % 2000 == 0 && i > 0 {
            // в Rust-порту GUI сохраняет кадр; живое окно опционально
        }
    }
    let elapsed = t0.elapsed().as_secs_f64();
    let mips = cpu.instructions as f64 / elapsed / 1_000_000.0;

    if !quiet {
        println!();
        println!("vectors: 00={:08x} 18={:08x} 20={:08x} 24={:08x} 28={:08x}",
            cpu.mem.read32(0), cpu.mem.read32(0x18), cpu.mem.read32(0x20),
            cpu.mem.read32(0x24), cpu.mem.read32(0x28));
    }
    let pa = cpu.va2pa(0xCA2C_CABC);
    let mut live = 0;
    for k in 0..40 {
        let v = cpu.mem.read32(pa.wrapping_add((k * 0x14) as u32).wrapping_add(4));
        if v & 0xF000_0000 == 0xC000_0000 { live += 1; }
    }
    println!("[TAB40] va=ca2ccabc pa={:08x} live={}/40", pa, live);
    println!("[LCD] commands={} pixels={} frames={}", cpu.mem.lcd.commands,
             cpu.mem.lcd.pixels, cpu.mem.lcd.frames);
    println!("[IRQOPEN] steps={} halted={} reason={} os_init={} dispatch={}",
             cpu.irq_open_steps, cpu.halted, cpu.halt_reason, saw_os_init, saw_dispatch);
    println!("[WFI] count={} data_abort={} prefetch_abort={}", cpu.wfi_count,
             cpu.data_aborts, cpu.prefetch_aborts);
    println!("[MIPS] {:.2} million instr/sec ({} instr in {:.3}s)", mips, cpu.instructions, elapsed);
    println!("{}", cpu.dump_regs());
    let top: Vec<String> = {
        let mut v: Vec<_> = hist.into_iter().collect();
        v.sort_by(|a, b| b.1.cmp(&a.1));
        v.into_iter().take(15).map(|(pc, n)| format!("{:08x}:{}", pc, n)).collect()
    };
    println!("[HOTPC] {}", top.join(" "));

    if trace_io {
        let mut keys: Vec<u32> = cpu.mem.io_seen.keys().copied().collect();
        keys.sort_unstable();
        for a in keys {
            let (r, w) = cpu.mem.io_seen[&a];
            println!("[MMIO] {:08x} reads={} writes={}", a, r, w);
        }
    }
    if start_root || from_main {
        let _ = cpu.mem.lcd.save_png("/tmp/opencode/bootscreen_rust.png");
    }
}
