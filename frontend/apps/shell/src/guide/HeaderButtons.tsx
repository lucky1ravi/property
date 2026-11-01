import { CircleHelp, Search } from 'lucide-react';
import { useGuide } from './context';

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent);

/** The two buttons in the top bar that make the product easy to learn and quick to move around: search, and help. */
export function GuideHeaderButtons() {
  const { openPalette, openHelp } = useGuide();
  return (
    <div className="flex items-center gap-1.5">
      <button type="button" data-tour="header-search" onClick={openPalette} aria-label="Search" aria-keyshortcuts="Control+K Meta+K"
        className="group hidden h-9 w-[260px] items-center gap-2 rounded-lg border border-limestone-300 bg-limestone-50 px-3 text-sm text-graphite-500 transition-colors hover:border-federal-300 hover:bg-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-federal-700 md:flex lg:w-[320px]">
        <Search className="h-4 w-4 text-graphite-400 group-hover:text-federal-700" aria-hidden />
        <span className="flex-1 truncate text-left">Search or jump to…</span>
        <kbd className="rounded border border-limestone-300 bg-white px-1.5 py-0.5 text-[11px] font-semibold text-graphite-500">{isMac ? '⌘K' : 'Ctrl K'}</kbd>
      </button>
      <button type="button" data-tour="header-search" onClick={openPalette} aria-label="Search" className="rounded p-2 text-graphite-600 hover:bg-limestone-100 md:hidden"><Search className="h-5 w-5" /></button>
      <button type="button" data-tour="header-help" onClick={openHelp} aria-label="Help" aria-keyshortcuts="?" title="Help (press ?)"
        className="inline-flex h-9 items-center gap-1.5 rounded-lg px-2.5 text-sm font-semibold text-graphite-700 hover:bg-limestone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-federal-700">
        <CircleHelp className="h-5 w-5 text-federal-700" aria-hidden /><span className="hidden sm:inline">Help</span>
      </button>
    </div>
  );
}
