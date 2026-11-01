import { Children, cloneElement, isValidElement, useEffect, useRef, useState, type CSSProperties, type ElementType, type ReactElement, type ReactNode } from 'react';
import { animate, motion, useInView, useMotionValue, useReducedMotion, useScroll, useSpring, useTransform, type Variants } from 'framer-motion';

/**
 * Motion for the whole product, in one place so that everything moves the same way.
 *
 * Rules the pieces here follow:
 *  - Nothing moves for people who ask their device to reduce motion; they get the content, instantly.
 *  - Motion explains (what just appeared, where something came from) or rewards (a press, a hover). It never delays work:
 *    entrances are short and never block clicks.
 *  - Parallax is for marketing surfaces and decorative backgrounds, never for data.
 */
export const SPRING = { type: 'spring' as const, stiffness: 380, damping: 32 };
export const EASE = [0.22, 1, 0.36, 1] as const;

/** True when it is fine to animate. */
export function useMotionOK() {
  return !useReducedMotion();
}

type Direction = 'up' | 'down' | 'left' | 'right' | 'none';
const offset = (d: Direction, px: number) => ({ x: d === 'left' ? px : d === 'right' ? -px : 0, y: d === 'up' ? px : d === 'down' ? -px : 0 });

/** Fades and slides its content in the first time it scrolls into view. */
export function Reveal({ children, direction = 'up', distance = 28, delay = 0, duration = 0.6, amount = 0.2, once = true, as = 'div', className, style, ...rest }: {
  children: ReactNode; direction?: Direction; distance?: number; delay?: number; duration?: number; amount?: number; once?: boolean; as?: ElementType; className?: string; style?: CSSProperties; [attribute: string]: unknown;
}) {
  const ok = useMotionOK();
  const M: any = (motion as any)[as as string] ?? motion.div;
  if (!ok) { const Plain: any = as; return <Plain className={className} style={style} {...rest}>{children}</Plain>; }
  return (
    <M className={className} style={style} {...rest} initial={{ opacity: 0, ...offset(direction, distance) }} whileInView={{ opacity: 1, x: 0, y: 0 }} viewport={{ once, amount }}
      transition={{ duration, delay, ease: EASE }}>
      {children}
    </M>
  );
}

const container = (gap: number, delay: number): Variants => ({ hidden: {}, show: { transition: { staggerChildren: gap, delayChildren: delay } } });
const item = (d: Direction, px: number): Variants => ({ hidden: { opacity: 0, ...offset(d, px) }, show: { opacity: 1, x: 0, y: 0, transition: { duration: 0.55, ease: EASE } } });

/** Children that are {@link StaggerItem}s appear one after another as the group scrolls into view. */
export function Stagger({ children, gap = 0.08, delay = 0, amount = 0.15, as = 'div', className, ...rest }: { children: ReactNode; gap?: number; delay?: number; amount?: number; as?: ElementType; className?: string; [attribute: string]: unknown }) {
  const ok = useMotionOK();
  const M: any = (motion as any)[as as string] ?? motion.div;
  if (!ok) { const Plain: any = as; return <Plain className={className} {...rest}>{children}</Plain>; }
  return <M className={className} {...rest} variants={container(gap, delay)} initial="hidden" whileInView="show" viewport={{ once: true, amount }}>{children}</M>;
}

/** One thing in a {@link Stagger}. */
export function StaggerItem({ children, direction = 'up', distance = 22, as = 'div', className, ...rest }: { children: ReactNode; direction?: Direction; distance?: number; as?: ElementType; className?: string; [attribute: string]: unknown }) {
  const ok = useMotionOK();
  const M: any = (motion as any)[as as string] ?? motion.div;
  if (!ok) { const Plain: any = as; return <Plain className={className} {...rest}>{children}</Plain>; }
  return <M className={className} {...rest} variants={item(direction, distance)}>{children}</M>;
}

/**
 * Moves its content more slowly (or faster) than the page scrolls, so it seems to sit at a different depth.
 * `factor` is how far it travels relative to the scroll: 0.2 drifts down slowly like a background; -0.1 drifts up.
 * With `within`, the effect is measured as the element crosses the screen instead of from the top of the page.
 */
export function Parallax({ children, factor = 0.2, within = false, className, style }: { children: ReactNode; factor?: number; within?: boolean; className?: string; style?: CSSProperties }) {
  const ok = useMotionOK();
  const ref = useRef<HTMLDivElement>(null);
  const page = useScroll();
  const local = useScroll({ target: ref, offset: ['start end', 'end start'] });
  // within: -1..1 across the element's pass through the screen, scaled to a few hundred pixels
  const yPage = useTransform(page.scrollY, (v) => v * factor);
  const yLocal = useTransform(local.scrollYProgress, [0, 1], [-factor * 400, factor * 400]);
  const y = useSpring(within ? yLocal : yPage, { stiffness: 120, damping: 28, mass: 0.4 });
  if (!ok) return <div ref={ref} className={className} style={style}>{children}</div>;
  return <motion.div ref={ref} className={className} style={{ ...style, y }}>{children}</motion.div>;
}

/** Tilts toward the pointer in 3D and settles back when it leaves. For product shots and feature cards; ignored on touch screens. */
export function Tilt({ children, max = 7, scale = 1.015, className }: { children: ReactNode; max?: number; scale?: number; className?: string }) {
  const ok = useMotionOK();
  const rx = useMotionValue(0);
  const ry = useMotionValue(0);
  const sx = useSpring(rx, { stiffness: 180, damping: 18 });
  const sy = useSpring(ry, { stiffness: 180, damping: 18 });
  const [hover, setHover] = useState(false);
  const coarse = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches;
  if (!ok || coarse) return <div className={className}>{children}</div>;
  return (
    <motion.div className={className} style={{ rotateX: sx, rotateY: sy, transformPerspective: 1000, transformStyle: 'preserve-3d' }} animate={{ scale: hover ? scale : 1 }} transition={SPRING}
      onPointerMove={(e) => {
        const r = e.currentTarget.getBoundingClientRect();
        ry.set(((e.clientX - r.left) / r.width - 0.5) * 2 * max);
        rx.set(-((e.clientY - r.top) / r.height - 0.5) * 2 * max);
      }}
      onPointerEnter={() => setHover(true)} onPointerLeave={() => { setHover(false); rx.set(0); ry.set(0); }}>
      {children}
    </motion.div>
  );
}

/** Drifts gently up and down. Decoration only. */
export function Float({ children, amplitude = 8, duration = 6, delay = 0, className }: { children: ReactNode; amplitude?: number; duration?: number; delay?: number; className?: string }) {
  const ok = useMotionOK();
  if (!ok) return <div className={className}>{children}</div>;
  return <motion.div className={className} animate={{ y: [0, -amplitude, 0] }} transition={{ duration, delay, repeat: Infinity, ease: 'easeInOut' }}>{children}</motion.div>;
}

/** Counts up to a figure the first time it is seen, and glides to the new figure when it changes. Keeps its prefix, suffix, commas and decimals. */
export function CountUp({ value, duration = 1.1, className }: { value: string | number; duration?: number; className?: string }) {
  const ok = useMotionOK();
  const ref = useRef<HTMLSpanElement>(null);
  const seen = useInView(ref, { once: true, amount: 0.4 });
  const text = String(value);
  const parsed = /^([^\d-]*)(-?[\d,]*\.?\d+)(.*)$/.exec(text);
  // Only figures count up. A date such as "Oct 30, 2026" or a range such as "12 / 15" is shown as it is.
  const m = parsed && /^[$€£¥+~-]?$/.test(parsed[1]) && /^(%|[kKmMbB]?|\s?[A-Za-z/]{0,6})$/.test(parsed[3]) ? parsed : null;
  const prev = useRef(0);
  const [shown, setShown] = useState(m && ok ? `${m[1]}${format(0, m[2])}${m[3]}` : text);
  useEffect(() => {
    if (!m || !ok) { setShown(text); return; }
    if (!seen) return;
    const to = parseFloat(m[2].replace(/,/g, ''));
    const controls = animate(prev.current, to, { duration, ease: EASE, onUpdate: (v) => setShown(`${m[1]}${format(v, m[2])}${m[3]}`), onComplete: () => { prev.current = to; setShown(text); } });
    return () => controls.stop();
  }, [text, seen, ok]); // eslint-disable-line react-hooks/exhaustive-deps
  return <span ref={ref} className={className}>{shown}</span>;
}
function format(n: number, like: string) {
  const decimals = (like.split('.')[1] ?? '').length;
  const fixed = Math.abs(n).toFixed(decimals);
  const [int, dec] = fixed.split('.');
  const withCommas = like.includes(',') ? int.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : int;
  return (n < 0 ? '-' : '') + withCommas + (dec ? '.' + dec : '');
}

/** A thin bar across the top that fills as the page is read. */
export function ScrollProgress({ className }: { className?: string }) {
  const ok = useMotionOK();
  const { scrollYProgress } = useScroll();
  const scaleX = useSpring(scrollYProgress, { stiffness: 140, damping: 28, restDelta: 0.001 });
  if (!ok) return null;
  return <motion.div aria-hidden className={className ?? 'fixed inset-x-0 top-0 z-[60] h-[3px] origin-left bg-federal-600'} style={{ scaleX }} />;
}

/** Fades the content of a page in whenever `id` changes (for example the URL), so moving between pages feels connected. */
export function PageTransition({ id, children, className }: { id: string; children: ReactNode; className?: string }) {
  const ok = useMotionOK();
  if (!ok) return <div className={className}>{children}</div>;
  return <motion.div key={id} className={className} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.26, ease: EASE }}>{children}</motion.div>;
}

/** Gives each child that is a {@link StaggerItem}-like element a delay, for lists that are not in a scroll-reveal group. */
export function withDelays(children: ReactNode, step = 0.06): ReactNode {
  return Children.map(children, (c, i) => (isValidElement(c) ? cloneElement(c as ReactElement<any>, { delay: i * step }) : c));
}
