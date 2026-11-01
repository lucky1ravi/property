import { afterEach, describe, expect, it, vi } from 'vitest';
import { applyBranding, scaleFor } from '../../../../../apps/shell/src/layout/branding';

const channels = (rgb: string) => rgb.split(' ').map(Number);
const lightness = (rgb: string) => { const [r, g, b] = channels(rgb).map((v) => v / 255); return (Math.max(r, g, b) + Math.min(r, g, b)) / 2; };

describe('the company colour scale', () => {
  it('makes nine steps from the darkest to the lightest', () => {
    const scale = scaleFor('#7A1F3D');
    expect(Object.keys(scale).sort()).toEqual(['950', '900', '800', '700', '600', '300', '200', '100', '50'].sort());
    const order = ['950', '900', '800', '700', '600', '300', '200', '100', '50'].map((k) => lightness(scale[k]));
    for (let i = 1; i < order.length; i++) expect(order[i]).toBeGreaterThan(order[i - 1]);
  });

  it('keeps the hue of the colour, so a red company stays red', () => {
    for (const step of ['900', '800', '700']) {
      const [r, g, b] = channels(scaleFor('#7A1F3D')[step]);
      expect(r).toBeGreaterThan(g);
      expect(r).toBeGreaterThan(b);
    }
  });

  it('never lets the main colour get so light that white text on it cannot be read', () => {
    expect(lightness(scaleFor('#F5E663')['900'])).toBeLessThanOrEqual(0.31);
    expect(lightness(scaleFor('#000000')['900'])).toBeGreaterThanOrEqual(0.09);
  });
});

describe('applying the colours to the page', () => {
  const set = vi.fn(), remove = vi.fn();
  afterEach(() => { set.mockReset(); remove.mockReset(); vi.unstubAllGlobals(); });
  const page = () => vi.stubGlobal('document', { documentElement: { style: { setProperty: set, removeProperty: remove } } });

  it('sets every step and the accent for a company colour', () => {
    page();
    applyBranding({ primaryColor: '#7A1F3D', accentColor: '#E07A5F' });
    expect(set.mock.calls.filter(([k]) => String(k).startsWith('--federal-'))).toHaveLength(9);
    expect(set).toHaveBeenCalledWith('--brand-accent', '224 122 95');
  });

  it('clears everything for no branding, an unreadable colour, or the PropertyOS default, so the built-in look is used exactly', () => {
    page();
    for (const b of [null, undefined, { primaryColor: 'red' }, { primaryColor: '#0f3d3e' }, { primaryColor: '' }]) {
      set.mockClear(); remove.mockClear();
      applyBranding(b as never);
      expect(set.mock.calls.filter(([k]) => String(k).startsWith('--federal-'))).toHaveLength(0);
      expect(remove.mock.calls.filter(([k]) => String(k).startsWith('--federal-'))).toHaveLength(9);
    }
  });
});
