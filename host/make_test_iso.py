#!/usr/bin/env python3
"""Генератор тестового ISO9660+Joliet, имитирующего структуру диска Windows XP.
Пишет manifest.json с ожидаемым содержимым (для verify_fat32.py).
Использование: make_test_iso.py out.iso manifest.json
"""
import os, sys, json, struct, random, hashlib

rnd = random.Random(1234)
SS = 2048

def rb(n): return bytes(rnd.getrandbits(8) for _ in range(n)) if n < 4096 else rnd.randbytes(n)

class N:
    def __init__(s, name, data=None):
        s.name = name; s.data = data; s.children = [] if data is None else None
        s.ext = 0; s.size = 0 if data is None else len(data)
    @property
    def isdir(s): return s.children is not None
    def add(s, c): s.children.append(c); return c

def iso_name(n, isdir):
    n = n.upper()
    base, _, ext = n.rpartition('.') if '.' in n else (n, '', '')
    def clean(x): return ''.join(c if (c.isalnum() and c.isascii()) or c == '_' else '_' for c in x)
    if isdir: return clean(n)[:8]
    b = clean(base)[:8]; e = clean(ext)[:3]
    return f"{b}.{e};1"

def both(v): return struct.pack('<I', v) + struct.pack('>I', v)
def b16(v): return struct.pack('<H', v) + struct.pack('>H', v)

def rec(name_bytes, ext, size, isdir):
    nl = len(name_bytes)
    ln = 33 + nl + (1 if nl % 2 == 0 else 0)
    r = bytearray(ln)
    r[0] = ln; r[2:10] = both(ext); r[10:18] = both(size)
    r[18:25] = bytes([126, 1, 1, 0, 0, 0, 0]); r[25] = 2 if isdir else 0
    r[28:32] = b16(1); r[32] = nl; r[33:33+nl] = name_bytes
    return bytes(r)

def dir_bytes(d, parent, joliet, ext_of):
    recs = [rec(b'\x00', ext_of(d), dsize(d, joliet), True), rec(b'\x01', ext_of(parent), dsize(parent, joliet), True)]
    for c in sorted(d.children, key=lambda x: x.name.upper()):
        nm = c.name.encode('utf-16-be') if joliet else iso_name(c.name, c.isdir).encode('ascii')
        if joliet and not c.isdir: nm = (c.name + ';1').encode('utf-16-be')
        recs.append(rec(nm, ext_of(c), dsize(c, joliet) if c.isdir else c.size, c.isdir))
    out = b''; cur = b''
    for r in recs:
        if len(cur) + len(r) > SS: out += cur.ljust(SS, b'\0'); cur = b''
        cur += r
    out += cur.ljust(SS, b'\0')
    return out

_sizes = {}
def dsize(d, joliet):
    k = (id(d), joliet)
    if k not in _sizes:
        # размер не зависит от extent'ов
        _sizes[k] = len(dir_bytes_sizeonly(d, joliet))
    return _sizes[k]

def dir_bytes_sizeonly(d, joliet):
    recs = [rec(b'\x00', 0, 0, True), rec(b'\x01', 0, 0, True)]
    for c in d.children:
        nm = (c.name + ';1').encode('utf-16-be') if (joliet and not c.isdir) else (c.name.encode('utf-16-be') if joliet else iso_name(c.name, c.isdir).encode('ascii'))
        recs.append(rec(nm, 0, 0, c.isdir))
    out = b''; cur = b''
    for r in recs:
        if len(cur) + len(r) > SS: out += cur.ljust(SS, b'\0'); cur = b''
        cur += r
    return out + cur.ljust(SS, b'\0')

def walk(d):
    yield d
    for c in d.children:
        if c.isdir: yield from walk(c)

def build_tree():
    root = N('')
    i386 = root.add(N('I386', None))
    txt = (b"[SourceDisksNames]\r\n1 = x\r\n\r\n[SetupData]\r\nOsLoadOptions = \"/fastdetect\"\r\nProductType = 0\r\n\r\n[Other]\r\na=b\r\n")
    ldr = bytearray(rb(0x3000))
    # строки, которые должен патчить Rufus-алгоритм
    def put(off, s): ldr[off:off+len(s)] = s
    put(0x100, b"\\minint\\txtsetup.sif\0")
    put(0x200, b"\\MININT\\system32\\\0")
    put(0x300, b"multi(0)rdisk(0)partition(1)\0")
    put(0x400, b"\\$WIN_NT$.~BT\\txtsetup.sif\0")
    put(0x500, b"$win_nt$.~bt\\\0")
    put(0x600, b"rdisk(0)\0")
    ldr[0x2060] = 0x74; ldr[0x2061] = 0x03
    i386.add(N('TXTSETUP.SIF', txt))
    i386.add(N('SETUPLDR.BIN', bytes(ldr)))
    i386.add(N('NTDETECT.COM', rb(47564)))
    for k in range(400): i386.add(N(f'FILE{k:04d}.DL_', rb(rnd.randint(1, 20000))))
    i386.add(N('Long File Name Test.txt', rb(1234)))
    i386.add(N('readme_lowercase.txt', rb(77)))
    i386.add(N('Тест файл.txt', rb(555)))
    i386.add(N('LFN.COLLIDE.ONE.DAT', rb(10))); i386.add(N('LFN.COLLIDE.TWO.DAT', rb(11)))
    sup = root.add(N('SUPPORT', None)); tools = sup.add(N('TOOLS', None))
    tools.add(N('DEPLOY.CAB', rb(20 * 1024 * 1024)))
    sub = tools.add(N('Nested Dir', None)); sub.add(N('deep.bin', rb(300000)))
    sub.add(N('EMPTY.TXT', b''))
    root.add(N('README.HTM', rb(5000))); root.add(N('AUTORUN.INF', b'[autorun]\r\n'))
    root.add(N('ONEBYTE.DAT', b'x'))
    root.add(N('CLUSTER.DAT', rb(4096 * 8)))     # ровно кластер (для spc=8)
    root.add(N('BIG.BIN', rb(40 * 1024 * 1024 + 123)))
    return root

def main(out, manifest):
    root = build_tree()
    dirs = list(walk(root))
    parent = {}
    for d in dirs:
        for c in d.children:
            if c.isdir: parent[id(c)] = d
    ext = {}   # (id, joliet) -> extent
    sector = 19
    for joliet in (False, True):
        for d in dirs:
            ext[(id(d), joliet)] = sector
            sector += dsize(d, joliet) // SS
    for d in dirs:
        for c in d.children:
            if not c.isdir:
                c.ext = sector; sector += max(1, (c.size + SS - 1) // SS) if c.size else 0
    total = sector
    img = bytearray(total * SS)
    def pvd(t, joliet):
        v = bytearray(SS); v[0] = t; v[1:6] = b'CD001'; v[6] = 1
        lab = ('WXPTEST'.encode('utf-16-be').ljust(32, b'\0')) if joliet else b'WXPTEST'.ljust(32)
        v[8:40] = b' '*32 if not joliet else b'\0\x20'*16
        v[40:72] = lab
        v[80:88] = both(total); v[120:124] = b16(1); v[124:128] = b16(1); v[128:132] = b16(SS)
        v[132:140] = both(0)
        if joliet: v[88:91] = b'%/E'
        v[156:190] = rec(b'\x00', ext[(id(root), joliet)], dsize(root, joliet), True)[:34]
        v[881] = 1
        return bytes(v)
    img[16*SS:17*SS] = pvd(1, False)
    img[17*SS:18*SS] = pvd(2, True)
    t = bytearray(SS); t[0] = 255; t[1:6] = b'CD001'; t[6] = 1
    img[18*SS:19*SS] = t
    for joliet in (False, True):
        for d in dirs:
            p = parent.get(id(d), d)
            b = dir_bytes(d, p, joliet, lambda n: ext[(id(n), joliet)] if n.isdir else n.ext)
            e = ext[(id(d), joliet)] * SS
            img[e:e+len(b)] = b
    files = {}
    def collect(d, path):
        for c in d.children:
            p = path + '/' + c.name if path else c.name
            if c.isdir: collect(c, p)
            else:
                if c.size: img[c.ext*SS:c.ext*SS+c.size] = c.data
                files[p] = {'size': c.size, 'sha1': hashlib.sha1(c.data).hexdigest()}
    collect(root, '')
    open(out, 'wb').write(img)
    dirs_l = []
    def cd(d, path):
        for c in d.children:
            if c.isdir:
                p = path + '/' + c.name if path else c.name; dirs_l.append(p); cd(c, p)
    cd(root, '')
    json.dump({'files': files, 'dirs': dirs_l,
               'setupldr_hex': root.children[0].children[1].data.hex(),
               'txtsetup_hex': root.children[0].children[0].data.hex(),
               'ntdetect_sha1': hashlib.sha1(root.children[0].children[2].data).hexdigest()}, open(manifest, 'w'))
    print('ISO', out, total*SS//1024//1024, 'MB,', len(files), 'files')

main(sys.argv[1], sys.argv[2])
