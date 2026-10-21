import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { demoMode, rpc, ruleName, type AdminTrip, type Settlement } from '../api';
import { carpoolLink, store, tripLink } from '../store';
import { Button, Card, ErrorText, Page, QrImage, StatusPill, copyText, usePolling, won } from '../ui';

// Driver view of one trip: its QR and link, who joined and where they get on and off.
export default function DriverTrip() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const secret = store.adminSecret(code);
  const [trip, setTrip] = useState<AdminTrip | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState('');
  const link = tripLink(code.toUpperCase());

  async function load() {
    if (!secret) return;
    try {
      setTrip(await rpc<AdminTrip>('get_trip_admin', { p_code: code, p_admin_secret: secret }));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  usePolling(load);

  if (!secret) {
    return (
      <Page back="/">
        <Card className="gap-3">
          <p className="t-body">이 운행은 다른 기기에서 만들어졌어요.</p>
          <Link to={`/t/${code}`} className="t-strong text-brand">
            동승자 페이지로 열기
          </Link>
        </Card>
      </Page>
    );
  }

  async function copy(text: string, what: string) {
    if (await copyText(text)) {
      setCopied(what);
      window.setTimeout(() => setCopied(''), 1500);
    }
  }

  async function share() {
    const text = `${trip?.title ?? '페어풀'} 운행에 함께해요. 탈 곳과 내릴 곳을 골라 주세요.`;
    if (navigator.share) await navigator.share({ title: '페어풀', text, url: link }).catch(() => undefined);
    else copy(link, 'link');
  }

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const cancel = () =>
    window.confirm('운행을 취소할까요? 동승자 링크도 닫혀요.') &&
    run(() => rpc('set_trip_status', { p_code: code, p_admin_secret: secret, p_status: 'cancelled' })).then(() => navigate('/'));

  return (
    <Page back="/">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="t-title">{trip?.title ?? '운행'}</h1>
          {trip && (
            <p className="t-caption mt-1 text-sub">
              {trip.origin?.label ?? '출발지 미정'} 출발 | {trip.seats}자리 | {ruleName[trip.rule]}
            </p>
          )}
        </div>
        {trip && <StatusPill status={trip.status} />}
      </div>

      <Card className="items-center gap-4 text-center">
        <p className="t-caption text-sub">동승자가 찍으면 이 운행 페이지가 열려요</p>
        <QrImage text={link} />
        <div>
          <p className="t-caption text-sub">운행 코드</p>
          <p className="font-mono text-2xl font-bold tracking-[0.2em]">{code.toUpperCase()}</p>
        </div>
        <div className="flex w-full gap-2.5">
          <Button variant="secondary" onClick={() => copy(link, 'link')} className="flex-1">
            {copied === 'link' ? '복사했어요' : '링크 복사'}
          </Button>
          <Button onClick={share} className="t-strong! flex-1">
            링크 보내기
          </Button>
        </div>
      </Card>

      <Card className="gap-3">
        <h2 className="t-strong">함께 타는 사람 {trip ? `${trip.passengers.length}명` : ''}</h2>
        {trip?.passengers.length === 0 && <p className="t-caption text-sub">아직 참여한 사람이 없어요.</p>}
        {trip?.passengers.map((p) => (
          <div key={p.id} className="rounded-xl bg-page px-4 py-3">
            <p className="t-strong">
              {p.name}
              {p.boarded_at && <span className="t-caption ml-1.5 text-brand">{p.alighted_at ? '내림' : '탑승'}</span>}
            </p>
            <p className="t-caption mt-0.5 text-sub">
              {p.pickup?.label ?? '출발지에서 탑승'} → {p.dropoff?.label ?? '내릴 곳 미정'}
            </p>
          </div>
        ))}
      </Card>

      {trip && trip.issues.length > 0 && (
        <Card className="gap-2">
          <h2 className="t-strong">동승자가 남긴 문제</h2>
          {trip.issues.map((i, n) => (
            <p key={n} className="t-body">
              <span className="text-sub">{i.name ?? '동승자'}</span> {i.message}
            </p>
          ))}
        </Card>
      )}

      {trip?.settlement && (
        <Card className="t-body gap-2.5">
          <h2 className="t-strong">정산 결과</h2>
          {trip.settlement.shares.map((s, i) => (
            <div key={i} className="flex justify-between">
              <span className="text-sub">
                {s.name}
                {s.role === 'driver' && ' (운전자)'}
              </span>
              <span>{won(s.amount)}</span>
            </div>
          ))}
        </Card>
      )}

      {trip && trip.status !== 'cancelled' && (
        <div className="flex flex-col gap-2.5">
          {trip.carpool_code ? (
            <Card className="gap-2">
              <h2 className="t-strong">즐겨찾기에 추가됨</h2>
              <p className="t-caption text-sub">동승자가 이 링크를 홈 화면에 두면 다음 운행도 같은 곳에서 열려요.</p>
              <button type="button" onClick={() => copy(carpoolLink(trip.carpool_code!), 'carpool')} className="t-strong truncate text-left text-brand">
                {copied === 'carpool' ? '복사했어요' : carpoolLink(trip.carpool_code)}
              </button>
            </Card>
          ) : (
            store.driverSecret() && (
              <Button variant="secondary" onClick={() => run(() => rpc('save_carpool', { p_code: code, p_admin_secret: secret, p_driver_secret: store.driverSecret() }))}>
                즐겨찾기에 추가
              </Button>
            )
          )}
          {demoMode && trip.status !== 'settled' && <DemoTools trip={trip} secret={secret} run={run} />}
          {trip.status === 'preparing' && (
            <Button variant="danger" onClick={cancel}>
              운행 취소
            </Button>
          )}
        </div>
      )}
      <ErrorText error={error} />
    </Page>
  );
}

// Demo mode only: stand in for the car, which normally sends progress, boarding and the settlement.
function DemoTools({ trip, secret, run }: { trip: AdminTrip; secret: string; run: (fn: () => Promise<unknown>) => void }) {
  const ps = trip.passengers;
  const hhmm = (d: Date) => d.toTimeString().slice(0, 5);
  const allBoarded = ps.length > 0 && ps.every((p) => p.boarded_at);

  function sendProgress(boarded: boolean) {
    const stops = [
      { kind: 'start', label: trip.origin?.label ?? '출발지', time: hhmm(new Date(Date.now() - 30 * 60000)), state: 'done' },
      ...ps.filter((p) => p.pickup).map((p, i) => ({
        kind: 'pickup', passenger_id: p.id, label: p.pickup!.label, time: hhmm(new Date()),
        eta_min: 10 + i * 5, distance_km: 7 + i * 2, state: boarded ? 'done' : i === 0 ? 'now' : 'next',
      })),
      ...ps.map((p, i) => ({
        kind: 'dropoff', passenger_id: p.id, label: p.dropoff?.label, area: p.dropoff?.area ?? p.dropoff?.label,
        eta_min: 5 + i * 10, distance_km: 1.5 + i * 2.9, state: boarded && i === 0 ? 'now' : 'next',
      })),
      { kind: 'end', label: trip.destination?.label ?? '운전자 목적지', state: 'next' },
    ];
    const first = ps.find((p) => p.pickup?.lat)?.pickup;
    const car = first?.lat && first.lng ? { lat: first.lat + 0.02, lng: first.lng + 0.04 } : null;
    return run(async () => {
      await rpc('set_trip_status', { p_code: trip.code, p_admin_secret: secret, p_status: 'in_progress' });
      if (boarded) {
        for (const p of ps) await rpc('mark_passenger', { p_code: trip.code, p_admin_secret: secret, p_passenger_id: p.id, p_boarded: true });
      }
      await rpc('update_progress', {
        p_code: trip.code,
        p_admin_secret: secret,
        p_progress: { car, stops },
        p_estimates: ps.map((p, i) => ({ passenger_id: p.id, amount: 370 + i * 4000 })),
      });
    });
  }

  function settle() {
    const fuel = { distance_km: 80, efficiency_km_l: 11, amount: 12370 };
    const toll = 3000;
    const total = fuel.amount + toll;
    const people = trip.include_driver ? ps.length + 1 : ps.length;
    const each = Math.round(total / Math.max(people, 1) / 10) * 10;
    const payload: Settlement = {
      rule: trip.rule,
      include_driver: trip.include_driver,
      total,
      fuel,
      toll,
      shares: [
        ...ps.map((p) => ({
          passenger_id: p.id, name: p.name, role: 'passenger' as const, amount: each, km: 5.6,
          from: p.pickup?.area ?? p.pickup?.label ?? trip.origin?.label ?? '출발지', to: p.dropoff?.area ?? p.dropoff?.label ?? '',
        })),
        ...(trip.include_driver ? [{ passenger_id: null, name: '운전자', role: 'driver' as const, amount: total - each * ps.length }] : []),
      ],
    };
    return run(async () => {
      for (const p of ps) await rpc('mark_passenger', { p_code: trip.code, p_admin_secret: secret, p_passenger_id: p.id, p_alighted: true });
      await rpc('post_settlement', { p_code: trip.code, p_admin_secret: secret, p_payload: payload });
    });
  }

  return (
    <Card className="gap-2.5">
      <h2 className="t-strong">시연 도구</h2>
      <p className="t-caption text-sub">실제로는 차가 보내는 값이에요. 데모 모드에서만 보여요.</p>
      <Button variant="secondary" onClick={() => sendProgress(false)} disabled={ps.length === 0}>
        태우러 가는 중 보내기
      </Button>
      <Button variant="secondary" onClick={() => sendProgress(true)} disabled={ps.length === 0 || allBoarded}>
        모두 탑승, 함께 가는 중 보내기
      </Button>
      <Button variant="secondary" onClick={settle} disabled={ps.length === 0}>
        정산 결과 보내기
      </Button>
    </Card>
  );
}
