# Samsung GT-C3520 / PNX4910 emulator

Эмулятор ARM926EJ-S (ARMv5TE) и QMDC-декодер прошивки Samsung C3520.

## Состав
- `arm-emulator/src/arm/` — интерпретатор ARM/Thumb, CP15/MMU, IRQ/SVC, память.
- `own-emulator/` — собственный J2ME/UI (OwnJ2ME), `PhoneShell`.
- `launcher.py`, `web/` — дашборд на http://127.0.0.1:8848.
- `freej2me/` — альтернативный J2ME-движок.

## Прорыв
- QMDC: найден родной декодер в `.bootloader` по `0x2FFE4190`, Global Table
  `0x2FFE9AB0` (256×uint16); формат чанка `QMDC + A/B/C/D` разобран полностью.
  `qmdc_decode2.py` даёт бит-в-бит совпадение с родным кодом.
- Распаковка MAIN/DSP/RAM: суммарный MAIN = `0x119FF5C` байт (ровно как в
  заголовке `.cla`). `qmdc_unpack.py` пишет образы в `/tmp/opencode/dec/`.
- Холодный старт `--start-root`: ROOT `0xC0000000` -> MMU/TTBR `0xC3BC4000` ->
  вход в MAIN по `blx 0xC0058704`. Пошаговое сравнение с Unicorn 2.1.4:
  1 858 821 инструкция совпала бит-в-бит; region setup честно возвращает `r0=0`.

## Сборка / запуск
```
cd arm-emulator && ./build.sh
java -jar build/arm9.jar /home/bogdan/Downloads/C3520XELJ1 50000000 quiet --start-root --trace-mmio
python3 qmdc_unpack.py
python3 qmdc_decode2.py
cd .. && ./run.sh     # дашборд :8848
```

Подробный журнал разбора — `arm-emulator/QMDC-ANALYSIS.md`.
