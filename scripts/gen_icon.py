import os
from PIL import Image, ImageDraw, ImageFont

RES = r"<工作区>/FreeBox-src/app/src/main/res"

START = (0x15, 0x80, 0x3d)   # #15803d 深绿
END   = (0x4a, 0xde, 0x80)   # #4ade80 亮绿
WHITE = (255, 255, 255, 255)

def load_font(size):
    for p in [r"C:/Windows/Fonts/arialbd.ttf",
              r"C:/Windows/Fonts/arial.ttf",
              r"C:/Windows/Fonts/msyh.ttc",
              r"C:/Windows/Fonts/msyhbd.ttc"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

def vertical_gradient(size, start, end):
    col = Image.new("RGB", (1, size))
    px = col.load()
    for y in range(size):
        t = y / (size - 1)
        r = int(start[0] + (end[0] - start[0]) * t)
        g = int(start[1] + (end[1] - start[1]) * t)
        b = int(start[2] + (end[2] - start[2]) * t)
        px[0, y] = (r, g, b)
    return col.resize((size, size))

def make_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    grad = vertical_gradient(size, START, END).convert("RGBA")
    # 圆角矩形遮罩
    m = max(2, int(size * 0.06))
    r = int(size * 0.22)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([m, m, size - m, size - m], radius=r, fill=255)
    img = Image.composite(grad, img, mask)
    d = ImageDraw.Draw(img)
    # "L1" 文字
    fs = int(size * 0.60)
    font = load_font(fs)
    txt = "L1"
    bbox = d.textbbox((0, 0), txt, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - tw) / 2 - bbox[0]
    y = (size - th) / 2 - bbox[1]
    d.text((x, y), txt, font=font, fill=WHITE)
    return img

def make_splash(size=1024):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    fs = int(size * 0.16)
    font = load_font(fs)
    txt = "L1Box"
    bbox = d.textbbox((0, 0), txt, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - tw) / 2 - bbox[0]
    y = (size - th) / 2 - bbox[1]
    d.text((x, y), txt, font=font, fill=WHITE)
    return img

# 1) 图标: 各密度
densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dname, sz in densities.items():
    folder = os.path.join(RES, "drawable-" + dname)
    os.makedirs(folder, exist_ok=True)
    out = os.path.join(folder, "app_icon.png")
    make_icon(sz).save(out, "PNG")
    print("icon", dname, sz, "->", out)

# 2) 启动图文字 (透明底, 白色 L1Box), 覆盖原 iv_splash.webp
sf = os.path.join(RES, "drawable-xxhdpi")
os.makedirs(sf, exist_ok=True)
sout = os.path.join(sf, "iv_splash.webp")
make_splash(1024).save(sout, "WEBP", lossless=True)
print("splash text ->", sout)
print("DONE")
