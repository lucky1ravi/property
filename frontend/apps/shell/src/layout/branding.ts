/**
 * A company's own colours. The product's main colour scale ("federal": sidebar, buttons, links, highlights) is a set of CSS
 * variables; this turns the company's primary colour from Configuration > Branding into that scale and sets the variables, so
 * the whole product takes on the company's colour. The accent colour is exposed as a variable for invoices and highlights.
 */
const STEPS = ['950', '900', '800', '700', '600', '300', '200', '100', '50'] as const;

/** Lightness (percent) of each step of the default scale, from darkest to lightest. */
const LIGHTNESS: Record<(typeof STEPS)[number], number> = { '950': 9, '900': 16, '800': 21, '700': 26, '600': 33, '300': 67, '200': 82, '100': 89, '50': 95 };

export const HEX = /^#[0-9a-fA-F]{6}$/;
const DEFAULT_PRIMARY = '#0F3D3E';

function hexToHsl(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255, g = parseInt(hex.slice(3, 5), 16) / 255, b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
  let h = 0, s = 0;
  if (d > 0) {
    s = d / (1 - Math.abs(2 * l - 1));
    h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, s * 100, l * 100];
}

function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  s /= 100; l /= 100;
  const c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}

/** The nine steps of the scale for a primary colour, as "r g b" triplets. The primary colour itself is the 900 step. */
export function scaleFor(primary: string): Record<string, string> {
  const [h, s, l] = hexToHsl(primary);
  const base = Math.min(Math.max(l, 10), 30); // too light a primary would leave white text unreadable on it
  const out: Record<string, string> = {};
  for (const step of STEPS) {
    const target = step === '900' ? base : step === '950' ? Math.max(5, base - 7) : step === '800' ? base + 5 : step === '700' ? base + 10 : step === '600' ? base + 17 : LIGHTNESS[step];
    const sat = ['300', '200', '100', '50'].includes(step) ? Math.min(s, 45) : s; // light tints look cleaner a little less saturated
    out[step] = hslToRgb(h, sat, target).join(' ');
  }
  return out;
}

/** Sets (or clears) the company's colours on the page. Safe to call again whenever the branding changes. */
export function applyBranding(branding?: { primaryColor?: string; accentColor?: string } | null) {
  const root = document.documentElement;
  const primary = branding?.primaryColor;
  const accent = branding?.accentColor;
  // The PropertyOS default colour is already the default scale, exactly; only a company's own colour needs a scale made for it.
  if (primary && HEX.test(primary) && primary.toUpperCase() !== DEFAULT_PRIMARY) {
    const scale = scaleFor(primary);
    for (const step of STEPS) root.style.setProperty(`--federal-${step}`, scale[step]);
  } else {
    for (const step of STEPS) root.style.removeProperty(`--federal-${step}`);
  }
  if (accent && HEX.test(accent)) root.style.setProperty('--brand-accent', `${parseInt(accent.slice(1, 3), 16)} ${parseInt(accent.slice(3, 5), 16)} ${parseInt(accent.slice(5, 7), 16)}`);
  else root.style.removeProperty('--brand-accent');
}
