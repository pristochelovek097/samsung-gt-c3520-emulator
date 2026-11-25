#!/usr/bin/env python3
"""QMDC-чанки прошивки Samsung C3520: парсер и каркас декодера.

Формат (установлено по .cla/ER_FLASH_CB_MAIN):
  чанк 0x1000 байт, начинается с 'QMDC' (u32 LE 0x43444D51)
  +0x04 A — длина блока данных (обычно ~0xFE8)
  +0x08 B — контрольное слово/CRC (0x1240..0x1DE0)
  +0x0C C — размер секции таблиц  (0x128..0x25C)
  +0x10 D — размер секции/первое слово данных (0x330..0x714)

D дублируется первым u32 сразу после 16-байтового заголовка.
По смещенинию chunk+16+4+D+C в чанке 0 лежит валидный Thumb-код,
то есть поток содержит участки почти-сырых литералов.
"""
import struct
import sys
from pathlib import Path

MAGIC = b'QMDC'


def chunks(blob: bytes, start=0x2000):
    off = start
    while True:
        i = blob.find(MAGIC, off)
        if i < 0:
            return
        if i + 16 > len(blob):
            return
        a, b, c, d = struct.unpack_from('<IIII', blob, i + 4)
        first = struct.unpack_from('<I', blob, i + 16)[0] if i + 20 <= len(blob) else None
        # Потоки: D-поток включает 4-байтовое слово длины, затем C, затем raw.
        # D-поток: 4-байтовое слово длины + байт полезной нагрузки; затем C; затем raw.
        d_stream = blob[i + 16:i + 20 + d]
        c_stream = blob[i + 20 + d:i + 20 + d + c]
        literals = blob[i + 20 + d + c:i + 16 + a]
        yield {'off': i, 'A': a, 'B': b, 'C': c, 'D': d,
               'first': first, 'data': blob[i + 16:i + 16 + a],
               'd_stream': d_stream, 'c_stream': c_stream, 'literals': literals}
        off = i + 4


def dump(blob: bytes, count=5):
    ch = list(chunks(blob))
    print(f'chunks: {len(ch)}')
    for n, x in enumerate(ch[:count]):
        print(f'[{n}] off={x["off"]:#x} A={x["A"]:#x} B={x["B"]:#x} '
              f'C={x["C"]:#x} D={x["D"]:#x} first={x["first"]:#x} '
              f'D==first: {x["D"] == x["first"]}')
        print('    ' + x['data'][:48].hex(' '))
        lit = 16 + 4 + x['D'] + x['C']
        print(f'    literal-region starts +{lit:#x}: {x["data"][lit:lit+24].hex(" ")}')


def raw_reconstruct(blob: bytes, out: Path, limit=None):
    """Пробная «реконструкция»: кладём данные чанков без заголовков в выход.
    Не является настоящей распаковкой QMDC — нужна для сравнения/поиска
    участков валидного кода и границ таблиц."""
    buf = bytearray()
    for n, x in enumerate(chunks(blob)):
        if limit is not None and n >= limit:
            break
        buf += x['data']
    out.write_bytes(buf)
    print(f'wrote {len(buf):#x} bytes -> {out}')


if __name__ == '__main__':
    p = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        '/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_MAIN')
    blob = p.read_bytes()
    dump(blob)
    if len(sys.argv) > 2:
        raw_reconstruct(blob, Path(sys.argv[2]))
