import { type ReactNode, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { motion } from 'framer-motion';
import { keepPreviousData } from '@tanstack/react-query';
import clsx from 'clsx';
import { Check, ChevronDown, Search, X } from 'lucide-react';
import { useApi } from '../lib/hooks';
import { Avatar, Badge, Spinner } from './primitives';

/**
 * Searchable pickers for choosing a record (person, owner, property, unit, vendor…) from a list that may hold thousands.
 *
 * They never load the whole list: each keystroke (after a short pause) asks the server for the best matches, at most
 * {@code pageSize} of them, and the footer says how many more matched. Point `endpoint` at any list endpoint that accepts
 * `q`, `page` and `size` (every paged list does); a plain array is also accepted and is searched in the browser.
 */
export interface PickerSource<T> {
  /** List endpoint, with any fixed filters already in its query string. */
  endpoint: string;
  /** Extra query parameters, for example `{ status: 'VACANT' }`. */
  params?: Record<string, string>;
  /** Matches to fetch per search. Default 20. */
  pageSize?: number;
  /** The record's id. Default `item.id`. */
  getId?: (item: T) => string;
  /** Main line for a record. */
  label: (item: T) => string;
  /** Smaller second line, such as an email or address. */
  sub?: (item: T) => string | null | undefined;
  /** Small tag on the right, such as a role or relationship. */
  tag?: (item: T) => string | null | undefined;
  /** Show a photo/initials avatar, for people. */
  avatar?: boolean;
  /** A small picture before the label, such as an amenity's icon. */
  icon?: (item: T) => ReactNode;
  /** Text under the search box before anything is typed. */
  hint?: string;
  /** Search over this text in the browser when the endpoint returns a plain array. Defaults to label + sub. */
  searchText?: (item: T) => string;
}

interface Found<T> { rows: T[]; total: number; loading: boolean; error: boolean }

/** Debounced server-side search, keeping the previous results on screen while the next ones load. */
function useFound<T>(src: PickerSource<T>, q: string, enabled: boolean): Found<T> {
  const size = src.pageSize ?? 20;
  const [term, setTerm] = useState(q);
  useEffect(() => { const t = setTimeout(() => setTerm(q), 250); return () => clearTimeout(t); }, [q]);
  const search = new URLSearchParams({ page: '1', size: String(size), ...(src.params ?? {}), ...(term.trim() ? { q: term.trim() } : {}) });
  const url = `${src.endpoint}${src.endpoint.includes('?') ? '&' : '?'}${search.toString()}`;
  const query = useApi<any>(enabled ? url : null, { placeholderData: keepPreviousData, staleTime: 30_000 });
  const data = query.data;
  const needle = term.trim().toLowerCase();
  return useMemo(() => {
    if (!data) return { rows: [], total: 0, loading: query.isLoading, error: !!query.error };
    if (Array.isArray(data)) {
      // An endpoint that doesn't page: search what it sent here.
      const text = (x: T) => (src.searchText ? src.searchText(x) : `${src.label(x)} ${src.sub?.(x) ?? ''}`).toLowerCase();
      const hit = needle ? data.filter((x: T) => text(x).includes(needle)) : data;
      return { rows: hit.slice(0, size), total: hit.length, loading: false, error: false };
    }
    const rows: T[] = data.rows ?? data.items ?? [];
    return { rows, total: typeof data.total === 'number' ? data.total : rows.length, loading: query.isFetching, error: false };
  }, [data, needle, size, query.isFetching, query.isLoading, query.error]); // eslint-disable-line react-hooks/exhaustive-deps
}

/** A floating list pinned under its anchor. Rendered in a portal so a dialog's scrolling never clips it. */
function Popover({ anchor, open, children }: { anchor: React.RefObject<HTMLElement>; open: boolean; children: ReactNode }) {
  const [box, setBox] = useState<{ left: number; top: number; width: number; flip: boolean } | null>(null);
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const r = anchor.current?.getBoundingClientRect();
      if (!r) return;
      const room = window.innerHeight - r.bottom;
      setBox({ left: r.left, top: room < 280 && r.top > room ? r.top : r.bottom, width: r.width, flip: room < 280 && r.top > room });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, anchor]);
  if (!open || !box) return null;
  return createPortal(
    <motion.div initial={{ opacity: 0, y: box.flip ? 6 : -6, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.14 }}
      style={{ position: 'fixed', left: box.left, width: box.width, top: box.flip ? undefined : box.top + 4, bottom: box.flip ? window.innerHeight - box.top + 4 : undefined, zIndex: 80 }}
      className="overflow-hidden rounded-md border border-limestone-300 bg-white shadow-xl" onMouseDown={(e) => e.preventDefault()}>
      {children}
    </motion.div>, document.body);
}

function Results<T>({ src, found, q, picked, onPick, active, setActive, listId }: {
  src: PickerSource<T>; found: Found<T>; q: string; picked: (id: string) => boolean; onPick: (item: T) => void; active: number; setActive: (i: number) => void; listId: string;
}) {
  const idOf = src.getId ?? ((x: any) => String(x.id));
  const activeRef = useRef<HTMLLIElement>(null);
  useEffect(() => { activeRef.current?.scrollIntoView({ block: 'nearest' }); }, [active]);
  return (
    <div>
      <ul id={listId} role="listbox" className="max-h-64 overflow-y-auto py-1">
        {found.rows.map((item, i) => {
          const id = idOf(item), sub = src.sub?.(item), tag = src.tag?.(item), on = picked(id);
          return (
            <li key={id} id={`${listId}-${i}`} ref={i === active ? activeRef : undefined} role="option" aria-selected={on}
              onMouseEnter={() => setActive(i)} onClick={() => onPick(item)}
              className={clsx('flex cursor-pointer items-center gap-2.5 px-3 py-2 text-sm', i === active ? 'bg-federal-50' : '')}>
              {src.avatar && <Avatar name={src.label(item)} size={28} seed={id} />}
              {src.icon && <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800">{src.icon(item)}</span>}
              <span className="min-w-0 flex-1">
                <span className="block truncate font-semibold text-graphite-900">{src.label(item)}</span>
                {sub && <span className="block truncate text-xs text-graphite-500">{sub}</span>}
              </span>
              {tag && <Badge>{tag}</Badge>}
              {on && <Check className="h-4 w-4 shrink-0 text-federal-800" aria-hidden />}
            </li>
          );
        })}
      </ul>
      {found.rows.length === 0 && (
        <p className="px-3 py-3 text-sm text-graphite-500">{found.loading ? 'Searching…' : found.error ? 'Could not load. Try again.' : q.trim() ? `No matches for “${q.trim()}”.` : 'Nothing to choose from.'}</p>
      )}
      <div className="flex items-center justify-between gap-2 border-t border-limestone-200 bg-limestone-50 px-3 py-1.5 text-xs text-graphite-500">
        <span>
          {found.loading && found.rows.length > 0 ? 'Searching…' : found.total > found.rows.length
            ? `Showing ${found.rows.length} of ${found.total.toLocaleString()}. Keep typing to narrow it down.`
            : found.rows.length > 0 ? `${found.total.toLocaleString()} ${found.total === 1 ? 'match' : 'matches'}` : src.hint ?? 'Type to search'}
        </span>
        <span className="hidden sm:inline">↑↓ to move · Enter to choose · Esc to close</span>
      </div>
    </div>
  );
}

/** Shared open/close, search text, highlighted row and keyboard handling. */
function usePickerState<T>(src: PickerSource<T>, onPick: (item: T) => void, keepOpen: boolean) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [active, setActive] = useState(0);
  const found = useFound(src, q, open);
  const listId = useId();
  useEffect(() => setActive(0), [q, open]);
  const choose = (item: T) => { onPick(item); if (!keepOpen) { setOpen(false); setQ(''); } };
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive((a) => Math.min(a + 1, Math.max(0, found.rows.length - 1))); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter' && open && found.rows[active]) { e.preventDefault(); choose(found.rows[active]); }
    else if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); setOpen(false); setQ(''); }
  };
  return { open, setOpen, q, setQ, active, setActive, found, listId, choose, onKeyDown };
}

const boxCls = 'flex min-h-9 w-full items-center gap-2 rounded border border-limestone-300 bg-white px-3 text-sm text-graphite-900 focus-within:border-federal-700 focus-within:ring-2 focus-within:ring-federal-700/15';

/** Choose one record by searching. `selected` shows the current choice before the user has searched. */
export function EntityPicker<T>({ value, onChange, selected, placeholder = 'Search…', disabled, clearable = true, ...src }: PickerSource<T> & {
  value: string | null | undefined; onChange: (id: string | null, item: T | null) => void; selected?: T | null; placeholder?: string; disabled?: boolean; clearable?: boolean;
}) {
  const idOf = src.getId ?? ((x: any) => String(x.id));
  const [chosen, setChosen] = useState<T | null>(selected ?? null);
  useEffect(() => { if (selected) setChosen(selected); }, [selected]);
  useEffect(() => { if (!value) setChosen(null); }, [value]);
  const st = usePickerState<T>(src, (item) => { setChosen(item); onChange(idOf(item), item); }, false);
  const anchor = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const current = value && chosen && idOf(chosen) === value ? chosen : null;
  const sub = current ? src.sub?.(current) : null;
  return (
    <div ref={anchor} className="relative">
      <div className={clsx(boxCls, disabled && 'cursor-not-allowed bg-limestone-100')} onClick={() => { if (!disabled) { st.setOpen(true); input.current?.focus(); } }}>
        {st.open ? <Search className="h-4 w-4 shrink-0 text-graphite-400" aria-hidden />
          : current && src.avatar ? <Avatar name={src.label(current)} size={22} seed={idOf(current)} /> : null}
        {st.open || !current ? (
          <input ref={input} role="combobox" aria-expanded={st.open} aria-controls={st.listId} aria-autocomplete="list" aria-activedescendant={st.open ? `${st.listId}-${st.active}` : undefined}
            className="h-9 min-w-0 flex-1 bg-transparent outline-none placeholder:text-graphite-400" disabled={disabled}
            placeholder={st.open && current ? `${src.label(current)} — type to change` : placeholder} value={st.q}
            onFocus={() => st.setOpen(true)} onBlur={() => { st.setOpen(false); st.setQ(''); }} onChange={(e) => { st.setQ(e.target.value); st.setOpen(true); }} onKeyDown={st.onKeyDown} />
        ) : (
          <button type="button" className="h-9 min-w-0 flex-1 truncate text-left" disabled={disabled} onClick={(e) => { e.stopPropagation(); st.setOpen(true); setTimeout(() => input.current?.focus(), 0); }}>
            <span className="font-semibold">{src.label(current)}</span>{sub && <span className="ml-2 text-xs text-graphite-500">{sub}</span>}
          </button>
        )}
        {current && clearable && !disabled && !st.open && (
          <button type="button" aria-label="Clear selection" className="rounded p-0.5 text-graphite-400 hover:bg-limestone-100 hover:text-graphite-800" onClick={(e) => { e.stopPropagation(); setChosen(null); onChange(null, null); }}><X className="h-4 w-4" /></button>
        )}
        {st.found.loading && st.open ? <Spinner className="h-4 w-4" /> : <ChevronDown className="h-4 w-4 shrink-0 text-graphite-400" aria-hidden />}
      </div>
      <Popover anchor={anchor} open={st.open}>
        <Results src={src} found={st.found} q={st.q} picked={(id) => id === value} onPick={st.choose} active={st.active} setActive={st.setActive} listId={st.listId} />
      </Popover>
    </div>
  );
}

/** Choose several records by searching; the choices show as removable chips. */
export function MultiEntityPicker<T>({ value, onChange, selected, placeholder = 'Search to add…', disabled, max, ...src }: PickerSource<T> & {
  value: string[]; onChange: (ids: string[], items: T[]) => void; selected?: T[]; placeholder?: string; disabled?: boolean; max?: number;
}) {
  const idOf = src.getId ?? ((x: any) => String(x.id));
  const known = useRef(new Map<string, T>());
  (selected ?? []).forEach((x) => known.current.set(idOf(x), x));
  const toggle = (item: T) => {
    const id = idOf(item);
    known.current.set(id, item);
    const next = value.includes(id) ? value.filter((x) => x !== id) : max && value.length >= max ? value : [...value, id];
    onChange(next, next.map((x) => known.current.get(x)!).filter(Boolean));
  };
  const st = usePickerState<T>(src, toggle, true);
  const anchor = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const remove = (id: string) => { const next = value.filter((x) => x !== id); onChange(next, next.map((x) => known.current.get(x)!).filter(Boolean)); };
  return (
    <div>
      {value.length > 0 && (
        <ul className="mb-2 flex flex-wrap gap-1.5" aria-label="Chosen">
          {value.map((id) => { const item = known.current.get(id); return (
            <li key={id} className="inline-flex max-w-full items-center gap-1.5 rounded-full border border-limestone-300 bg-limestone-50 py-0.5 pl-1 pr-1.5 text-sm">
              {src.avatar && item && <Avatar name={src.label(item)} size={20} seed={id} />}
              {src.icon && item && <span className="text-federal-800">{src.icon(item)}</span>}
              <span className="truncate font-medium">{item ? src.label(item) : id}</span>
              {item && src.tag?.(item) && <span className="text-xs text-graphite-500">{src.tag(item)}</span>}
              {!disabled && <button type="button" aria-label={`Remove ${item ? src.label(item) : 'this one'}`} className="rounded-full p-0.5 text-graphite-400 hover:bg-limestone-200 hover:text-graphite-900" onClick={() => remove(id)}><X className="h-3.5 w-3.5" /></button>}
            </li>); })}
        </ul>
      )}
      <div ref={anchor} className="relative">
        <div className={clsx(boxCls, disabled && 'cursor-not-allowed bg-limestone-100')} onClick={() => { if (!disabled) input.current?.focus(); }}>
          <Search className="h-4 w-4 shrink-0 text-graphite-400" aria-hidden />
          <input ref={input} role="combobox" aria-expanded={st.open} aria-controls={st.listId} aria-autocomplete="list" aria-activedescendant={st.open ? `${st.listId}-${st.active}` : undefined}
            className="h-9 min-w-0 flex-1 bg-transparent outline-none placeholder:text-graphite-400" disabled={disabled || (!!max && value.length >= max)}
            placeholder={max && value.length >= max ? `Limit of ${max} reached` : placeholder} value={st.q}
            onFocus={() => st.setOpen(true)} onBlur={() => { st.setOpen(false); st.setQ(''); }} onChange={(e) => { st.setQ(e.target.value); st.setOpen(true); }}
            onKeyDown={(e) => { if (e.key === 'Backspace' && !st.q && value.length) remove(value[value.length - 1]); else st.onKeyDown(e); }} />
          {st.found.loading && st.open && <Spinner className="h-4 w-4" />}
        </div>
        <Popover anchor={anchor} open={st.open}>
          <Results src={src} found={st.found} q={st.q} picked={(id) => value.includes(id)} onPick={st.choose} active={st.active} setActive={st.setActive} listId={st.listId} />
        </Popover>
      </div>
    </div>
  );
}
