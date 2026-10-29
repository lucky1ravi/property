import { useEffect, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

/** Image carousel: arrows, dots, keyboard, swipe, thumbnails-free and dependency-free. */
export function Carousel({ images, alt = '', aspect = 'aspect-[16/9]', className = '' }: { images: (string | null | undefined)[]; alt?: string; aspect?: string; className?: string }) {
  const pics = images.filter((x): x is string => !!x);
  const [i, setI] = useState(0);
  const start = useRef<number | null>(null);
  useEffect(() => { if (i >= pics.length) setI(0); }, [pics.length, i]);
  if (pics.length === 0) return null;
  const go = (n: number) => setI((n + pics.length) % pics.length);
  const many = pics.length > 1;
  return (
    <div className={`group relative overflow-hidden rounded-md bg-limestone-200 ${className}`} role="region" aria-roledescription="carousel" aria-label={alt || 'Photos'}
      tabIndex={0} onKeyDown={(e) => { if (e.key === 'ArrowLeft') go(i - 1); if (e.key === 'ArrowRight') go(i + 1); }}
      onTouchStart={(e) => { start.current = e.touches[0].clientX; }}
      onTouchEnd={(e) => { if (start.current == null) return; const d = e.changedTouches[0].clientX - start.current; if (Math.abs(d) > 40) go(i + (d < 0 ? 1 : -1)); start.current = null; }}>
      <div className={`flex ${aspect} w-full transition-transform duration-500 ease-out motion-reduce:transition-none`} style={{ transform: `translateX(-${i * 100}%)` }}>
        {pics.map((src, n) => (
          <img key={src + n} src={src} alt={`${alt} photo ${n + 1} of ${pics.length}`} loading={n === 0 ? 'eager' : 'lazy'} className="h-full w-full shrink-0 object-cover" />
        ))}
      </div>
      {many && (<>
        <button type="button" aria-label="Previous photo" onClick={() => go(i - 1)} className="absolute left-2 top-1/2 -translate-y-1/2 rounded-full bg-white/90 p-1.5 text-graphite-900 shadow hover:bg-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600"><ChevronLeft className="h-5 w-5" /></button>
        <button type="button" aria-label="Next photo" onClick={() => go(i + 1)} className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-white/90 p-1.5 text-graphite-900 shadow hover:bg-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600"><ChevronRight className="h-5 w-5" /></button>
        <span className="absolute right-2 top-2 rounded-full bg-black/60 px-2 py-0.5 text-xs font-semibold text-white">{i + 1} / {pics.length}</span>
        <div className="absolute inset-x-0 bottom-2 flex justify-center gap-1.5">
          {pics.map((_, n) => (
            <button key={n} type="button" aria-label={`Show photo ${n + 1}`} aria-current={n === i} onClick={() => setI(n)} className={`h-2 rounded-full transition-all ${n === i ? 'w-5 bg-white' : 'w-2 bg-white/60 hover:bg-white/90'}`} />
          ))}
        </div>
      </>)}
    </div>
  );
}
