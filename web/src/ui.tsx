// Building blocks of the phone web, matching the Figma components (M1~M4).
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import QRCode from 'qrcode';
import { demoMode, searchPlaces, type Place, type TripStatus } from './api';
import glyph from './assets/figma/glyph.svg';
import iconSearch from './assets/figma/icon-search.svg';
import radioOff from './assets/figma/radio-off.svg';
import radioOn from './assets/figma/radio-on.svg';
import checkboxOff from './assets/figma/checkbox-off.svg';

export function Brand({ right }: { right?: ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <Link to="/" className="flex items-center gap-2">
        <span
          className="relative size-7 shrink-0 rounded-[30px]"
          style={{ backgroundImage: 'linear-gradient(135deg, rgb(47, 107, 255) 0%, rgb(34, 195, 240) 100%)' }}
        >
          <img src={glyph} width={28} height={28} alt="" className="absolute inset-0" />
        </span>
        <span className="t-headline">페어풀(FairPool)</span>
      </Link>
      {right ??
        (demoMode && <span className="t-caption rounded-full bg-amber-100 px-2.5 py-1 text-amber-800">데모 모드</span>)}
    </div>
  );
}

export function Page({ children, back, gap = 16 }: { children: ReactNode; back?: string; gap?: 14 | 16 }) {
  return (
    <div
      className="mx-auto flex min-h-dvh max-w-[430px] flex-col px-5 pt-6 pb-7"
      style={{ gap, paddingTop: 'max(24px, env(safe-area-inset-top))' }}
    >
      <Brand
        right={
          back ? (
            <Link to={back} className="t-strong text-sub">
              뒤로
            </Link>
          ) : undefined
        }
      />
      {children}
    </div>
  );
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`flex w-full flex-col rounded-[20px] bg-card p-5 shadow-card ${className}`}>{children}</section>;
}

export function Chip({ children, tone = 'tile' }: { children: ReactNode; tone?: 'tile' | 'accent' }) {
  const look = tone === 'accent' ? 'bg-brand-soft text-brand' : 'bg-tile text-sub';
  return <span className={`t-caption rounded-full px-2.5 py-[5px] ${look}`}>{children}</span>;
}

export function Button({
  children,
  onClick,
  variant = 'primary',
  disabled,
  className = '',
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
  className?: string;
}) {
  const look = {
    primary: 't-headline bg-brand text-white',
    secondary: 't-strong border-[1.5px] border-line bg-card text-sub',
    danger: 't-strong border-[1.5px] border-line bg-card text-red-600',
  }[variant];
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex h-14 w-full items-center justify-center rounded-[14px] px-2 transition active:brightness-95 disabled:opacity-40 ${look} ${className}`}
    >
      {children}
    </button>
  );
}

export function TextInput({
  value,
  onChange,
  placeholder,
  inputMode,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  inputMode?: 'text' | 'numeric';
}) {
  return (
    <input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      inputMode={inputMode}
      className="t-body h-12 w-full rounded-xl bg-tile px-3.5 outline-none placeholder:text-faint focus:ring-2 focus:ring-brand"
    />
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <div className="flex w-full flex-col gap-2">
      <span className="t-strong">{label}</span>
      {children}
      {hint && <span className="t-caption text-faint">{hint}</span>}
    </div>
  );
}

// Four separate boxes over one real input, like the Figma "digits" row.
export function DigitsInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const ref = useRef<HTMLInputElement>(null);
  const [focused, setFocused] = useState(false);
  return (
    <div className="relative flex gap-2.5" onClick={() => ref.current?.focus()}>
      {[0, 1, 2, 3].map((i) => {
        const active = focused && i === Math.min(value.length, 3);
        return (
          <div
            key={i}
            className={`t-title flex h-[52px] w-[60px] items-center justify-center rounded-xl ${
              active ? 'border-2 border-brand bg-card' : 'bg-tile'
            }`}
          >
            {value[i] ?? ''}
          </div>
        );
      })}
      <input
        ref={ref}
        value={value}
        onChange={(e) => onChange(e.target.value.replace(/\D/g, '').slice(0, 4))}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        inputMode="numeric"
        autoComplete="off"
        aria-label="휴대폰 번호 뒤 4자리"
        className="absolute inset-0 opacity-0"
      />
    </div>
  );
}

export function OptionCard({
  selected,
  title,
  sub,
  onClick,
}: {
  selected: boolean;
  title: string;
  sub?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-3 rounded-xl px-3.5 py-3 text-left ${
        selected ? 'border-2 border-brand bg-brand-soft' : 'border-[1.5px] border-line bg-card'
      }`}
    >
      <img src={selected ? radioOn : radioOff} width={20} height={20} alt="" className="shrink-0" />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className={`t-strong ${selected ? 'text-brand' : ''}`}>{title}</span>
        {sub && <span className="t-caption text-sub">{sub}</span>}
      </span>
    </button>
  );
}

export function Checkbox({
  checked,
  onChange,
  title,
  sub,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  title: ReactNode;
  sub?: ReactNode;
}) {
  return (
    <button type="button" onClick={() => onChange(!checked)} className="flex w-full items-start gap-3 text-left">
      {checked ? (
        <span className="mt-0.5 flex size-[22px] shrink-0 items-center justify-center rounded-md bg-brand text-white">
          <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
            <path d="M2.5 7.2 5.6 10.2 11.5 3.8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      ) : (
        <img src={checkboxOff} width={22} height={22} alt="" className="mt-0.5 shrink-0" />
      )}
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="t-strong">{title}</span>
        {sub && <span className="t-caption text-sub">{sub}</span>}
      </span>
    </button>
  );
}

// Search box with results underneath. The chosen place keeps its coordinates for the map.
export function PlaceSearch({
  value,
  onChange,
  placeholder,
}: {
  value: Place | null;
  onChange: (p: Place | null) => void;
  placeholder: string;
}) {
  const [text, setText] = useState(value?.label ?? '');
  const [results, setResults] = useState<Place[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const id = window.setTimeout(() => {
      searchPlaces(text).then(setResults).catch(() => setResults([]));
    }, 300);
    return () => window.clearTimeout(id);
  }, [text, open]);

  function pick(p: Place) {
    setText(p.label);
    setOpen(false);
    onChange(p);
  }

  return (
    <div className="relative w-full">
      <label className="flex h-12 w-full items-center gap-2 rounded-xl bg-tile px-3.5 focus-within:ring-2 focus-within:ring-brand">
        <img src={iconSearch} width={20} height={20} alt="" className="shrink-0" />
        <input
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            setOpen(true);
            // only a picked result counts: the car needs its coordinates to set a waypoint
            onChange(null);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => window.setTimeout(() => setOpen(false), 150)}
          placeholder={placeholder}
          className="t-body min-w-0 flex-1 bg-transparent outline-none placeholder:text-faint"
        />
      </label>
      {!open && text.trim() && !value && <p className="t-caption mt-1.5 text-red-600">목록에서 장소를 골라 주세요</p>}
      {open && results.length > 0 && (
        <ul className="absolute top-[52px] z-10 w-full overflow-hidden rounded-xl bg-card shadow-card ring-1 ring-line">
          {results.map((p, i) => (
            <li key={i}>
              <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => pick(p)} className="flex w-full flex-col px-3.5 py-2.5 text-left hover:bg-page">
                <span className="t-strong">{p.label}</span>
                {p.area && <span className="t-caption text-sub">{p.area}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const statusText: Record<TripStatus, string> = {
  preparing: '출발 전',
  in_progress: '함께 가는 중',
  settled: '정산 완료',
  cancelled: '취소된 운행',
};

export function StatusPill({ status }: { status: TripStatus }) {
  const look = {
    preparing: 'bg-brand-soft text-brand',
    in_progress: 'bg-emerald-50 text-emerald-700',
    settled: 'bg-tile text-ink',
    cancelled: 'bg-red-50 text-red-600',
  }[status];
  return <span className={`t-caption shrink-0 rounded-full px-2.5 py-1 ${look}`}>{statusText[status]}</span>;
}

export function QrImage({ text, size = 240 }: { text: string; size?: number }) {
  const [src, setSrc] = useState('');
  useEffect(() => {
    QRCode.toDataURL(text, { margin: 1, width: size * 2, color: { dark: '#15181e', light: '#ffffff' } })
      .then(setSrc)
      .catch(() => setSrc(''));
  }, [text, size]);
  return src ? (
    <img src={src} width={size} height={size} alt="운행 참여 QR" className="rounded-xl" />
  ) : (
    <div style={{ width: size, height: size }} className="animate-pulse rounded-xl bg-tile" />
  );
}

export function ErrorText({ error }: { error: string | null }) {
  return error ? <p className="t-caption text-red-600">{error}</p> : null;
}

export const won = (n: number) => `${Math.round(n).toLocaleString('ko-KR')}원`;

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

// Re-fetch every few seconds while the page is visible, so status changes show up without reload.
export function usePolling(load: () => Promise<void>, ms = 5000) {
  const latest = useRef(load);
  latest.current = load;
  useEffect(() => {
    let alive = true;
    const tick = () => {
      if (alive && document.visibilityState === 'visible') latest.current().catch(() => undefined);
    };
    tick();
    const id = window.setInterval(tick, ms);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [ms]);
}
