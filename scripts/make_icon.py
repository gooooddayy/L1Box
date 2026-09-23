"""
#9: Resize the new L1Box icon to all launcher densities and overwrite app_icon.png.
"""
from PIL import Image
import os

SRC = r"<本机>\.workbuddy\clipboard-images\clipboard-2026-09-08T06-33-04-873Z-f8d4b6d3.jpg"
RES = r"<工作区>\FreeBox-src\app\src\main\res"

# density -> pixel size (Android launcher icon standard)
DENSITIES = [
    ("drawable-mdpi",    48),
    ("drawable-hdpi",    72),
    ("drawable-xhdpi",   96),
    ("drawable-xxhdpi",  144),
    ("drawable-xxxhdpi", 192),
]

img = Image.open(SRC).convert("RGBA")  # keep alpha for PNG
# Crop to square center just in case the source isn't perfectly square
w, h = img.size
s = min(w, h)
left = (w - s) // 2
top  = (h - s) // 2
img = img.crop((left, top, left + s, top + s))

for folder, size in DENSITIES:
    out = os.path.join(RES, folder, "app_icon.png")
    resized = img.resize((size, size), Image.LANCZOS)
    # Preserve any rounded corners / alpha the image has (it already has rounded shape)
    resized.save(out, "PNG", optimize=True)
    print(f"wrote {out} ({size}x{size})")

print("done.")