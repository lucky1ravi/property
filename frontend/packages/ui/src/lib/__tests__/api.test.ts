import { describe, expect, it } from 'vitest';
import { ApiError, errorText, friendlyMessage } from '../api';

describe('friendlyMessage', () => {
  it('keeps a server message written for people', () => {
    expect(friendlyMessage(400, 'Rent must be a number')).toBe('Rent must be a number');
  });

  it('replaces technical server text with a plain sentence', () => {
    const msg = friendlyMessage(400, 'org.springframework.dao.DataIntegrityViolationException: duplicate key');
    expect(msg).not.toMatch(/springframework|exception/i);
  });

  it('explains the common failures in plain words', () => {
    expect(friendlyMessage(401)).toMatch(/sign in again/i);
    expect(friendlyMessage(403)).toMatch(/don’t have access/i);
    expect(friendlyMessage(404)).toMatch(/couldn’t find/i);
    expect(friendlyMessage(409)).toMatch(/conflicts/i);
    expect(friendlyMessage(429)).toMatch(/wait a moment/i);
  });

  it('never shows a server stack to the user for 5xx errors', () => {
    expect(friendlyMessage(500, 'NullPointerException at com.propertyos.X')).toMatch(/something went wrong/i);
    expect(friendlyMessage(503)).toMatch(/temporarily unavailable/i);
  });

  it('tells the user when the API cannot be reached', () => {
    expect(friendlyMessage(0)).toMatch(/can’t connect/i);
  });
});

describe('errorText', () => {
  it('reads an ApiError through the same rules', () => {
    expect(errorText(new ApiError(403, 'Forbidden', 'FORBIDDEN'))).toMatch(/don’t have access/i);
  });

  it('treats a failed fetch as being offline', () => {
    expect(errorText(new TypeError('Failed to fetch'))).toMatch(/can’t connect/i);
  });

  it('falls back to a safe sentence for unknown values', () => {
    expect(errorText({ weird: true })).toBe('Something went wrong. Please try again.');
    expect(errorText(new Error('java.lang.IllegalStateException: boom'))).toBe('Something went wrong. Please try again.');
  });
});
