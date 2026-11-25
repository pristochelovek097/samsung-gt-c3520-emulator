#!/usr/bin/env python3
"""Распаковка всех .cla-разделов прошивки C3520 (QMDC + QMDU + сырые слоты)."""
import struct
import sys
from pathlib import Path

import qmdc_decode2 as q

DIR = Path('/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.cla')
OUT = Path('/tmp/opencode/dec')


def unpack(name):
    blob = (DIR / name).read_bytes()
    size = struct.unpack_from('<I', blob, 0)[0]
    phys = struct.unpack_from('<I', blob, 8)[0]
    payload = blob[0x2000:0x2000 + size]
    out = bytearray()
    stats = dict(qmdc=0, qmdu=0, raw=0)
    neg = 0
    for off in range(0, len(payload), 0x1000):
        slot = payload[off:off + 0x1000]
        if len(slot) < 0x1000:
            out += slot
            continue
        if slot[:4] == b'QMDC':
            ch = q.parse(blob, 0x2000 + off)
            try:
                decoded, st, cp, dp, lp = q.decode(ch)
            except EOFError as e:
                print(f'  FAIL at slot {off:#x} ({name}): {e}; using raw', file=sys.stderr)
                decoded = slot
            out += decoded
            stats['qmdc'] += 1
        elif slot[:4] == b'QMDU':
            n = struct.unpack_from('<I', slot, 4)[0]
            out += slot[8:8 + n]
            stats['qmdu'] += 1
        else:
            out += slot
            stats['raw'] += 1
    print(f'{name}: header_size={size:#x} phys={phys:#08x} out={len(out):#x} '
          f'expected={struct.unpack_from("<I", blob, 0x18)[0]:#x} '
          f'stats={stats} match={len(out) == struct.unpack_from("<I", blob, 0x18)[0]}')
    return bytes(out), phys


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ('ER_FLASH_CB_ROOT', 'ER_FLASH_CB_MAIN', 'ER_FLASH_CB_PAGE1',
                 'ER_FLASH_NCB_DSP', 'ER_RAM_CB'):
        data, phys = unpack(name)
        (OUT / (name + '.bin')).write_bytes(data)


if __name__ == '__main__':
    main()
