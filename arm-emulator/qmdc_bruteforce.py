#!/usr/bin/env python3
"""Перебор простых 16-битных LZSS-конфигураций QMDC.

Проверяет: выход ровно B/2 полуслов и первые полуслова совпадают с ожидаемыми.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from qmdc_tool import chunks

BLOB = Path('/home/bogdan/Downloads/C3520XELJ1/C3520XFLJ1_BIN/C3520XELaFleur.cla/ER_FLASH_CB_MAIN').read_bytes()
CH = list(chunks(BLOB))


def bits(s, msb):
    out = []
    for v in s:
        out.extend((v >> i) & 1 for i in (range(7, -1, -1) if msb else range(8)))
    return out


def decode(x, flag, msb, lit_bit, other, ow, lw, minmatch, halfwords):
    lit = x['literals']
    n_lit = len(lit) // 2
    fb = bits(x[flag], msb)
    ob = bits(x[other], msb)
    out = []
    fi = oi = li = 0
    guard = 0
    while fi < len(fb) and li < n_lit and guard < 20000:
        guard += 1
        if fb[fi] == lit_bit:
            fi += 1
            if halfwords:
                out.append(int.from_bytes(lit[li * 2:li * 2 + 2], 'little'))
                li += 1
            else:
                out.append(lit[li]); li += 1
            continue
        fi += 1
        if oi + ow + lw > len(ob):
            return None
        off = int(''.join(str(v) for v in ob[oi:oi + ow]), 2)
        ln = int(''.join(str(v) for v in ob[oi + ow:oi + ow + lw]), 2) + minmatch
        oi += ow + lw
        if off == 0 or off > len(out):
            return None
        for _ in range(ln):
            if len(out) >= x['B'] // 2:
                break
            out.append(out[-off])
        if len(out) >= x['B'] // 2:
            break
    if len(out) != x['B'] // 2:
        return None
    return out


def main():
    targets = {
        'chunk0': (CH[0], [int.from_bytes(CH[0]['literals'][i:i + 2], 'little') for i in range(0, 8, 2)]),
        'last': (CH[-1], None),
    }
    for name, (x, prefix) in targets.items():
        print('===', name, 'B/2', x['B'] // 2, 'lit/2', len(x['literals']) // 2)
        found = 0
        for flag in ('c_stream', 'd_stream'):
            for msb in (True, False):
                for lit_bit in (0, 1):
                    for other in ('c_stream', 'd_stream'):
                        for ow in (8, 9, 10, 11, 12, 13):
                            for lw in (2, 3, 4, 5, 6, 8):
                                for mm in (1, 2, 3):
                                    for hw in (True, False):
                                        r = decode(x, flag, msb, lit_bit, other, ow, lw, mm, hw)
                                        if r is None:
                                            continue
                                        if prefix and r[:len(prefix)] != prefix:
                                            continue
                                        print(f'  MATCH flag={flag} msb={msb} litbit={lit_bit} '
                                              f'other={other} ow={ow} lw={lw} mm={mm} hw={hw}')
                                        found += 1
                                        if found >= 8:
                                            break
                        if found >= 8: break
                    if found >= 8: break
                if found >= 8: break
            if found >= 8: break
        if not found:
            print('  no simple configuration matched')


if __name__ == '__main__':
    main()
