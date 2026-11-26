# rust-emulator — PNX4910 / Samsung GT-C3520 (Rust port)

Полный порт `arm-emulator/` на Rust: ARM926EJ-S (ARMv5TE) + Thumb, MMU/TCM,
NAND-контроллер по `.ptt`, LCD (240×320 RGB565), QMDC-распаковщик `.cla`,
MMIO-ответы, снапшоты и фазы загрузки.

## Сборка

```sh
cargo build --release
```

## Запуск

```sh
# 200 млн инструкций из верифицированного входа в MAIN
./target/release/arm9 /home/bogdan/Downloads/C3520XELJ1 200000000 quiet --from-main --skip-log --trace-mmio

# холодный старт + фаза LCDACT/IMAGE (RGB565 → /tmp/opencode/bootscreen.png)
./target/release/arm9 /home/bogdan/Downloads/C3520XELJ1 1000 quiet --start-root
```

Флаги: `--start-root`, `--from-main`, `--gui`, `--skip-log`, `--trace-mmio`,
`--trace-all`, `quiet`.

## Реализовано (1-в-1 с Java-портом)

- `arm.rs`: CP15 ID-регистры ARM926EJ-S (MIDR `0x41069265`, CTR `0x1D112152`,
  TCM Status `0x00010001`), direct-mapped TLB (256 записей, 4 КБ) + PC/инструкции-кэш,
  hot-loop fast-path; все 7 режимов, banked SP/LR, FIQ r8-r12, CPSR-маска
  `0xF80000FF`, `thumbBlPending` (исправление сиротской половины BL/BLX),
  CLZ/QADD/QSUB/SMUL/SMLA, MRC/MCR CP15 с раздельными `itcmReg`/`dtcmReg`
  (c9,c1,1 / c9,c1,0), WFI (c7,c0,4 — счётчик), Data/Prefetch Abort
  (vbase+0x10/+0x0C, включаются `--strict-aborts`), системный IRQ (vbase+0x18),
  LDM/STM `^`, все ARM/Thumb load/store, BL/BLX immediate/register.
- `memory.rs`: ITCM `0x0..0x3FFFF`, DTCM `0x30010000..0x30017FFF` с
  приоритетным байпасом MMU, SDRAM `0xC0000000..0xC3FFFFFF`
  read/write, SRAM, разреженные 4 КБ-страницы, MMIO-таблица, лог адресов
  0x7xxxxxxx.
- `nand.rs`: `0x7D80xxxx`, READ ID/STATUS/PAGE, `0x14 = 3→1→3`,
  `0xC0=0x16C9`, `0x408=0xFFFFFFFF`, образы по карте `.ptt`.
- `lcd.rs`: CMD `0x74002000`, DATA `0x74002100`, A1 `0x7401900E`,
  legacy `0x7402xxxx`, 240×320 RGB565, сохранение PNG без внешних crates.
- `qmdc.rs`: QMDC/QMDU (бит-в-бит по родному декодеру), загрузка `.cla`.

## Проверка

- `--start-root`: `commands=3`, `pixels=76800`, PNG `/tmp/opencode/bootscreen.png`;
- `--from-main` 40 000 шагов: `39/40` живых указателей в `0xCA2CCABC`;
- 200 000 000 шагов в `--release`: ~88 MIPS на тестовой машине,
  `halted=false`, `39/40`, `I=1` (PowerOff-ветка ещё не пробита),
  `WFI=0`, `[MMIO]` после 1M пуст — ОС крутится в self-loop `0xC03E0114`.
  С `--skip-log` доходит до диспетчера `0xC0460FF8` на шаге 33074.
