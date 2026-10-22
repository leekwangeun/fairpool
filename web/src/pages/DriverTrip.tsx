import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { demoMode, rpc, ruleName, type AdminTrip, type Settlement } from '../api';
import { carpoolLink, store, tripLink } from '../store';
import {
  BatchPill,
  Button,
  Card,
  Checkbox,
  ErrorText,
  Page,
  QrImage,
  StatusPill,
  TextInput,
  copyText,
  josa,
  usePolling,
  won,
} from '../ui';

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

  // Deleting is for the driver only. Before boarding the place leaves the stops (the car re-routes);
  // after boarding the record can only be corrected until the settlement is complete.
  function removePlace(id: string, name: string) {
    if (!window.confirm(`${name} 님의 장소를 지울까요? 들를 곳에서 빠지고, 즐겨찾기에 남긴 장소도 지워져요.`)) return;
    run(() => rpc('remove_passenger_place', { p_code: code, p_admin_secret: secret, p_passenger_id: id }));
  }

  function deleteRecord(id: string, name: string) {
    if (!window.confirm(`${name} 님의 탑승 기록을 지울까요? 탑승, 하차 위치가 지워지고, 정산 금액은 이름 없이 남아요.`)) return;
    run(() => rpc('delete_ride_record', { p_code: code, p_admin_secret: secret, p_passenger_id: id }));
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
        {trip?.passengers.map((p) => {
          const live = trip.status === 'preparing' || trip.status === 'in_progress';
          const canRemovePlace = live && !p.boarded_at && (p.pickup || p.dropoff);
          const canDeleteRecord = p.settle_done && !p.record_deleted_at;
          return (
            <div key={p.id} className="flex items-center gap-3 rounded-xl bg-page px-4 py-3">
              <div className="flex min-w-0 flex-1 flex-col">
                <p className="t-strong">
                  {p.name}
                  {p.boarded_at && <span className="t-caption ml-1.5 text-brand">{p.alighted_at ? '내림' : '탑승'}</span>}
                </p>
                <p className="t-caption mt-0.5 text-sub">
                  {p.record_deleted_at
                    ? '탑승 기록을 지웠어요. 정산 금액은 남아요'
                    : p.place_removed_at && !p.pickup && !p.dropoff
                      ? '장소를 지웠어요'
                      : `${p.pickup?.label ?? '출발지에서 탑승'} → ${p.dropoff?.label ?? '내릴 곳 미정'}`}
                </p>
              </div>
              {live && !p.boarded_at && (
                <button
                  type="button"
                  onClick={() => run(() => rpc('remove_passenger', { p_code: code, p_admin_secret: secret, p_passenger_id: p.id }))}
                  aria-label={`${p.name} 님을 운행에서 빼기`}
                  className="t-strong shrink-0 px-1 text-faint"
                >
                  ×
                </button>
              )}
              {canRemovePlace && (
                <button type="button" onClick={() => removePlace(p.id, p.name)} className="t-caption shrink-0 rounded-lg border border-line bg-card px-2.5 py-1.5 text-sub">
                  장소 지우기
                </button>
              )}
              {canDeleteRecord && (
                <button type="button" onClick={() => deleteRecord(p.id, p.name)} className="t-caption shrink-0 rounded-lg border border-line bg-card px-2.5 py-1.5 text-sub">
                  탑승 기록 지우기
                </button>
              )}
            </div>
          );
        })}
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

      {trip?.settlement && trip.status === 'settled' && <SettleSection trip={trip} secret={secret} run={run} />}

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
            // like the arrival screen (L6/P6): offered once the trip is settled, keeping who actually rode
            trip.status === 'settled' && (
              <Card className="gap-2">
                <p className="t-caption text-sub">
                  실제로 탄 사람과 장소, 나누는 방법이 그대로 저장돼요. 다음부터는 즐겨찾기를 누르면 같은 운행이 만들어져요.
                </p>
                <Button
                  variant="secondary"
                  onClick={() => run(() => rpc('save_carpool', { p_code: code, p_admin_secret: secret, p_driver_secret: store.driverSecret() }))}
                >
                  즐겨찾기에 추가
                </Button>
              </Card>
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

// L6 "이 운행 정산": settle now (바로 정산) or stack into a carpool folder, then follow it up.
function SettleSection({ trip, secret, run }: { trip: AdminTrip; secret: string; run: (fn: () => Promise<unknown>) => void }) {
  const s = trip.settlement!;
  const firstFolder = trip.suggested_folder ?? trip.folders[0]?.code ?? null;
  const [changing, setChanging] = useState(false);
  const [mode, setMode] = useState<'direct' | 'folder'>(trip.carpool_code || firstFolder ? 'folder' : 'direct');
  const [pick, setPick] = useState<string>(firstFolder ?? 'new');
  const [newName, setNewName] = useState(trip.carpool_code ? trip.title : '');
  const receivable = s.shares.filter((x) => x.role !== 'driver').reduce((sum, x) => sum + x.amount, 0);
  const paidAt = (id?: string | null) => trip.passengers.find((p) => p.id === id)?.paid_at ?? null;

  const choose = (m: 'direct' | 'folder') =>
    run(async () => {
      await rpc('choose_settlement', {
        p_code: trip.code,
        p_admin_secret: secret,
        p_mode: m,
        p_folder_code: m === 'folder' && pick !== 'new' ? pick : null,
        p_new_folder: m === 'folder' && pick === 'new' ? newName : null,
      });
      setChanging(false);
    });

  function togglePaid(id: string, name: string, amount: number, paid: boolean) {
    const ask = paid ? `${name} 님의 ${won(amount)} 정산을 완료로 바꿀까요?` : `${name} 님의 정산 완료를 취소할까요?`;
    if (!window.confirm(ask)) return;
    run(() => rpc('mark_paid', { p_code: trip.code, p_admin_secret: secret, p_passenger_id: id, p_paid: paid }));
  }

  const shares = (
    <>
      <div className="t-strong flex justify-between">
        <span>총비용</span>
        <span>{s.total != null ? won(s.total) : '-'}</span>
      </div>
      {trip.revisions.length > 0 && (
        <p className="t-caption text-sub">
          {trip.revisions.length}번 정정했어요 (이전 금액은 기록으로 남아요
          {trip.revisions[trip.revisions.length - 1].reason ? `, 마지막 사유: ${trip.revisions[trip.revisions.length - 1].reason}` : ''})
        </p>
      )}
      {s.shares.map((x, i) => {
        const paid = !!paidAt(x.passenger_id);
        return (
          <div key={i} className="flex items-center gap-2">
            <span className="t-body min-w-0 flex-1 text-sub">
              {x.name}
              {x.role === 'driver' && ' (운전자)'}
            </span>
            <span className="t-body">{won(x.amount)}</span>
            {trip.settle_mode === 'direct' && x.role !== 'driver' && x.passenger_id && (
              <span className="w-[104px] shrink-0">
                <Checkbox checked={paid} onChange={(v) => togglePaid(x.passenger_id!, x.name, x.amount, v)} title={<span className="t-caption text-sub">정산 완료</span>} />
              </span>
            )}
          </div>
        );
      })}
    </>
  );

  if (trip.settle_mode && !changing) {
    const locked = trip.folder != null && trip.folder.status !== 'open';
    const allPaid = trip.passengers.length > 0 && trip.passengers.every((p) => p.paid_at);
    return (
      <Card className="gap-2.5">
        <div className="flex items-center gap-2">
          <h2 className="t-strong flex-1">
            {trip.settle_mode === 'direct' ? '바로 정산' : `카풀 폴더 | ${trip.folder?.name ?? ''}`}
          </h2>
          <BatchPill status={trip.settle_mode === 'direct' ? (allPaid ? 'done' : 'requested') : (trip.folder?.status ?? 'open')} />
        </div>
        {shares}
        <p className="t-caption text-sub">
          {trip.settle_mode === 'direct'
            ? '정산 중인 동승자 페이지에 계좌번호가 보여요. 받으면 정산 완료로 바꿔 주세요. 바꾸면 그 사람 페이지에서 계좌번호가 내려가요'
            : '동승자 페이지에는 쌓인 금액만 보여요. 합쳐서 정산하면 그때 계좌번호가 보여요'}
        </p>
        <div className="flex gap-2.5">
          {!locked && (
            <Button variant="secondary" onClick={() => setChanging(true)} className="flex-1">
              {trip.settle_mode === 'direct' ? '폴더에 쌓기로 바꾸기' : '정산 방법 바꾸기'}
            </Button>
          )}
          {trip.folder && store.driverSecret() && (
            <Link to={`/f/${trip.folder.code}`} className="t-strong flex h-14 flex-1 items-center justify-center rounded-[14px] bg-brand px-2 text-white">
              폴더 보기
            </Link>
          )}
        </div>
      </Card>
    );
  }

  const picked = trip.folders.find((f) => f.code === pick);
  const pickedName = pick === 'new' ? newName.trim() : picked?.name ?? '';
  const already = trip.folder?.code === pick;
  return (
    <Card className="gap-3">
      {shares}
      <div className="flex items-center gap-2 pt-1">
        <h2 className="t-strong flex-1">이 운행 정산</h2>
        <div className="flex rounded-full bg-tile p-1">
          {(['direct', 'folder'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              className={`t-caption rounded-full px-3 py-1.5 ${mode === m ? 'bg-card text-brand shadow-card' : 'text-sub'}`}
            >
              {m === 'direct' ? '바로 정산' : '폴더에 쌓기'}
            </button>
          ))}
        </div>
      </div>
      {mode === 'folder' && (
        <div className="flex flex-wrap gap-2">
          {trip.folders.map((f) => (
            <button
              key={f.code}
              type="button"
              onClick={() => setPick(f.code)}
              className={`t-caption rounded-full px-3 py-1.5 ${pick === f.code ? 'border border-brand bg-brand-soft text-brand' : 'bg-tile text-sub'}`}
            >
              {f.name}
            </button>
          ))}
          <button
            type="button"
            onClick={() => setPick('new')}
            className={`t-caption rounded-full border border-dashed px-3 py-1.5 ${pick === 'new' ? 'border-brand bg-brand-soft text-brand' : 'border-faint text-sub'}`}
          >
            + 새 카풀 폴더
          </button>
        </div>
      )}
      {mode === 'folder' && pick === 'new' && <TextInput value={newName} onChange={setNewName} placeholder="폴더 이름 (예: 출근 카풀)" />}
      <p className="t-caption text-sub">
        {mode === 'direct'
          ? '이 운행만 따로 정산해요. 동승자 페이지에 내 몫과 계좌번호가 보여요'
          : pick === 'new'
            ? `${pickedName ? josa(pickedName, '을', '를') : '새 폴더를'} 만들어 이 운행부터 쌓아요. 동승자 페이지에는 쌓인 금액만 보여요`
            : `${pickedName}에 쌓으면 ${(picked?.trips ?? 0) + (already ? 0 : 1)}회 ${won((picked?.amount ?? 0) + (already ? 0 : receivable))}이 돼요. 동승자 페이지에는 쌓인 금액만 보여요`}
      </p>
      <div className="flex gap-2.5">
        {changing && (
          <Button variant="secondary" onClick={() => setChanging(false)} className="flex-1">
            취소
          </Button>
        )}
        <Button onClick={() => choose(mode)} disabled={mode === 'folder' && !pickedName} className="t-strong! flex-1">
          {mode === 'direct' ? '바로 정산하기' : `${pickedName || '폴더'}에 쌓기`}
        </Button>
      </div>
    </Card>
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

  // the account is entered only on the car (L1-9): link a stand-in car to this driver and save a sample
  async function addBankFromCar() {
    const car = await rpc<{ vehicle_secret: string; link_code: string }>('create_vehicle_link', {});
    await rpc('claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: store.driverSecret() });
    await rpc('set_bank_account', {
      p_vehicle_secret: car.vehicle_secret,
      p_consent: true,
      p_bank_name: '카카오뱅크',
      p_bank_account: '3333-01-2345678',
      p_account_holder: '운전자',
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
      {store.driverSecret() && (
        <Button variant="secondary" onClick={() => run(addBankFromCar)}>
          차 화면에서 받을 계좌 넣기
        </Button>
      )}
    </Card>
  );
}
