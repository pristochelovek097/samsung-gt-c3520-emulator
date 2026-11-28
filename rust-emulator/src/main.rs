mod arm;
mod lcd;
mod memory;
mod nand;
mod qmdc;
static CNT_PA: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static CNT_VAL: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

use arm::Arm;
use memory::Memory;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::Path;
use std::time::Instant;
use minifb::{Key, Window, WindowOptions};

/// ROM String Sniffer: печатная ASCII-строка из регистра BL/BLX.
fn rom_string(mem: &mut Memory, va: u32) -> Option<String> {
    if !(0xC000_0000..0xC200_0000).contains(&va) { return None; }
    let mut s = Vec::new();
    for k in 0..80u32 {
        let c = mem.read8(va.wrapping_add(k)) as u8;
        if c == 0 { break; }
        if !(0x20..=0x7E).contains(&c) { return None; }
        s.push(c);
    }
    if s.len() >= 4 { Some(String::from_utf8_lossy(&s).into_owned()) } else { None }
}

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
    let mut window: Option<Window> = if gui {
        Window::new("GT-C3520 LCD", 240, 320, WindowOptions {
            scale: minifb::Scale::X2, ..WindowOptions::default()
        }).ok()
    } else { None };
    let mut fb: Vec<u32> = vec![0; 240 * 320];
    let mut gui_open = gui && window.is_some();
    let skip_log = args.iter().any(|a| a == "--skip-log");
    let workarounds = args.iter().any(|a| a == "--boot-workarounds")
        || std::env::var("QMDC_WORKAROUNDS").map(|v| v == "1").unwrap_or(false);
    memory::WORKAROUNDS.store(workarounds, std::sync::atomic::Ordering::Relaxed);

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

    mem.write16(0xC1B5_1BEC, 0x0092);
    mem.write8(0xC1AC_619D, 1);   // флаг 0xC04571A8 (byte == 1)
    mem.write8(0xC1B5_1B47, 1);
    mem.write16(0xC1B5_1B48, 0x900);   // счётчик каналов для 0xC0419D4C
    for k in 0..0x92u32 { mem.write32(0xC1C7_6BE0 + k*8, 0xC1AB_0000); mem.write32(0xC1C7_6BE4 + k*8, 1); }
    mem.write32(0xC1AB_0000, 0xC1AB_0200);
    for k in 0..64u32 { mem.write32(0xC1AB_0200 + k*4, 0xC1AB_0200); }
    mem.write32(0xC1F2_A254, 0xC1AB_0000);
    // Сервис №10: [[entry]] == 0xC0D4CDCC, [entry+8] == 1
    mem.write32(0xC1D5_0000, 0xC9D5_0040);
    mem.write8(0xC1D5_0008, 1);
    mem.write32(0xC1D5_0040, 0xC0D4_CDCC);
    mem.write8(0xC1D5_0048, 1);   // байт +8 у [[entry]]
    mem.write8(0xC1D5_0054, 1);   // байт [obj+12*n+8] при n=1
    // Остальные сервисы: [entry] = obj, [entry+4]=1, байт obj+20 == 1
    mem.write32(0xC1D5_0100, 0xC9D5_0200);
    mem.write8(0xC1D5_0104, 1);
    mem.write8(0xC1D5_0214, 1);
    mem.write32(0xC1D5_0200, 0xC9D5_0300);
    for k in 0..0x92u32 {
        let e = if k == 0xA { 0xC9D5_0000u32 } else { 0xC9D5_0100u32 };
        mem.write32(0xC1C7_6BE0 + k*8, e);
        mem.write32(0xC1C7_6BE4 + k*8, 1);
    }
    mem.write32(0xC1F2_A254, 0xC9D5_0100);
    let mut cpu = Arm::new(&mut mem);
    cpu.strict_mmu = args.iter().any(|a| a == "--strict-mmu");
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
    let mut irq_injected = false;
    let mut sys_open_count = 0u64;
    let mut fmt_iters = 0u32;
    let mut fmt_bailed = false;
    let mut out_iters = 0u32;
    let mut re_count = 0u32;
    let mut timer_irqs = 0u64;
    let mut dump_seen = false;
    let mut irqtrace = 0u32;
    let mut strings_seen: HashSet<String> = HashSet::new();
    let mut idle_hits = 0u64;
    let mut nand_id_cmd = false;
    let mut vec_restores = 0u64;
    let mut dbg_once = false;
    let mut saw_os_init = false;
    let mut saw_dispatch = false;
    let t0 = Instant::now();

    // DMM1: валидный дескриптор пула — байт +2 (>=1) требует проверка
    // 0xC042E14A; без него дmm1excp.c вызывается по кругу.
    cpu.mem.write8(0xC9AF_ED1A, 0xFF);
    cpu.mem.write8(0xC9B5_1B47, 0x20);
    // Region$$Table ROOT 0x2C08: src=0xC0008D04, dst VA 0x00010000,
    // dst_end=0x000108B8 → длина 0x8B8; PA-адрес 0x2FFE0000.
    if let Ok(rootd) = fs::read("/tmp/opencode/dec/ER_FLASH_CB_ROOT.bin") {
        let off = 0x8D04usize;
        let n = 0x8B8usize.min(rootd.len().saturating_sub(off));
        for k in 0..n { cpu.mem.write8(0x2FFE_0000u32.wrapping_add(k as u32), rootd[off+k] as u32); }
        println!("[ITCMOVL] {} bytes", n);
    }
    // Инициализация контекста 0xCA7D9650 (как в 0xC0A67DB8): база кучи —
    // свободный SDRAM выше .bss (~0xCA7E0000), зазор до стека 0x10000.
    let p16 = cpu.va2pa(0xCA7D_9664); cpu.mem.write32(p16, if workarounds { 0xC9B0_0000 } else { 0xCA80_0000 }); // база кучи
    let p1c = cpu.va2pa(0xCA7D_966C); cpu.mem.write32(p1c, 0x0001_0000);
    let p18 = cpu.va2pa(0xCA7D_9668); cpu.mem.write32(p18, 0x40);
    let p10 = cpu.va2pa(0xCA7D_9660); cpu.mem.write32(p10, 1);
    if workarounds {
        // BML-геометрия: 4 сектора по 512 байт в странице 0x800.
        // Прошивка делит 0x800 на этот байт в 0xC0A34AF0; нуль даёт
        // деление на ноль -> fatal error 2 "Abnormal termination".
        let p = cpu.va2pa(0xC9EF_740D); cpu.mem.write8(p, 4);
    }
    if workarounds {
        // mgrpaging0init.c: драйвер читает 0x68-байтные структуры тома
        // из RAM-образа 0xC9EF5ACC + block*0x800 и копирует их через
        // 0xC9EF7384. Заполняем подлинной сигнатурой и чексуммой.
        let base = cpu.va2pa(0xC9EF_5ACC);
        let extra = cpu.va2pa(0xC9EF_7384);
        for blk in 0u32..0x400u32 {
            let p = if blk == 0 { extra } else { base.wrapping_add(blk.wrapping_mul(0x800)) };
            cpu.mem.write32(p, 0x0102_0304);
            cpu.mem.write32(p.wrapping_add(8), 1);
            cpu.mem.write32(p.wrapping_add(0x64), 0x0102_0304);
            let mut sum = 0u32;
            for k in 0..0x68u32 { sum = sum.wrapping_add(cpu.mem.read8(p.wrapping_add(k)) as u32); }
            cpu.mem.write8(p.wrapping_add(0x60), (0u32.wrapping_sub(sum)) as u32);
        }
    }
    let mut pg_src = 0u32;
    let mut pg_dst = 0u32;
    cpu.mem.watch_addr = 0xC02D_2954;
    for i in 0..steps {
        if cpu.halted { break; }
        let pc = cpu.r[15];
        cpu.mem.watch_pc = pc;
        if i == 1000 { cpu.mem.code_watch_enabled = true; cpu.mem.text_readonly = true; }
        if i == 421_000_000 { cpu.mem.pt_watch_enabled = true; }
        // HOTPC: считаем редко (раз в 4096 тактов), чтобы не тормозить цикл
        if (start_root || from_main) && (i & 4095) == 0 {
            *hist.entry(pc & !1).or_insert(0) += 4096;
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
        }
        if from_main && pc == 0xC043_3714 {
            // Таблица сервисов пересоздаётся ядром: восстанавливаем честные объекты
            // в верхней SDRAM (VA 0xC9D50000 / 0xC9D50100 -> PA 0xC1D50000 / 0xC1D50100).
            cpu.mem.write16(0xC1B5_1BEC, 0x0092);
            let pflag = cpu.va2pa(0xC9AC_619D);
            cpu.mem.write8(pflag, 1);
            cpu.mem.write8(0xC1B5_1B47, 1);
            cpu.mem.write16(0xC1B5_1B48, 0x900);
            for k in 0..0x92u32 {
                let e = if k == 0xA { 0xC9D5_0000u32 } else { 0xC9D5_0100u32 };
                cpu.mem.write32(0xC1C7_6BE0 + k*8, e);
                cpu.mem.write32(0xC1C7_6BE4 + k*8, 1);
            }
            cpu.mem.write32(0xC1D5_0000, 0xC9D5_0040);
            cpu.mem.write8(0xC1D5_0008, 1);
            cpu.mem.write32(0xC1D5_0040, 0xC0D4_CDCC);
            cpu.mem.write8(0xC1D5_0048, 1);
            cpu.mem.write8(0xC1D5_0054, 1);
            cpu.mem.write32(0xC1D5_0100, 0xC9D5_0200);
            cpu.mem.write8(0xC1D5_0104, 1);
            cpu.mem.write8(0xC1D5_0214, 1);
        }
        // Эмуляция пула блоков: честные данные для аллокатора 0xC0411BDE.
        if from_main && pc == 0xC041_1BDE && !dbg_once {
            dbg_once = true;
            let p_base = cpu.va2pa(0xC9B5_1B84);
            let p_n0 = cpu.va2pa(0xC9B5_1BDE);
            let p_n1 = cpu.va2pa(0xC9B5_1B46);
            let base = cpu.mem.read32(p_base);
            let bp = cpu.va2pa(base);
            cpu.mem.write16(p_n0, 0);
            cpu.mem.write8(p_n1, 0x20);
            for k in 0..0x20u32 {
                cpu.mem.write32(bp + k*0x1c, 0xC9D5_1000 + k*0x40);
                cpu.mem.write32(bp + k*0x1c + 8, 0x40);
                cpu.mem.write8(0xC1D5_1000 + k*0x40, k);
            }
        }
        if from_main && pc == 0xC046_B842 { out_iters = 0; }
        if from_main && pc == 0xC046_B84A {
            out_iters += 1;
            if out_iters > 8 { cpu.r[4] = cpu.r[6]; }
        }
        // (r6=0) и переходим на нормальный выход форматтера.
        // Функциональный указатель уводит в данные вместо кода:
        // Immediate return из callback по LR с восстановлением состояния.
        if from_main && ((0x2029_0000..=0x2029_FFFF).contains(&pc)
            || (0xBE50_0000..=0xBE50_FFFF).contains(&pc)) {
            let lr = cpu.r[14];
            if lr & 1 != 0 { cpu.cpsr |= 0x20; cpu.r[15] = lr & !1; }
            else { cpu.cpsr &= !0x20; cpu.r[15] = lr & !3; }
        }
        // Log_Printf: источник символов застрял
        if from_main && pc == 0xC046_B534 { fmt_iters = 0; fmt_bailed = false; }
        if from_main && pc == 0xC046_B548 && !fmt_bailed {
            fmt_iters += 1;
            if fmt_iters > 2 {
                fmt_bailed = true;
                cpu.r[6] = 0;
                cpu.r[15] = 0xC046_B560;
                println!("[FMTBAIL] step={} lr={:08x} iters={}", i, cpu.r[14], fmt_iters);
            }
        }
        // Повреждённый указатель функции вывода в	ctxе:.return по эпилогу
        if from_main && (0xC9AF_D000..0xC9AF_D900).contains(&pc) && (cpu.cpsr & 0x20) == 0 {
            cpu.cpsr |= 0x20;
            cpu.r[15] = 0xC046_B85C;
            println!("[FMTOUT] step={} pc={:08x}", i, pc);
        }
        // Венеер 0xC02D2954 (ldr pc,[pc,#-4] → 0xC0038070) затирается
        // нулями при инициализации канала — восстанавливаем его перед BLX.
        // Периодический таймерный IRQ после 1M шагов (src0 → idx 0x40, безопасный).
        if from_main && i > 1_000_000 && (cpu.cpsr & 0x80) == 0
            && (cpu.cpsr & 0x1F) != 0x12 && (i % 50_000) == 0 {
            cpu.mem.set_mmio(0x7000_102C, if workarounds { 0 } else { 17 });
            cpu.inject_irq();
            timer_irqs += 1;
        }
        // Вектор IRQ в ITCM может быть затёрт ядром — восстанавливаем.
        if from_main && cpu.mem.read32(0x18) == 0 {
            cpu.mem.write32(0x18, 0xE59F_F330);
            cpu.mem.write32(0x1C, 0xE59F_F32C);
            cpu.mem.write32(0x350, 0xC0A9_6454);
            vec_restores += 1;
        }
        if from_main && i == 557_274 {
            let fp = cpu.va2pa(0xCA2C_C558);
            let src = cpu.va2pa(0xC9AF_CE4C);
            print!("[IRQTB] fp={:08x}", fp);
            for k in 0..64u32 {
                let cb = cpu.mem.read32(fp.wrapping_add(k*8));
                if cb != 0 { print!(" idx{}:{:08x}", k, cb); }
            }
            println!();
            print!("[SRCTB] src={:08x}", src);
            for k in 0..64u32 {
                let v = cpu.mem.read8(src.wrapping_add(k*8+5));
                if v != 0 { print!(" idx{}:{}", k, v); }
            }
            println!();
            for &k in &[2u32,9,10,18,19,21,22,24,30,36] {
                let ctx = cpu.mem.read32(fp.wrapping_add(k*8+4));
                let ca = cpu.va2pa(ctx);
                print!("[CTX] idx{} ctx={:08x}", k, ctx);
                for j in 0..4u32 { print!(" {:08x}", cpu.mem.read32(ca.wrapping_add(j*4))); }
                println!();
            }
        }
        if from_main && pc == 0xC041_72E0 {
            let pl = cpu.va2pa(0xC9B5_1B47);
            cpu.mem.write8(pl, 0x20);
        }
        if from_main && pc == 0xC042_E114 {
            let pd = cpu.va2pa(0xC9AF_ED18);
            cpu.mem.write8(pd.wrapping_add(2), 0xFF);
        }
        // Semihosting SYS_OPEN (svc #0xab, r0=1) из _initio:
        // возвращаем валидный дескриптор и продолжаем без SVC-ловушки.
        if from_main && pc == 0xC002_0010 && cpu.r[0] == 1 {
            cpu.r[0] = 1;
            cpu.r[15] = 0xC002_0012;
            sys_open_count += 1;
        }
        // Таблица C++-конструкторов после MMU-remap VA 0xC11BFF48 → PA 0xC00BFF48:
        // копируем исходные слова из ROM-образа в перемапленную страницу.
        // Вызов callback'а прошивкой消费ирует завершение — снимаем бит 10.
        if from_main && workarounds {
            if cpu.mem.nand.irq_delay > 0 { cpu.mem.nand.irq_delay -= 1; }
            if cpu.mem.nand.irq_delay == 0 && cpu.mem.nand.irq_pending {
                cpu.mem.nand.irq_pending = false;
                cpu.mem.nand.irq_servicing = true;
                let pr0=cpu.mem.nand.pio_remaining; let st0=cpu.mem.read32(0x7400_202C);
                println!("[IRQDEL] step={} pio={} st={:08x}", i, pr0, st0);
                cpu.mem.set_mmio(0x7000_102C, 17);
                cpu.inject_irq();
            }
        }
        // NAND Read ID (0x90): DMA завершился — записываем канонический ID в буфер.
        if from_main && pc == 0xC009_60E4 { nand_id_cmd = true; }
        if from_main && nand_id_cmd && pc == 0xC009_6172 {
            nand_id_cmd = false;
            let p = cpu.va2pa(cpu.r[3]);
            for (k,b) in [0xECu8,0xD3,0x10,0x45,0x00].iter().enumerate() { cpu.mem.write8(p.wrapping_add(k as u32), *b as u32); }
        }
        // Диагностика аварийного дампера SysDump (файл/строка/аргументы).
        if from_main && !dump_seen && (pc == 0xC045_7084 || pc == 0xC046_423C) {
            dump_seen = true;
            println!("[DUMP] step={} pc={:08x} lr={:08x} sp={:08x} cpsr={:08x} r0={:08x} r1={:08x} r2={:08x} r3={:08x}", i,pc,cpu.r[14],cpu.r[13],cpu.cpsr,cpu.r[0],cpu.r[1],cpu.r[2],cpu.r[3]);
            for &r in &[0usize,1,2,3] {
                let v = cpu.r[r];
                if v >= 0xC000_0000 && v < 0xC400_0000 {
                    let pv = cpu.va2pa(v); let mut st=Vec::new();
                    for k in 0..64u32 { let c=cpu.mem.read8(pv.wrapping_add(k)) as u8; if c==0 || c>0x7e {break;} st.push(c); }
                    if !st.is_empty() { println!("[DUMPSTR] r{}={:08x} '{}'", r, v, String::from_utf8_lossy(&st)); }
                }
            }
            let psp = cpu.va2pa(cpu.r[13]); let mut raw=Vec::new();
            for k in 0..0x120u32 { raw.push(cpu.mem.read8(psp.wrapping_add(k)) as u8); }
            let txt: String = raw.iter().map(|&c| if (0x20..=0x7e).contains(&c) { c as char } else { '.' }).collect();
            println!("[DUMPSTACK] {}", txt);
        }
        if from_main && workarounds && i > 756_446 && irqtrace < 40 {
            match pc {
                0x0000_0018 | 0xC0A9_6454 | 0xC041_B2F8 | 0xC041_B302 | 0xC041_B306 | 0xC041_B310 | 0xC041_B31C | 0xC041_B348 | 0xC041_B354 | 0xC041_B364 | 0xC041_134A | 0xC00C_C2E0 => {
                    irqtrace+=1;
                    let st=cpu.mem.read32(0x7400_202C);
                    let g=cpu.va2pa(0xCA76_8290); let gv=cpu.mem.read8(g);
                    let f=cpu.va2pa(0xCA75_F810); let fv=cpu.mem.read8(f);
                    let ip=cpu.mem.nand.irq_pending; let dl=cpu.mem.nand.irq_delay; let pr=cpu.mem.nand.pio_remaining;
                    let it=cpu.mem.read32(0x7000102C);
                    println!("[IRQTR] step={} pc={:08x} lr={:08x} st={:08x} g8290={:02x} flag={:02x} irqp={} delay={} pio={} intc={:08x} srv={}",i,pc,cpu.r[14],st,gv,fv,ip,dl,pr,it,cpu.mem.nand.irq_servicing);
                }
                _=>{}
            }
        }
        // ROM String Sniffer (под флагом): ловим строки в аргументах вызовов.
        if from_main && workarounds && i >= 755_000 && (i < 1_000_000 || (i & 4095) == 0) {
            let regs = [cpu.r[0], cpu.r[1], cpu.r[2], cpu.r[3]];
            let mut news = Vec::new();
            for &r in &regs {
                if let Some(st) = rom_string(&mut cpu.mem, r) {
                    if strings_seen.insert(st.clone()) { news.push(st); }
                }
            }
            for st in news { println!("[FW-LOG] step={} pc={:08x} \"{}\"", i, pc, st); }
        }
        if (pc & !1) == 0xC002_B52A { idle_hits += 1; }
        // C007A570: операция драйвера 0x12 (чтение 0x800 байт тома).
        // Возвращаем успех и переносим структуру из RAM-образа в буфер.
        if from_main && workarounds && pc == 0xC007_A570 { pg_src = cpu.r[0]; pg_dst = cpu.r[1]; }
        if from_main && workarounds && pc == 0xC007_A57C {
            cpu.r[0] = 0;
            let blk = pg_src >> 17;
            let src = cpu.va2pa(0xC9EF_5ACCu32.wrapping_add(blk.wrapping_mul(0x800)));
            let dst = cpu.va2pa(pg_dst);
            for k in 0..0x800u32 { let b = cpu.mem.read8(src.wrapping_add(k)); cpu.mem.write8(dst.wrapping_add(k), b); }
        }
        cpu.step(!quiet && i < 60);
        if from_main && pc == 0xC036_AB2C { re_count += 1; }
        if pc == 0xC033_219C && !saw_os_init { saw_os_init = true; println!("[OSINIT] step={}", i); }
        if pc == 0xC046_0FF8 && !saw_dispatch { saw_dispatch = true; println!("[DISPATCH] step={}", i); }
        if gui_open && i % 2000 == 0 && i > 0 {
            if let Some(ref mut w) = window {
                if !w.is_open() || w.is_key_down(Key::Escape) {
                    gui_open = false;
                } else {
                    for (d, &p) in fb.iter_mut().zip(cpu.mem.lcd.frame.iter()) {
                        let r = ((p >> 11) & 0x1F) as u32;
                        let g = ((p >> 5) & 0x3F) as u32;
                        let b = (p & 0x1F) as u32;
                        *d = (r << 19) | (g << 10) | b;
                    }
                    let _ = w.update_with_buffer(&fb, 240, 320);
                }
            }
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
    let flag_pa = cpu.va2pa(0xCA75_F810);

    println!("[FLAG] {:02x}", cpu.mem.read8(flag_pa));
    println!("[VECRESTORE] {}", vec_restores);
    println!("[TIMERIRQ] {}", timer_irqs);
    if from_main {
        let fp = cpu.va2pa(0xCA2C_C558);
        for &k in &[2u32,9,10,18,19,21,22,24,30,36] {
            let ctx = cpu.mem.read32(fp.wrapping_add(k*8+4));
            let ca = cpu.va2pa(ctx);
            print!("[LISR_END] idx{} ctx={:08x}", k, ctx);
            for j in 0..4u32 { print!(" {:08x}", cpu.mem.read32(ca.wrapping_add(j*4))); }
            println!();
        }
    }
    if from_main {
        print!("[ITCM0]");
        for k in 0..16u32 { print!(" {:08x}", cpu.mem.read32(k*4)); }
        println!();
        print!("[ITCM320]");
        for k in 0..16u32 { print!(" {:08x}", cpu.mem.read32(0x320+k*4)); }
        println!();
    }
    if from_main {
        let b = &cpu.mem.fifo_bytes;
        let ascii: String = b.iter().map(|&(_,c)| if c.is_ascii_graphic() || c == b' ' || c == b'\n' { c as char } else { '.' }).collect();
        let hex: String = b.iter().take(64).map(|&(_,c)| format!("{:02x}",c)).collect();
        println!("[FIFO] n={} ascii='{}' hex={}", b.len(), ascii, hex);
    }
    println!("[PMU] n={}", cpu.mem.pmu_writes.len());
    for &(a,v) in cpu.mem.pmu_writes.iter().take(24) { println!("[PMU] {:08x} = {:08x}", a, v); }
    println!("[SYSOPEN] {}", sys_open_count);
    println!("[RE] count={}", re_count);
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
    if from_main {
        let mut cnt = [0u32; 3];
        let mut hits: Vec<(u32, u32)> = Vec::new();
        let d = &cpu.mem.dram;
        let mut off = 0usize;
        while off + 4 <= d.len() {
            let v = u32::from_le_bytes([d[off], d[off+1], d[off+2], d[off+3]]);
            let idx = match v { 0x5441_534B => Some(0), 0x4849_5352 => Some(1), 0x5449_4D52 => Some(2), _ => None };
            if let Some(k) = idx {
                cnt[k] += 1;
                if hits.len() < 12 { hits.push((0xC000_0000u32.wrapping_add(off as u32), v)); }
            }
            off += 4;
        }
        println!("[DRAMSCAN] words={} TASK={} HISR={} TIMR={}", d.len()/4, cnt[0], cnt[1], cnt[2]);
        for &(a, v) in &hits { println!("[DRAMSCAN] va={:08x} sig={:08x}", a, v); }
        println!("[TCT] TCT_Schedule calls=0 (символ отсутствует в MAIN; idle 0xC002B52A hits={})", idle_hits);
        println!("[GUI] enabled={} open={} fb=240x320", gui, gui_open);
    }
    println!("[HOTPC] {}", top.join(" "));
    println!("[ABORT] data={} prefetch={} far={:08x} fsr={:08x}", cpu.data_aborts, cpu.prefetch_aborts, cpu.fault_far, cpu.fault_fsr);
    for &(n, pc, far, fsr) in cpu.abort_first.iter().take(10) {
        println!("[ABORT] #{} pc={:08x} far={:08x} fsr={:02x}", n, pc, far, fsr);
    }
    println!("[WATCH] hits={}", cpu.mem.watch_hits.len());
    for &(p, a, o, n) in cpu.mem.watch_hits.iter().take(20) {
        println!("[WATCH] pc={:08x} addr={:08x} {:08x} -> {:08x}", p, a, o, n);
    }
    println!("[PTWR] count={}", cpu.mem.pt_writes.len());
    for &(p, a, o, n) in cpu.mem.pt_writes.iter().take(40) {
        println!("[PTWR] pc={:08x} addr={:08x} {:08x} -> {:08x}", p, a, o, n);
    }
    println!("[CODEWR] count={}", cpu.mem.code_writes.len());
    for &(p, a, o, n) in cpu.mem.code_writes.iter().take(20) {
        println!("[CODEWR] pc={:08x} addr={:08x} {:08x} -> {:08x}", p, a, o, n);
    }
    if from_main {
        let p = cpu.va2pa(0x0001_0180);
        print!("[IRQTABLE] va=00010180 pa={:08x}", p);
        for k in 0..16u32 { print!(" {:08x}", cpu.mem.read32(p + k*4)); }
        println!();
    }

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
