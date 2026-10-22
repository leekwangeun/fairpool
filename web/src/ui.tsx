// Building blocks of the phone web, matching the Figma components (M1~M4).
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import QRCode from 'qrcode';
import { demoMode, issueKinds, searchPlaces, type Bank, type BatchStatus, type IssueKind, type Place, type TripStatus } from './api';
import glyph from './assets/figma/glyph.svg';
import iconSearch from './assets/figma/icon-search.svg';
import radioOff from './assets/figma/radio-off.svg';
import radioOn from './assets/figma/radio-on.svg';
import checkboxOff from './assets/figma/checkbox-off.svg';
import checkboxOn from './assets/figma/checkbox-on.svg';

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

export function Page({
  children,
  back,
  tag,
  gap = 16,
}: {
  children: ReactNode;
  back?: string;
  tag?: string;
  gap?: 14 | 16;
}) {
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
          ) : tag ? (
            <span className="t-caption rounded-full bg-tile px-2.5 py-1 text-sub">{tag}</span>
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
      role="radio"
      aria-checked={selected}
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
    <button type="button" role="checkbox" aria-checked={checked} onClick={() => onChange(!checked)} className="flex w-full items-start gap-2.5 text-left">
      <img src={checked ? checkboxOn : checkboxOff} width={22} height={22} alt="" className="mt-0.5 shrink-0" />
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
  settled: '운행 끝',
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

// Settlement state of a folder batch or a 바로 정산 share (M5 요청 전, M6 정산 중, M6-1 정산 완료).
export function BatchPill({ status }: { status: BatchStatus }) {
  const [text, look] = {
    open: ['요청 전', 'bg-[#e8ebf2] text-[#6b7380]'],
    requested: ['정산 중', 'bg-[#fdefd9] text-[#d17300]'],
    done: ['정산 완료', 'bg-[#e3f7eb] text-[#148f4d]'],
  }[status];
  return <span className={`t-caption shrink-0 rounded-full px-2 py-0.5 ${look}`}>{text}</span>;
}

// "10월 14일 (화) 출근" rows of the folder screens.
export function AmountRow({ label, amount, strong }: { label: ReactNode; amount: number; strong?: boolean }) {
  return (
    <div className={`flex w-full items-center gap-2 ${strong ? 't-strong' : 't-body'}`}>
      <span className={`min-w-0 flex-1 ${strong ? '' : 'text-sub'}`}>{label}</span>
      <span className="shrink-0">{won(amount)}</span>
    </div>
  );
}

// "9,720 원" in the Number style.
export function Amount({ value }: { value: number }) {
  return (
    <div className="flex items-baseline gap-1">
      <span className="t-number">{Math.round(value).toLocaleString('ko-KR')}</span>
      <span className="t-headline text-sub">원</span>
    </div>
  );
}

// Newest rides first; the rest folds into "10월 1일 ~ 10월 8일 5회 더 보기".
export function RideList({ title, rides, shown }: { title: string; rides: { date: string; title: string; amount: number }[]; shown: number }) {
  const [all, setAll] = useState(false);
  const rest = rides.slice(shown);
  const visible = all ? rides : rides.slice(0, shown);
  return (
    <Card className="gap-2.5">
      <h2 className="t-strong">{title}</h2>
      {visible.map((r, i) => (
        <AmountRow key={i} label={`${dayLabel(r.date)} ${r.title}`} amount={r.amount} />
      ))}
      {!all && rest.length > 0 && (
        <button type="button" onClick={() => setAll(true)} className="t-caption text-left text-brand">
          {rest.length > 1
            ? `${dayLabel(rest[rest.length - 1].date).split(' (')[0]} ~ ${dayLabel(rest[0].date).split(' (')[0]} `
            : ''}
          {rest.length}회 더 보기
        </button>
      )}
    </Card>
  );
}

// "정산에 문제가 있어요" and, while this passenger's settlement is in progress, "계좌번호 복사".
export function PayActions({
  bank,
  driverName,
  context,
  onReport,
}: {
  bank: Bank | null;
  driverName: string;
  context: string;
  onReport: (kind: IssueKind, message: string) => Promise<unknown>;
}) {
  const [copied, setCopied] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [reported, setReported] = useState(false);

  async function copyAccount() {
    if (bank && (await copyText(bank.account))) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    }
  }

  if (reporting) {
    return (
      <div className="fixed inset-0 z-50 overflow-y-auto bg-page">
        <ReportIssue
          driverName={driverName}
          context={context}
          onCancel={() => setReporting(false)}
          onSend={async (kind, message) => {
            await onReport(kind, message);
            setReporting(false);
            setReported(true);
          }}
        />
      </div>
    );
  }

  return (
    <>
      <div className="flex w-full gap-2.5">
        <Button variant="secondary" onClick={() => setReporting(true)} disabled={reported} className="flex-1">
          {reported ? '운전자에게 알렸어요' : '정산에 문제가 있어요'}
        </Button>
        {bank && (
          <Button onClick={copyAccount} className="t-strong! flex-1">
            {copied ? '복사했어요' : '계좌번호 복사'}
          </Button>
        )}
      </div>
      {copied && bank && (
        <p className="t-caption text-center text-sub">
          {bank.bank_name} {bank.account} ({bank.holder})
        </p>
      )}
    </>
  );
}

// M3-1 정산에 문제가 있어요: what is wrong and an optional note; it arrives on the car screen (L1-8).
// There is no delete or leave choice here: the driver handles those on the car.
function ReportIssue({
  driverName,
  context,
  onCancel,
  onSend,
}: {
  driverName: string;
  context: string;
  onCancel: () => void;
  onSend: (kind: IssueKind, message: string) => Promise<void>;
}) {
  const [kind, setKind] = useState<IssueKind | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send() {
    if (!kind) return;
    setBusy(true);
    setError(null);
    try {
      await onSend(kind, message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page>
      <div className="flex flex-col gap-1">
        <p className="t-caption text-brand">{context}</p>
        <h1 className="t-title">어떤 문제가 있나요?</h1>
      </div>
      <Card className="gap-2.5">
        <div role="radiogroup" aria-label="문제 종류" className="flex flex-col gap-2.5">
          {issueKinds.map((k) => (
            <OptionCard key={k.kind} selected={kind === k.kind} onClick={() => setKind(k.kind)} title={k.label} />
          ))}
        </div>
      </Card>
      <Card className="gap-2.5">
        <label htmlFor="issue-message" className="t-strong">
          {driverName}님에게 남길 말 (선택)
        </label>
        <textarea
          id="issue-message"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          maxLength={500}
          rows={4}
          placeholder="예: 신촌역 3번 출구에서 탔어요"
          className="t-body w-full resize-none rounded-xl bg-tile px-3.5 py-3 outline-none placeholder:text-faint focus:ring-2 focus:ring-brand"
        />
      </Card>
      <p className="t-caption text-sub">
        {driverName}님 차 화면에 요청이 가고, {driverName}님이 고치면 금액이 다시 계산돼요
      </p>
      <ErrorText error={error} />
      <div className="flex-1" />
      <div className="flex w-full gap-2.5">
        <Button variant="secondary" onClick={onCancel} className="flex-1">
          취소
        </Button>
        <Button onClick={send} disabled={!kind || busy} className="t-strong! flex-1">
          요청 보내기
        </Button>
      </div>
    </Page>
  );
}

export const dayLabel = (iso: string) => {
  const d = new Date(iso);
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${'일월화수목금토'[d.getDay()]})`;
};

export const isToday = (iso: string) => new Date(iso).toDateString() === new Date().toDateString();

// Korean particle after a word: 은/는, 이/가, 을/를 depending on the last syllable's final consonant.
export function josa(word: string, withFinal: string, withoutFinal: string) {
  const c = word.charCodeAt(word.length - 1);
  const hasFinal = c >= 0xac00 && c <= 0xd7a3 ? (c - 0xac00) % 28 > 0 : false;
  return word + (hasFinal ? withFinal : withoutFinal);
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
