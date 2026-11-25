#!/usr/bin/env python3
"""QMDC decoder skeleton — Samsung C3520 (патенты TWI393353B / TWI357224B / CN101383616B).

Проверенные факты:
  * макроблок = 4 байта (два 16-битных short block);
  * difference flag = 2 бита: 00 copy, 01 global index, 10 local index, 11 raw;
  * global index 0..255, local index 256..511 — индекс 8 бит;
  * distance flag = 1 бит: 0 = новое расстояние, 1 = то же, что в прошлом блоке;
  * новое расстояние: 10 бит если diff < 256, иначе 18 бит;
  * local table сохраняется в сжатом кадре (сжата по global table), global table фиксирована;
  * исключение (LMB) — сырые данные, хранятся в literal pool.

Чанк №0: B/4 = 1168 MB, C = 296 байт = 1168*2 бит (292) + 4 байта.
Слот 0xB000: выход начинается по 0xC0020000 + 0xB690; 0xC002C798 - 0xC002B690 = 0x1108.
"""
import struct
import sys
from pathlib import Path

MAIN = Path('/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_MAIN')


class BitReader:
    def __init__(self, data, msb=False):
        self.data, self.i, self.msb = data, 0, msb

    def bit(self):
        if self.i >= len(self.data) * 8:
            raise EOFError('bit stream exhausted')
        v = (self.data[self.i >> 3] >> (7 - (self.i & 7) if self.msb else (self.i & 7))) & 1
        self.i += 1
        return v

    def bits(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | self.bit()
        return v


def parse_chunk(blob, off):
    magic = blob[off:off + 4]
    if magic != b'QMDC':
        return None
    A, B, C, D = struct.unpack_from('<4I', blob, off + 4)
    pay = blob[off + 0x10:off + 0x10 + A]
    assert struct.unpack_from('<I', pay, 0)[0] == D
    return dict(off=off, A=A, B=B, C=C, D=D,
                d=pay[4:4 + D], c=pay[4 + D:4 + D + C],
                lit=pay[4 + D + C:A])


def flags_c(c, n, msb=False):
    """Два бита на макроблок, LSB-first подтверждён на чанке №0:
    первый код = 11 -> output[0:4] = literal[0:4] (валидный Thumb push)."""
    br = BitReader(c, msb)
    return [br.bits(2) for _ in range(n)]


def decode(chunk, glob_table=None, loc_table=None, msb_c=False, msb_d=True):
    """Возвращает partial output: None на место слова, требующего таблицы."""
    B, C, D = chunk['B'], chunk['c'], chunk['d']
    fl = flags_c(C, B // 4, msb_c)
    br = BitReader(D[4:], msb_d)
    out = bytearray()
    li = 0
    prev_dist = 4
    stats = [0, 0, 0, 0]
    try:
        for code in fl:
            stats[code] += 1
            if code == 0:                  # 00: точная копия домена
                f = br.bit()               # 0 = новое расстояние (патент FIG.13)
                if f == 0:
                    big = br.bit()
                    prev_dist = br.bits(16 if big else 8)
                src = len(out) - prev_dist
                out += out[src:src + 4] if 0 <= src < len(out) else b'\0' * 4
            elif code in (1, 2):           # 01/10: индекс таблицы (8 бит) в D
                idx = br.bits(8)
                f = br.bit()
                if f == 0:
                    big = br.bit()
                    prev_dist = br.bits(16 if big else 8)
                src = len(out) - prev_dist
                diff = (glob_table or [0] * 256)[idx] if code == 1 else (loc_table or [0] * 256)[idx]
                base = out[src:src + 4] if 0 <= src < len(out) else b'\0' * 4
                out += bytes(a ^ b for a, b in zip(base, struct.pack('<I', diff) * 2))
            else:                          # 11: сырой 4-байтный литерал
                out += chunk['lit'][li:li + 4]
                li += 4
    except EOFError:
        out += b'\0' * (B - len(out))
    return bytes(out), stats, br.i, li


def main():
    blob = MAIN.read_bytes()
    start = 0
    # Суммарный выход всех чанков до слота 0xB000
    for off in range(0x2000, 0xB000, 0x1000):
        ch = parse_chunk(blob, off)
        if ch:
            start += ch['B']
    print(f'slot 0xB000 output base = 0xC0020000 + {start:#x} = {0xC0020000 + start:#08x}')
    print(f'0xC002C798 - base = {0xC002C798 - (0xC0020000 + start):#x}')
    ch = parse_chunk(blob, 0x2000)
    out, stats, used, li = decode(ch)
    print(f'chunk0: flags {stats}, D bits {used}/{len(ch["d"]) * 8}, literals {li}/{len(ch["lit"])}')
    print('out[:48]  ', out[:48].hex(' '))
    print('lit[:48]  ', ch['lit'][:48].hex(' '))
    print('same?     ', out[:4] == ch['lit'][:4])


if __name__ == '__main__':
    main()
