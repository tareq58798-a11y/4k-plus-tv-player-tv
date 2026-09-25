/**
 * A background colour for a channel's card, taken from its logo.
 *
 * The television puts a frame from the channel's stream on its card (LiveSnapshotCapture.kt),
 * which a Samsung web app cannot do (web/README.md). The owner chose this instead: the logo, small
 * and sharp in the middle of the card, on a colour that belongs to it.
 *
 * The colour is the logo's own - the average of its visible, coloured pixels, read from a copy
 * drawn eight pixels square - darkened so the logo stands out on it. Reading pixels needs the
 * logo's host to allow it (CORS), and many provider hosts do not; then the colour comes from the
 * channel's name instead, so each channel still has its own and keeps it. Results are kept for the
 * session.
 */

const cache = new Map<string, Promise<string>>();

function hsl(hue: number, saturation: number, lightness: number): string {
  return `hsl(${Math.round(hue)}, ${Math.round(saturation)}%, ${Math.round(lightness)}%)`;
}

/** A steady colour for [name]: the same channel is always the same colour. */
function fromName(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return hsl(Math.abs(hash) % 360, 45, 26);
}

function rgbToHsl(r: number, g: number, b: number): [number, number, number] {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const lightness = (max + min) / 2;
  if (max === min) return [0, 0, lightness * 100];
  const d = max - min;
  const saturation = lightness > 0.5 ? d / (2 - max - min) : d / (max + min);
  let hue: number;
  if (max === r) hue = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) hue = (b - r) / d + 2;
  else hue = (r - g) / d + 4;
  return [hue * 60, saturation * 100, lightness * 100];
}

/** The logo's colour, or null when its pixels cannot be read or it has no colour worth using. */
function fromLogo(url: string): Promise<string | null> {
  return new Promise((resolve) => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => {
      try {
        const size = 8;
        const canvas = document.createElement('canvas');
        canvas.width = size;
        canvas.height = size;
        const context = canvas.getContext('2d');
        if (!context) return resolve(null);
        context.drawImage(image, 0, 0, size, size);
        const pixels = context.getImageData(0, 0, size, size).data;
        let r = 0;
        let g = 0;
        let b = 0;
        let weight = 0;
        for (let i = 0; i < pixels.length; i += 4) {
          const alpha = pixels[i + 3]! / 255;
          if (alpha < 0.5) continue;
          const [, s, l] = rgbToHsl(pixels[i]!, pixels[i + 1]!, pixels[i + 2]!);
          // White, black and grey say nothing about the brand; the coloured pixels do.
          const w = l > 92 || l < 8 ? 0.05 : 0.2 + s / 100;
          r += pixels[i]! * w;
          g += pixels[i + 1]! * w;
          b += pixels[i + 2]! * w;
          weight += w;
        }
        if (weight === 0) return resolve(null);
        const [hue, saturation] = rgbToHsl(r / weight, g / weight, b / weight);
        // A logo with no real colour gets a dark slate rather than a muddy tint.
        resolve(saturation < 12 ? hsl(215, 20, 20) : hsl(hue, Math.min(saturation, 60), 24));
      } catch {
        // The host does not allow its pixels to be read.
        resolve(null);
      }
    };
    image.onerror = () => resolve(null);
    image.src = url;
  });
}

export function channelTint(logoUrl: string | null | undefined, name: string): Promise<string> {
  const key = logoUrl || `name:${name}`;
  let found = cache.get(key);
  if (!found) {
    found = logoUrl
      ? fromLogo(logoUrl).then((colour) => colour ?? fromName(name))
      : Promise.resolve(fromName(name));
    cache.set(key, found);
  }
  return found;
}
