import { describe, expect, it } from 'vitest';
import { fmt } from '../format';

describe('fmt', () => {
  it('formats US dollars with and without cents', () => {
    expect(fmt.usd(1250)).toBe('$1,250');
    expect(fmt.usd('1250.5', true)).toBe('$1,250.50');
  });

  it('treats missing or invalid amounts as zero', () => {
    expect(fmt.usd(null)).toBe('$0');
    expect(fmt.usd('abc')).toBe('$0');
  });

  it('formats dates without shifting the day for date-only strings', () => {
    expect(fmt.date('2026-10-29')).toBe('Oct 29, 2026');
    expect(fmt.date(null)).toBe('—');
  });

  it('turns codes into readable words', () => {
    expect(fmt.title('PENDING_REVIEW')).toBe('Pending review');
    expect(fmt.title(undefined)).toBe('');
  });

  it('describes recent and upcoming times in words', () => {
    expect(fmt.relative(new Date(Date.now() - 2 * 3600_000).toISOString())).toBe('2 hours ago');
    expect(fmt.relative(new Date(Date.now() + 3 * 86400_000 + 5000).toISOString())).toBe('in 3 days');
  });
});
