import re, sys, io

LOG = sys.argv[1]
pid = sys.argv[2] if len(sys.argv) > 2 else None
KEY = sys.argv[3] if len(sys.argv) > 3 else None

NOISE = re.compile(r'sensors-hal|bluetooth|LinkPower|netlink|GNSS|gnss|surfaceflinger|'
                   r'libc\b|gralloc|Adreno|DisplayFeature|ScreenOnOff|thermal|power_supply|'
                   r'WifiHAL|wpa_supplicant|AudioFlinger|MediaCodec|chatty|logd|'
                   r'NetworkController|ConnectivityService|Nfc|Telephony|QcRil|RILJ|'
                   r'AndroidAutoSiz|Oplus|colorx|nativeloader|MultiDex|J4A|OpenGLRenderer|'
                   r'GraphicsEnviro|Compatibility|ResourcesManag|OpenGLRenderer')

LINE = re.compile(r'^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d+)\s+(\w)/(\S+)\s*\(\s*(\d+)\):\s?(.*)$')
CJK = re.compile(r'[\u4e00-\u9fff]')
KW = re.compile(KEY) if KEY else None

rows = []
with io.open(LOG, 'r', encoding='utf-8', errors='replace') as f:
    for ln in f:
        m = LINE.match(ln.rstrip('\n'))
        if not m:
            continue
        mo, d, h, mi, s, ms, lvl, tag, p, msg = m.groups()
        if pid and p != pid:
            continue
        if NOISE.search(tag):
            continue
        if KEY:
            if not KW.search(msg) and not KW.search(tag):
                continue
        else:
            if not CJK.search(msg):
                continue
        t = ((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(ms)
        rows.append((t, lvl, tag, p, msg))

if not rows:
    print('没匹配到行')
    sys.exit(0)

t0 = rows[0][0]
print('pid=%s  命中行=%d  跨度=%.1fs' % (pid, len(rows), (rows[-1][0] - t0) / 1000.0))
print('---')
for t, lvl, tag, p, msg in rows:
    print('%8.3f  %s/%-13s %s' % ((t - t0) / 1000.0, lvl, tag[:13], msg[:160]))
