#!/usr/bin/env python3
"""Независимая проверка образа флешки: MBR, FAT32, цепочки FAT, каталоги/LFN, содержимое файлов.
Использование: verify_fat32.py disk.img manifest.json boot_dir
"""
import sys, json, struct, hashlib, os

def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]

img = open(sys.argv[1], 'rb'); man = json.load(open(sys.argv[2])); bootdir = sys.argv[3]
errors = []
def err(m):
    errors.append(m); print('ОШИБКА:', m)

def rd(off, n):
    img.seek(off); return img.read(n)

# ---------- MBR
mbr = rd(0, 512)
mbr_code = open(os.path.join(bootdir, 'mbr_rufus.bin'), 'rb').read()
if mbr[:440] != mbr_code: err('MBR: код не совпадает с mbr_rufus.bin')
if mbr[510:512] != b'\x55\xaa': err('MBR: нет сигнатуры 55AA')
pe = mbr[446:462]
flag, ptype, pstart, psize = pe[0], pe[4], u32(pe, 8), u32(pe, 12)
print(f'MBR: flag=0x{flag:02x} type=0x{ptype:02x} start={pstart} size={psize}')
if flag != 0x81: err('флаг активного раздела должен быть 0x81')
if ptype != 0x0C: err('тип раздела должен быть 0x0C')
for i in range(1, 4):
    if mbr[446+16*i:462+16*i] != b'\0'*16: err(f'раздел {i+1} не пуст')
if pstart % 2048: err('раздел не выровнен на 1 МиБ')
disk_size = os.path.getsize(sys.argv[1])
if (pstart + psize) * 512 > disk_size: err('раздел выходит за пределы диска')
if any(rd(512, 512*2047)): err('зазор между MBR и разделом не обнулён')

P = pstart * 512
# ---------- VBR
vbr = rd(P, 512*3)
if vbr[0:3] != b'\xeb\x58\x90': err('VBR: jmp')
bps = u16(vbr, 11); spc = vbr[13]; rsv = u16(vbr, 14); nfats = vbr[16]
fatsz = u32(vbr, 36); root_clus = u32(vbr, 44); fsinfo = u16(vbr, 48); bkboot = u16(vbr, 50)
hidden = u32(vbr, 28); tot32 = u32(vbr, 32)
print(f'BPB: bps={bps} spc={spc} rsv={rsv} nfats={nfats} fatsz={fatsz} root={root_clus} hidden={hidden} tot={tot32}')
if bps != 512 or nfats != 2 or root_clus != 2 or fsinfo != 1 or bkboot != 6: err('BPB: неожиданные поля')
if hidden != pstart or tot32 != psize: err('BPB: hidden/total не совпадают с MBR')
if vbr[0x40] != 0x80 or vbr[0x42] != 0x29: err('BPB: drive number / ext sig')
if vbr[0x52:0x5a] != b'FAT32   ': err('BPB: fs type')
if vbr[510:512] != b'\x55\xaa' or vbr[1022:1024] != b'\x55\xaa' or vbr[1534:1536] != b'\x55\xaa': err('сигнатуры 55AA в секторах 0..2')
# загрузочный код против ресурсов
b52 = open(os.path.join(bootdir, 'fat32nt_0x52.bin'), 'rb').read()
b3f0 = open(os.path.join(bootdir, 'fat32nt_0x3f0.bin'), 'rb').read()
b1800 = open(os.path.join(bootdir, 'fat32nt_0x1800.bin'), 'rb').read()
if vbr[0x52:0x52+len(b52)] != b52: err('VBR: код 0x52 не совпал')
two = rd(P, 0x3f0+len(b3f0))
if two[0x3f0:] != b3f0: err('VBR: блок 0x3f0 не совпал')
if rd(P+0x1800, 512) != b1800: err('второй загрузочный сектор (сектор 12) не совпал')
if rd(P + 6*512, 3*512) != vbr[:1536]: err('резервная копия загрузочных секторов (6..8) отличается')
if b'NTLDR      ' not in rd(P+0x52, 920): err('в загрузочном коде нет имени NTLDR')
# FSInfo
fi = vbr[512:1024]
if fi[0:4] != b'RRaA' or fi[484:488] != b'rrAa' or fi[508:512] != b'\x00\x00\x55\xaa': err('FSInfo: сигнатуры')
free_cnt, next_free = u32(fi, 488), u32(fi, 492)

# ---------- FAT
fat_off = P + rsv*512
fat1 = rd(fat_off, fatsz*512); fat2 = rd(fat_off + fatsz*512, fatsz*512)
if fat1 != fat2: err('копии FAT различаются')
data_start = P + (rsv + nfats*fatsz) * 512
if (data_start // 512) % 2048: print('предупреждение: данные не выровнены на 1 МиБ')
nclus = (psize - rsv - nfats*fatsz) // spc
if nclus < 65525: err('кластеров меньше 65525 — это не FAT32')
if (nclus + 2) * 4 > fatsz * 512: err('FAT слишком мала для числа кластеров')
fat = struct.unpack('<%dI' % (len(fat1)//4), fat1)
if fat[0] != 0x0FFFFFF8 or fat[1] != 0x0FFFFFFF: err('FAT[0]/FAT[1]')
cb = spc * 512
def chain(c):
    out = []; seen = set()
    while True:
        if c < 2 or c >= nclus + 2: err(f'цепочка: плохой кластер {c}'); return out
        if c in seen: err('цикл в цепочке'); return out
        seen.add(c); out.append(c)
        nx = fat[c] & 0x0FFFFFFF
        if nx >= 0x0FFFFFF8: return out
        if nx == 0: err(f'цепочка обрывается в свободном кластере после {c}'); return out
        c = nx
def read_chain(ch):
    return b''.join(rd(data_start + (c-2)*cb, cb) for c in ch)

used = set()
files = {}; dirs = set()
def lfn_checksum(n11):
    s = 0
    for b in n11: s = (((s & 1) << 7) + (s >> 1) + b) & 0xFF
    return s
def walk(clus, path, parent_clus):
    ch = chain(clus)
    for c in ch:
        if c in used: err(f'кластер {c} принадлежит двум объектам')
        used.add(c)
    data = read_chain(ch)
    lfn = []; seen_short = set(); entries = 0
    for i in range(0, len(data), 32):
        e = data[i:i+32]
        if e[0] == 0: break
        if e[0] == 0xE5: lfn = []; continue
        attr = e[11]
        if attr == 0x0F:
            lfn.append(e); continue
        if attr & 0x08:   # метка тома
            if path == '': print('метка тома:', e[:11])
            lfn = []; continue
        n11 = bytes(e[:11]); entries += 1
        if n11 in seen_short: err(f'дубликат короткого имени {n11} в {path}')
        seen_short.add(n11)
        first = (u16(e, 20) << 16) | u16(e, 26); size = u32(e, 28)
        if n11[:2] == b'. ' or n11[:3] == b'.  ' or n11[0:2] == b'..':
            if n11.startswith(b'..'):
                exp = 0 if parent_clus == 2 else parent_clus
                if first != exp: err(f'".." в {path}: {first} != {exp}')
            else:
                if first != clus: err(f'"." в {path}')
            lfn = []; continue
        if lfn:
            # проверка LFN
            lfn = lfn[::-1]
            if not (lfn[0][0] & 0x40 or True): pass
            ck = lfn_checksum(n11)
            chars = ''
            seqs = []
            for le in sorted(lfn, key=lambda x: x[0] & 0x1F):
                if le[13] != ck: err(f'LFN checksum в {path}')
                seqs.append(le[0] & 0x1F)
                part = le[1:11] + le[14:26] + le[28:32]
                chars += part.decode('utf-16-le')
            if seqs != list(range(1, len(seqs)+1)): err(f'LFN порядок в {path}: {seqs}')
            if not (max(lfn, key=lambda x: x[0]&0x1F)[0] & 0x40): err('LFN: нет флага последней записи')
            name = chars.split('\0')[0]
            tail = chars.split('\0', 1)[1] if '\0' in chars else ''
            if tail.strip('￿'): err(f'LFN: мусор после терминатора в {path}')
        else:
            base = n11[:8].decode('cp437').rstrip(); ext = n11[8:].decode('cp437').rstrip()
            name = base + ('.' + ext if ext else '')
        lfn = []
        full = path + '/' + name if path else name
        if attr & 0x10:
            dirs.add(full); walk(first, full, clus)
        else:
            ch2 = chain(first) if size else []
            if size and len(ch2) != (size + cb - 1)//cb: err(f'{full}: длина цепочки {len(ch2)} != {(size+cb-1)//cb}')
            if not size and first != 0: err(f'{full}: пустой файл с кластером {first}')
            for c in ch2:
                if c in used: err(f'кластер {c} принадлежит двум объектам ({full})')
                used.add(c)
            h = hashlib.sha1()
            left = size
            for c in ch2:
                blk = rd(data_start + (c-2)*cb, cb)
                h.update(blk[:min(cb, left)]); left -= cb
            files[full] = {'size': size, 'sha1': h.hexdigest()}
walk(2, '', 2)

# ---------- ожидаемое содержимое
# независимая реализация правок из Rufus (wue.c)
def patch_ldr(orig):
    b = bytearray(orig)
    if b[0x2060] == 0x74 and b[0x2061] == 0x03: b[0x2060] = 0xEB; b[0x2061] = 0x1A
    def m(i, s, n): return b[i:i+n].lower() == s[:n].lower().encode()
    org = ['\\minint\\txtsetup.sif', '\\minint\\system32\\']; rep = ['\\i386\\txtsetup.sif', '\\i386\\system32\\']
    i = 1
    while i < len(b) - 32:
        for j in range(2):
            if m(i, org[j], len(org[j]) - 1):
                b[i:i+len(rep[j])+1] = rep[j].encode() + b'\0'
                i += max(len(org[j]), len(rep[j]))
        i += 1
    for i in range(0, len(b) - 32):
        if m(i, 'rdisk(0)', 7): b[i+6] = 0x31
        if m(i, '$win_nt$.~bt', 11):
            nxt = b[i+12]
            b[i:i+5] = b'i386\0'; b[i+4] = nxt; b[i+5] = 0
    return bytes(b)
def patch_txt(t):
    out = b''
    for line in t.splitlines(keepends=True):
        out += line
        if line.strip().lower().startswith(b'[setupdata]'):
            out += b'SetupSourceDevice = "\\device\\harddisk1\\partition1"\r\n'
    return out
exp = dict(man['files'])
ldr = patch_ldr(bytes.fromhex(man['setupldr_hex'])); txt = patch_txt(bytes.fromhex(man['txtsetup_hex']))
exp['NTLDR'] = {'size': len(ldr), 'sha1': hashlib.sha1(ldr).hexdigest()}
exp['TXTSETUP.SIF'] = {'size': len(txt), 'sha1': hashlib.sha1(txt).hexdigest()}
exp['NTDETECT.COM'] = {'size': len(bytes.fromhex('00')) and 0, 'sha1': man['ntdetect_sha1']}
exp['NTDETECT.COM']['size'] = man['files']['I386/NTDETECT.COM']['size']
exp = {k.upper() if False else k: v for k, v in exp.items()}
lower_found = {k.lower(): (k, v) for k, v in files.items()}
for k, v in exp.items():
    got = lower_found.get(k.lower())
    if not got: err(f'отсутствует файл {k}'); continue
    if got[1] != v: err(f'{k}: содержимое не совпало (size {got[1]["size"]} vs {v["size"]})')
extra = set(lower_found) - {k.lower() for k in exp}
if extra: err(f'лишние файлы: {sorted(extra)[:5]}')
for d in man['dirs']:
    if d.lower() not in {x.lower() for x in dirs}: err(f'нет каталога {d}')
# имена в точности (регистр) для LFN-файлов
for k in man['files']:
    if k not in files and k.upper() != k: err(f'регистр/LFN имя не сохранено: {k}')
# сверка счётчиков FSInfo
nused = len(used)
if free_cnt != nclus - nused: err(f'FSInfo free {free_cnt} != {nclus - nused}')
if next_free != max(used) + 1: err(f'FSInfo next_free {next_free} != {max(used)+1}')
# все занятые в FAT = найденные
fat_used = {i for i in range(2, nclus + 2) if fat[i] != 0}
if fat_used != used: err(f'FAT содержит {len(fat_used)} занятых, найдено в каталогах {len(used)}')
print(f'файлов: {len(files)}, каталогов: {len(dirs)}, занято кластеров: {nused}/{nclus}')
print('РЕЗУЛЬТАТ:', 'OK' if not errors else f'{len(errors)} ошибок')
sys.exit(1 if errors else 0)
