#!/usr/bin/env python3
"""Точный QMDC-декодер, реконструированный из .bootloader 0x2FFE4190..0x2FFE4788.

Карта чанка ( caller передаёт в функцию указатель chunk+8 ):
  chunk+0  : 'QMDC'
  chunk+4  : A (размер полезной нагрузки)
  chunk+8  : B (размер выхода)
  chunk+12 : C (размер потока битов)
  chunk+16 : D (размер потока флагов/индексов)
  chunk+20 : C-stream | D-stream | literals

Глобальная таблица: 256 x uint16 @ 0x2FFE9AB0 (в .bootloader +0x9AB0).

Алгоритм (0x2FFE42A0..0x2FFE4744):
 每组 = 16 байт выхода (4 макроблока = 8 short block):
   bitC == 0  -> 16 байт сырья из literals (LMB exception)
   bitC == 1  -> flags = байт из D; distance:
        bitC==1 -> расстояние не меняется
        bitC==0, бит==1 -> r6 = байт из D
        bitC==0, бит==0 -> r6 = uint16 из literals
       затем 8 short block (биты flags 7..0):
        бит==1 -> копия domain = out[pos - 2*r6]
        бит==0 -> бит из C: 1 -> uint16 из literals;
                            0 -> idx из D, word = global[idx] XOR domain
"""
import struct
import sys
from pathlib import Path

CLA = Path('/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_MAIN')
BOOT = Path('/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.bootloader')

GLOBAL = struct.unpack_from('<256H', BOOT.read_bytes(), 0x9AB0)


class CBit:
    """0x2FFE42B8: r7 = (r7 << 8) | byte; биты берутся от старшего к младшему."""
    def __init__(self, data):
        self.d = data
        self.nbits = 0
        self.buf = 0
        self.pos = 0

    def bit(self):
        if self.nbits < 2:
            # Хвостовые биты после конца C-потока читаются как нули (как в железе:
            # буфер сдвигается и подтягивает нулевые байты).
            b = self.d[self.pos] if self.pos < len(self.d) else 0
            self.buf = (self.buf << 8) | b
            self.pos += 1
            self.nbits += 8
        self.nbits -= 1
        return (self.buf >> self.nbits) & 1


class DStream:
    def __init__(self, data):
        self.d = data
        self.pos = 0

    def byte(self):
        if self.pos >= len(self.d):
            raise EOFError('D stream')
        v = self.d[self.pos]
        self.pos += 1
        return v


class Literals:
    def __init__(self, data):
        self.d = data
        self.pos = 0

    def u16(self):
        if self.pos + 2 > len(self.d):
            raise EOFError('literals')
        v = struct.unpack_from('<H', self.d, self.pos)[0]
        self.pos += 2
        return v

    def raw16(self, n):
        if self.pos + n > len(self.d):
            raise EOFError('literals')
        v = self.d[self.pos:self.pos + n]
        self.pos += n
        return v


def parse(blob, off):
    assert blob[off:off + 4] == b'QMDC', hex(off)
    A, B, C, D = struct.unpack_from('<4I', blob, off + 4)
    pay = blob[off + 0x14:off + 0x14 + A]
    return dict(off=off, A=A, B=B,
                c=pay[:C], d=pay[C:C + D], lit=pay[C + D:])


def decode(ch, verbose=False):
    B = ch['B']
    cb = CBit(ch['c'])
    ds = DStream(ch['d'])
    lit = Literals(ch['lit'])
    out = bytearray()
    dist = 1                      # r6, в 16-битных словах
    stats = dict(raw=0, coded=0, copy=0, raww=0, tab=0)
    out += lit.raw16(16)          # 0x2FFE4248: стартовые 16 байт из literals
    def dom(src):
        return struct.unpack_from('<H', out, src)[0] if 0 <= src < len(out) - 1 else 0
    while len(out) < B:
        if cb.bit() == 0:         # LMB: 16 байт (0x2FFE4678)
            if cb.bit() == 1:     # повтор domain-блока на расстоянии r6
                for _ in range(8):
                    out += struct.pack('<H', dom(len(out) - 2 * dist))
                stats['raw'] += 1
            else:                 # сырьё из literals
                out += lit.raw16(16)
                stats['raw'] += 1
            continue
        stats['coded'] += 1
        flags = ds.byte()

        def update_dist():
            nonlocal dist
            # 0x2FFE4304 / 0x2FFE43DC / 0x2FFE44B0 / ... — обновление между short block
            if cb.bit() == 1:     # расстояние то же
                return
            if cb.bit() == 1:     # байт из D
                dist = ds.byte()
            else:                 # uint16 из literals
                dist = lit.u16()

        for bit in range(7, -1, -1):
            # обновление расстояния только перед short block 0,2,4,6
            if bit & 1:
                update_dist()
            src = len(out) - 2 * dist
            base = dom(src)
            if (flags >> bit) & 1:
                v = base
                stats['copy'] += 1
            elif cb.bit() == 1:
                v = lit.u16()
                stats['raww'] += 1
            else:
                v = GLOBAL[ds.byte()] ^ base
                stats['tab'] += 1
            out += struct.pack('<H', v)
    return bytes(out[:B]), stats, cb.pos, ds.pos, lit.pos


def main():
    blob = CLA.read_bytes()
    for off in (0x2000, 0x3000, 0xb000, 0xA18000, 0x858000, 0xad8000):
        ch = parse(blob, off)
        try:
            out, st, cp, dp, lp = decode(ch)
            print(f'{off:#08x} B={ch["B"]:#x} C={len(ch["c"]):#x} D={len(ch["d"]):#x} '
                  f'L={len(ch["lit"]):#x} stats={st} usedC={cp}/{len(ch["c"])} '
                  f'usedD={dp}/{len(ch["d"])} usedLit={lp}/{len(ch["lit"])}')
            print('  out[:48] ', out[:48].hex(' '))
            print('  lit[:48] ', ch['lit'][:48].hex(' '))
        except EOFError as e:
            print(f'{off:#08x}: ERROR {e}')


if __name__ == '__main__':
    main()
