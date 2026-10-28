export function Logo({ light, compact }: { light?: boolean; compact?: boolean }) {
  return (
    <span className="inline-flex items-center gap-2.5">
      <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden>
        <rect width="32" height="32" rx="7" fill={light ? '#F5F7F6' : '#0F3D3E'} />
        <path d="M8 15.5 16 9l8 6.5V24h-5.5v-5h-5v5H8z" fill={light ? '#0F3D3E' : '#F5F7F6'} />
        <rect x="14.5" y="19" width="3" height="5" fill="#A23E2B" />
      </svg>
      {!compact && (
        <span className={`text-[19px] font-black tracking-[-0.03em] ${light ? 'text-white' : 'text-federal-900'}`}>
          Property<span className={light ? 'text-federal-200' : 'text-federal-600'}>OS</span>
        </span>
      )}
    </span>
  );
}
