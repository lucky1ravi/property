import { createContext, useContext } from 'react';

export interface GuideApi {
  /** A tour of the page the person is on. */
  startPageTour: () => void;
  /** The first-time tour of the whole workspace. */
  startAppTour: () => void;
  openHelp: () => void;
  openPalette: () => void;
  /** Offer every tour again, as if the person had just arrived. */
  resetGuide: () => void;
}

export const GuideContext = createContext<GuideApi | null>(null);

export function useGuide(): GuideApi {
  const g = useContext(GuideContext);
  if (!g) throw new Error('useGuide must be used inside GuideProvider');
  return g;
}
