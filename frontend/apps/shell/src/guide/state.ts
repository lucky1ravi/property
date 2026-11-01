import { useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, useSession } from '@propertyos/ui';

/**
 * What a person has already been shown. Saved on their account (not in the browser), so it follows them to another device
 * and survives clearing site data.
 */
export interface GuideState {
  /** The welcome dialog has been answered, either way. */
  welcome?: boolean;
  /** Page tours already offered, taken or skipped: guide id to the time it happened. */
  tours?: Record<string, number>;
  /** The person asked not to be offered page tours. */
  noCoach?: boolean;
  /** The getting-started checklist was hidden. */
  checklistHidden?: boolean;
}

export function useGuideState() {
  const { session, token } = useSession();
  const qc = useQueryClient();
  const state: GuideState = session?.uiState?.guide ?? {};
  // Several changes can land in one tick (finish a tour, then mute offers); always build on the latest.
  const latest = useRef(state);
  latest.current = state;

  const save = (patch: Partial<GuideState>) => {
    const next: GuideState = { ...latest.current, ...patch };
    latest.current = next;
    // Show it at once, then store it. A failed save only means the tour may be offered again on another device.
    qc.setQueryData(['session', token], (old: any) => (old ? { ...old, uiState: { ...(old.uiState ?? {}), guide: next } } : old));
    api.put('/api/identity/me/ui-state', { guide: next }).catch(() => undefined);
  };
  return { state, save, ready: !!session };
}
