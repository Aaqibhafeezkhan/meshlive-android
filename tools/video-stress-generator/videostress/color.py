"""Integer BT.709 signal-space RGB -> limited-range centered I420, version 1."""

import numpy as np

COLOR = {"range": "limited", "primaries": "bt709", "matrix": "bt709",
         "transfer": "bt709", "chroma_location": "center"}
CONVERSION = {
    "version": "bt709-integer-box420-v1",
    "source": "8-bit nonlinear BT.709 R'G'B' code values, RGB order, full range",
    "luma": "L=2126*R+7152*G+722*B; Y=16+round(219*L/2550000)",
    "chroma": "Cb=128+round(224*(10000*B-L)/(255*18556)); Cr uses R and 15748",
    "sampling": "Average each native 2x2 chroma numerator before quantization; centered",
    "rounding": "Integer floor((numerator+denominator//2)/denominator), ties upward",
    "geometry": "Unchanged; no scale, crop, padding, rotation or interpolation",
    "pixel_aspect_ratio": "1/1", "bit_depth": 8, "plane_order": ["Y", "U", "V"],
}


def rgb_to_i420(rgb):
    h, w, channels = rgb.shape
    if rgb.dtype != np.uint8 or channels != 3 or w % 2 or h % 2:
        raise ValueError("Conversion requires native even-sized uint8 RGB")
    r, g, b = np.moveaxis(rgb.astype(np.int64), -1, 0)
    luma = 2126 * r + 7152 * g + 722 * b
    y = 16 + (219 * luma + 1275000) // 2550000

    def chroma(numerator, denominator):
        summed = numerator.reshape(h // 2, 2, w // 2, 2).sum(axis=(1, 3))
        divisor = denominator * 4
        return np.clip(128 + (224 * summed + divisor // 2) // divisor, 16, 240).astype(np.uint8)

    u = chroma(10000 * b - luma, 255 * 18556)
    v = chroma(10000 * r - luma, 255 * 15748)
    return y.astype(np.uint8).tobytes() + u.tobytes() + v.tobytes()


def planes(data, width, height):
    size = width * height
    if len(data) != size * 3 // 2:
        raise ValueError("Wrong tightly packed I420 frame size")
    a = np.frombuffer(data, np.uint8)
    return (a[:size].reshape(height, width),
            a[size:size * 5 // 4].reshape(height // 2, width // 2),
            a[size * 5 // 4:].reshape(height // 2, width // 2))
