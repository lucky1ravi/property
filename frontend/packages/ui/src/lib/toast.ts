// Cross-bundle toasts: any micro-frontend dispatches; the shell renders them.
/** green: it worked · yellow: it needs your attention (a rule, a missing detail, no access) · red: something broke · blue: for your information */
export type ToastTone = 'success' | 'warning' | 'error' | 'info';

/**
 * A message shown briefly in the corner.
 */
export interface ToastMessage {
  id: string;
  tone: ToastTone;
  title: string;
  body?: string;
  /** A support reference to quote when something broke. */
  ref?: string;
}

/**
 * Shows a brief message.
 *
 * @param title what happened
 * @param opts  tone and extra detail
 */
export function toast(title: string, opts: { tone?: ToastTone; body?: string; ref?: string } = {}) {
  const detail: ToastMessage = { id: Math.random().toString(36).slice(2), tone: opts.tone ?? 'success', title, body: opts.body, ref: opts.ref };
  window.dispatchEvent(new CustomEvent<ToastMessage>('pos:toast', { detail }));
}

import { ApiError, errorText } from './api';

/** What went wrong, in a few words, for the heading of the message. */
function errorTitle(e: unknown): string {
  const status = e instanceof ApiError ? e.status : -1;
  if (status === 0) return 'You’re offline';
  if (status === 400 || status === 422) return 'Please check what you entered';
  if (status === 401) return 'Please sign in again';
  if (status === 403) return 'You don’t have access';
  if (status === 404) return 'We couldn’t find that';
  if (status === 405) return 'That isn’t available';
  if (status === 409) return 'That can’t be done right now';
  if (status === 413) return 'That file is too large';
  if (status === 429) return 'Slow down for a moment';
  if (status >= 500) return 'Something went wrong';
  return 'That didn’t work';
}

/**
 * Shows a failure. Yellow when the person can fix it (a rule, a missing detail, no access); red when something broke on our side or
 * the connection failed, with the support reference to quote. The server's own sentence is used when it has one.
 *
 * @param e        what was thrown
 * @param fallback a heading of your own; otherwise one is chosen from what happened
 */
export function toastError(e: unknown, fallback?: string) {
  const status = e instanceof ApiError ? e.status : -1;
  const broke = status === -1 || status === 0 || status === 405 || status >= 500; // 405 means the screen asked for something that doesn't exist: ours to fix
  toast(fallback ?? errorTitle(e), { tone: broke ? 'error' : 'warning', body: errorText(e), ref: broke && e instanceof ApiError ? e.correlationId : undefined });
}
