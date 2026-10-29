// Cross-bundle toasts: any micro-frontend dispatches; the shell renders them.
export type ToastTone = 'success' | 'error' | 'info';

/**
 * A message shown briefly in the corner.
 */
export interface ToastMessage {
  id: string;
  tone: ToastTone;
  title: string;
  body?: string;
}

/**
 * Shows a brief message.
 *
 * @param title what happened
 * @param opts  tone and extra detail
 */
export function toast(title: string, opts: { tone?: ToastTone; body?: string } = {}) {
  const detail: ToastMessage = { id: Math.random().toString(36).slice(2), tone: opts.tone ?? 'success', title, body: opts.body };
  window.dispatchEvent(new CustomEvent<ToastMessage>('pos:toast', { detail }));
}

import { errorText } from './api';

/**
 * Shows a failure message, using the error's own text when it has one.
 */
export const toastError = (e: unknown, fallback = 'That didn’t work') =>
  toast(fallback, { tone: 'error', body: errorText(e) });
