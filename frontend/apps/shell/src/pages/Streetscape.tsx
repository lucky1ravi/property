import { useEffect, useMemo } from 'react';
import { motion, useMotionValue, useSpring, useTransform, type MotionValue } from 'framer-motion';

// An American residential street at dusk, drawn in layers. Windows switch on one by one
// as the page loads (each lit window is a door under management), and the layers move
// at different depths with the pointer and with scroll.

const GROUND = 640;
const W = 1600;

function rng(seed: number) {
  let s = seed;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

interface Win { x: number; y: number; w: number; h: number; delay: number; lit: boolean }

function windowsGrid(x: number, top: number, w: number, h: number, cols: number, rows: number, ww: number, wh: number, rand: () => number, litRatio: number, offset: number): Win[] {
  const out: Win[] = [];
  const gx = (w - cols * ww) / (cols + 1);
  const gy = (h - rows * wh) / (rows + 1);
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      out.push({ x: x + gx + c * (ww + gx), y: top + gy + r * (wh + gy), w: ww, h: wh, delay: offset + rand() * 2.6, lit: rand() < litRatio });
    }
  }
  return out;
}

function LitWindow({ win, unlit = '#1D2B2A', light = '#F4D58D' }: { win: Win; unlit?: string; light?: string }) {
  if (!win.lit) return <rect x={win.x} y={win.y} width={win.w} height={win.h} fill={unlit} rx={1} />;
  return (
    <motion.rect
      x={win.x} y={win.y} width={win.w} height={win.h} rx={1}
      initial={{ fill: unlit }}
      animate={{ fill: light }}
      transition={{ delay: win.delay, duration: 0.5, ease: 'easeOut' }}
    />
  );
}

function Layer({ children, x, y }: { children: React.ReactNode; x: MotionValue<number>; y: MotionValue<number> }) {
  return <motion.g style={{ x, y }}>{children}</motion.g>;
}

export function Streetscape({ scrollProgress }: { scrollProgress?: MotionValue<number> }) {
  const px = useMotionValue(0);
  const py = useMotionValue(0);
  const sx = useSpring(px, { stiffness: 60, damping: 18 });
  const sy = useSpring(py, { stiffness: 60, damping: 18 });
  const fallback = useMotionValue(0);
  const sp = scrollProgress ?? fallback;

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const onMove = (e: PointerEvent) => {
      px.set((e.clientX / window.innerWidth - 0.5) * 2);
      py.set((e.clientY / window.innerHeight - 0.5) * 2);
    };
    window.addEventListener('pointermove', onMove);
    return () => window.removeEventListener('pointermove', onMove);
  }, [px, py]);

  // Depth: far layers move little, near layers move most.
  const farX = useTransform(sx, (v) => v * -6);
  const midX = useTransform(sx, (v) => v * -14);
  const nearX = useTransform(sx, (v) => v * -26);
  const farY = useTransform([sy, sp] as any, ([a, b]: number[]) => a * -3 + b * -30);
  const midY = useTransform([sy, sp] as any, ([a, b]: number[]) => a * -6 + b * -80);
  const nearY = useTransform([sy, sp] as any, ([a, b]: number[]) => a * -10 + b * -150);
  const skyY = useTransform(sp, (b) => b * 40);

  const scene = useMemo(() => {
    const rand = rng(42);
    // Far skyline: downtown towers.
    const towers: { x: number; w: number; h: number; wins: Win[] }[] = [];
    let x = -40;
    while (x < W + 40) {
      const w = 50 + rand() * 70;
      const h = 150 + rand() * 230;
      towers.push({ x, w, h, wins: windowsGrid(x, GROUND - 120 - h, w, h, Math.max(2, Math.floor(w / 18)), Math.floor(h / 26), 6, 9, rand, 0.35, 0.2) });
      x += w + 6 + rand() * 18;
    }
    // Middle: brownstone row houses.
    const brownstones: { x: number; w: number; h: number; tone: string; wins: Win[] }[] = [];
    x = -20;
    const tones = ['#6E3326', '#7D3B2C', '#8A4433', '#5F2E24', '#76402F'];
    let i = 0;
    while (x < W + 20) {
      const w = 118 + rand() * 40;
      const h = 210 + rand() * 60;
      brownstones.push({ x, w, h, tone: tones[i % tones.length], wins: windowsGrid(x + 8, GROUND - 40 - h + 26, w - 16, h - 90, 3, 3, 18, 30, rand, 0.7, 0.9) });
      x += w;
      i++;
    }
    // Near: townhomes and a duplex with gabled roofs and porches.
    const homes: { x: number; w: number; h: number; tone: string; roof: string; wins: Win[]; door: number }[] = [];
    const homeTones = [['#D9DFDC', '#3A4644'], ['#BFCAC6', '#2B3533'], ['#E6E1D8', '#4F5B59'], ['#9FB3AF', '#1D2524'], ['#DCD5C9', '#3A4644']];
    x = 10;
    i = 0;
    while (x < W) {
      const w = 170 + rand() * 60;
      const h = 130 + rand() * 30;
      const [tone, roof] = homeTones[i % homeTones.length];
      homes.push({ x, w, h, tone, roof, wins: windowsGrid(x, GROUND - h, w, h - 40, 3, 2, 24, 26, rand, 0.8, 1.6), door: x + w / 2 - 12 });
      x += w + 70 + rand() * 50;
      i++;
    }
    const trees = Array.from({ length: 14 }, (_, k) => ({ x: 40 + k * 118 + rand() * 40, r: 26 + rand() * 18 }));
    return { towers, brownstones, homes, trees };
  }, []);

  return (
    <svg viewBox={`0 0 ${W} 720`} preserveAspectRatio="xMidYMax slice" className="absolute inset-0 h-full w-full" aria-hidden>
      <defs>
        <linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#082425" />
          <stop offset="45%" stopColor="#14494A" />
          <stop offset="78%" stopColor="#3F6E6B" />
          <stop offset="100%" stopColor="#C9A677" />
        </linearGradient>
        <linearGradient id="street" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#2B3533" />
          <stop offset="100%" stopColor="#121817" />
        </linearGradient>
      </defs>

      <motion.rect x={-100} y={-100} width={W + 200} height={900} fill="url(#dusk)" style={{ y: skyY }} />
      <motion.circle cx={1240} cy={170} r={34} fill="#F3E6C4" opacity={0.85} style={{ y: skyY }} />

      <Layer x={farX} y={farY}>
        {scene.towers.map((t, k) => (
          <g key={k}>
            <rect x={t.x} y={GROUND - 120 - t.h} width={t.w} height={t.h + 120} fill="#123D3E" />
            {t.wins.map((w, j) => <LitWindow key={j} win={w} unlit="#163F40" light="#C8B07A" />)}
          </g>
        ))}
      </Layer>

      <Layer x={midX} y={midY}>
        {scene.brownstones.map((b, k) => {
          const top = GROUND - 40 - b.h;
          return (
            <g key={k}>
              <rect x={b.x} y={top} width={b.w} height={b.h + 40} fill={b.tone} />
              <rect x={b.x - 3} y={top - 10} width={b.w + 6} height={12} fill="#3B1D16" />
              <rect x={b.x} y={top + 2} width={b.w} height={3} fill="#A86A52" opacity={0.5} />
              {b.wins.map((w, j) => <LitWindow key={j} win={w} />)}
              <rect x={b.x + b.w / 2 - 11} y={GROUND - 92} width={22} height={40} fill="#2A1510" />
              <path d={`M${b.x + b.w / 2 - 20} ${GROUND - 52} h40 l12 22 h-64 z`} fill="#4A2A21" />
            </g>
          );
        })}
      </Layer>

      <Layer x={nearX} y={nearY}>
        {scene.trees.map((t, k) => (
          <g key={`t${k}`}>
            <rect x={t.x - 3} y={GROUND - 40} width={6} height={50} fill="#1D2524" />
            <circle cx={t.x} cy={GROUND - 50 - t.r * 0.6} r={t.r} fill="#1B4A3F" />
            <circle cx={t.x - t.r * 0.5} cy={GROUND - 40 - t.r * 0.4} r={t.r * 0.7} fill="#205446" />
          </g>
        ))}
        {scene.homes.map((h, k) => {
          const top = GROUND - h.h;
          return (
            <g key={k}>
              <polygon points={`${h.x - 12},${top} ${h.x + h.w / 2},${top - 62} ${h.x + h.w + 12},${top}`} fill={h.roof} />
              <rect x={h.x} y={top} width={h.w} height={h.h + 20} fill={h.tone} />
              {h.wins.map((w, j) => <LitWindow key={j} win={w} unlit="#55625F" light="#F7DC97" />)}
              <rect x={h.door} y={GROUND - 46} width={24} height={46} fill="#0F3D3E" />
              <rect x={h.x + 10} y={GROUND - 54} width={h.w - 20} height={6} fill={h.roof} />
              <rect x={h.x + 14} y={GROUND - 48} width={4} height={48} fill={h.roof} />
              <rect x={h.x + h.w - 18} y={GROUND - 48} width={4} height={48} fill={h.roof} />
            </g>
          );
        })}
        <rect x={-100} y={GROUND} width={W + 200} height={16} fill="#8A9592" />
        <rect x={-100} y={GROUND + 16} width={W + 200} height={120} fill="url(#street)" />
        {Array.from({ length: 20 }, (_, k) => <rect key={k} x={k * 90} y={GROUND + 62} width={44} height={4} fill="#B4BDBA" opacity={0.5} />)}
        {Array.from({ length: 8 }, (_, k) => (
          <g key={`l${k}`}>
            <rect x={110 + k * 210} y={GROUND - 120} width={4} height={120} fill="#121817" />
            <motion.circle cx={112 + k * 210} cy={GROUND - 124} r={7} initial={{ fill: '#3A4644' }} animate={{ fill: '#FCE7AE' }} transition={{ delay: 0.4 + k * 0.12, duration: 0.4 }} />
          </g>
        ))}
      </Layer>
    </svg>
  );
}
