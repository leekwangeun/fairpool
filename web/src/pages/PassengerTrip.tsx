import { useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { rpc, ruleName, type DriverView, type Place, type PublicTrip, type Stop } from '../api';
import { store } from '../store';
import DriverSend from './DriverSend';
import {
  Amount,
  BatchPill,
  Button,
  Card,
  Chip,
  dayLabel,
  isToday,
  josa,
  DigitsInput,
  ErrorText,
  Field,
  OptionCard,
  Page,
  PayActions,
  PlaceSearch,
  RideList,
  TextInput,
  usePolling,
  won,
} from '../ui';
import { CourseMap, hasPoint } from '../map';
import iconInfo from '../assets/figma/icon-info.svg';
import iconLock from '../assets/figma/icon-lock.svg';
import iconLocationOff from '../assets/figma/icon-location-off.svg';
import dotDone from '../assets/figma/dot-done.svg';
import dotNow from '../assets/figma/dot-now.svg';
import dotNext from '../assets/figma/dot-next.svg';

// The page a passenger opens from the QR on the car screen or the link the driver sent.
// M1 join, M1-1 another phone, M2 waiting for pickup, M2-1 riding, M3 settlement (바로 정산),
// M5 a trip stacked into a carpool folder (요청 전; the settlement page is SettlePage).
export default function PassengerTrip() {
  const { code = '' } = useParams();
  const deviceKey = store.deviceKey();
  const [trip, setTrip] = useState<PublicTrip | null>(null);
  const [notFound, setNotFound] = useState<string | null>(null);
  const [mode, setMode] = useState<'view' | 'edit' | 'verify'>('view');
  const [driverView, setDriverView] = useState<DriverView | null>(null);

  async function load() {
    try {
      setTrip(await rpc<PublicTrip>('get_trip', { p_code: code, p_device_key: deviceKey }));
    } catch (e) {
      setNotFound((e as Error).message);
    }
  }
  usePolling(load);

  if (notFound) {
    return (
      <Page>
        <Card>
          <p className="t-body">{notFound}</p>
        </Card>
      </Page>
    );
  }
  if (!trip) return <Page>{null}</Page>;

  if (trip.status === 'cancelled') {
    return (
      <Page>
        <Card className="gap-1">
          <p className="t-caption text-brand">{trip.title}</p>
          <p className="t-body">{trip.driver_name}님이 이 운행을 취소했어요.</p>
        </Card>
      </Page>
    );
  }

  const open = trip.status === 'preparing' || trip.status === 'in_progress';
  const done = (t: PublicTrip) => {
    setTrip(t);
    setMode('view');
  };

  if (driverView) return <DriverSend view={driverView} />;
  if (!trip.me) {
    if (mode === 'verify' || !open) {
      return <Verify trip={trip} deviceKey={deviceKey} onDone={done} onDriver={setDriverView} onBack={open ? () => setMode('view') : undefined} />;
    }
    return <Join trip={trip} deviceKey={deviceKey} onDone={done} onVerify={() => setMode('verify')} />;
  }
  if (mode === 'edit' && open) return <Join trip={trip} deviceKey={deviceKey} onDone={done} onCancel={() => setMode('view')} />;
  if (trip.status === 'settled') {
    return trip.folder ? (
      <FolderView trip={trip} />
    ) : (
      <Settled trip={trip} deviceKey={deviceKey} />
    );
  }
  return (
    <Riding trip={trip} onEdit={trip.status === 'preparing' && !trip.me.boarded ? () => setMode('edit') : undefined} />
  );
}

// ------------------------------------------------------------------ M1 참여

function Join({
  trip,
  deviceKey,
  onDone,
  onVerify,
  onCancel,
}: {
  trip: PublicTrip;
  deviceKey: string;
  onDone: (t: PublicTrip) => void;
  onVerify?: () => void;
  onCancel?: () => void;
}) {
  const editing = !!trip.me;
  const [name, setName] = useState(trip.me?.name ?? store.myName());
  const [phone4, setPhone4] = useState('');
  const [onTheWay, setOnTheWay] = useState(!!trip.me?.pickup);
  const [pickup, setPickup] = useState<Place | null>(trip.me?.pickup ?? null);
  const [dropoff, setDropoff] = useState<Place | null>(trip.me?.dropoff ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // stops must come from the search list so they carry coordinates for the car's navigation
  const ready = name.trim() && (editing || phone4.length === 4) && hasPoint(dropoff) && (!onTheWay || hasPoint(pickup));

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const t = await rpc<PublicTrip>('join_trip', {
        p_code: trip.code,
        p_device_key: deviceKey,
        p_name: name,
        p_pickup: onTheWay ? pickup : null,
        p_dropoff: dropoff,
        p_phone4: phone4.length === 4 ? phone4 : null,
      });
      store.setMyName(name.trim());
      onDone(t);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page>
      <Card className="gap-1.5">
        <p className="t-caption text-brand">{trip.driver_name}님이 카풀에 초대했어요</p>
        <h1 className="t-title">{trip.title}</h1>
        <div className="flex flex-wrap items-center gap-2">
          <Chip>
            자리 {trip.seats}개 중 {trip.seats_left}개 남음
          </Chip>
          <Chip>{ruleName[trip.rule]} 정산</Chip>
          <Link to={`/t/${trip.code}/how`} aria-label="계산 방법 보기">
            <img src={iconInfo} width={22} height={22} alt="" />
          </Link>
        </div>
      </Card>

      <Card className="gap-[18px]">
        <Field label="이름">
          <TextInput value={name} onChange={setName} placeholder="운전자가 알아볼 이름" />
        </Field>
        {!editing && (
          <Field label="휴대폰 번호 뒤 4자리" hint="다른 기기에서 이 페이지를 열 때 확인용이에요. 번호 전체는 받지 않아요">
            <DigitsInput value={phone4} onChange={setPhone4} />
          </Field>
        )}
        <Field label="탈 곳">
          <div className="flex flex-col gap-2">
            <OptionCard
              selected={!onTheWay}
              onClick={() => setOnTheWay(false)}
              title="출발지에서 같이 타요"
              sub={trip.origin ?? '운전자와 같은 곳에서 출발해요'}
            />
            <OptionCard selected={onTheWay} onClick={() => setOnTheWay(true)} title="가는 길에 태워 주세요" sub="장소를 검색해 정해요" />
          </div>
          {onTheWay && <PlaceSearch value={pickup} onChange={setPickup} placeholder="어디서 탈까요?" />}
        </Field>
        <Field label="내릴 곳">
          <PlaceSearch value={dropoff} onChange={setDropoff} placeholder="어디서 내릴까요?" />
        </Field>
        <p className="t-caption text-faint">
          {trip.carpool_code
            ? '정확한 위치는 운행이 끝나면 지워요. 즐겨찾기에 저장되는 카풀이라 다음 운행을 위해 탈 곳과 내릴 곳이 저장되고, 지우고 싶으면 운전자에게 말해 주세요'
            : '정확한 위치는 운행이 끝나면 지워요. 운전자 차의 운행 기록에는 동 이름만 남아요'}
        </p>
      </Card>

      {!editing && <p className="t-caption text-center text-sub">참여하면 픽업 시간과 정산 금액을 여기서 볼 수 있어요</p>}
      <ErrorText error={error} />
      <Button onClick={submit} disabled={busy || !ready}>
        {editing ? '저장하기' : '참여하기'}
      </Button>

      {onVerify && (
        <>
          <button type="button" onClick={onVerify} className="t-caption text-sub underline underline-offset-2">
            다른 휴대폰에서 이미 참여했나요?
          </button>
          <button type="button" onClick={onVerify} className="t-caption text-sub underline underline-offset-2">
            {trip.driver_name}님이라면 여기서 초대를 공유할 수 있어요
          </button>
        </>
      )}
      {editing && (
        <>
          <Button variant="secondary" onClick={onCancel}>
            취소
          </Button>
          <p className="t-caption text-center text-faint">이 운행에서 빠지려면 {trip.driver_name}님에게 말해 주세요</p>
        </>
      )}
    </Page>
  );
}

// ------------------------------------------------------------------ M1-1 다른 기기 확인

// A passenger's name and digits re-open their page here; the driver's own (saved on the car) open
// the read-only "내 휴대폰으로 보내기" view instead.
function Verify({
  trip,
  deviceKey,
  onDone,
  onDriver,
  onBack,
}: {
  trip: PublicTrip;
  deviceKey: string;
  onDone: (t: PublicTrip) => void;
  onDriver: (v: DriverView) => void;
  onBack?: () => void;
}) {
  const [name, setName] = useState(store.myName());
  const [phone4, setPhone4] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function verify() {
    setError(null);
    try {
      const res = await rpc<PublicTrip | { driver_view: DriverView }>('verify_device', {
        p_code: trip.code,
        p_device_key: deviceKey,
        p_name: name,
        p_phone4: phone4,
      });
      if ('driver_view' in res) return onDriver(res.driver_view);
      onDone(res);
      store.setMyName(name.trim());
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Page>
      <Card className="gap-[18px]">
        <img src={iconLock} width={48} height={48} alt="" />
        <div className="flex flex-col gap-1">
          <h1 className="t-title">이 운행에 참여한 사람인가요?</h1>
          <p className="t-body text-sub">처음 참여한 휴대폰이 아니라서 한 번 확인할게요</p>
          <p className="t-caption text-faint">{trip.driver_name}님이라면 차 화면에 넣은 이름과 번호 뒤 4자리로 보낼 초대와 정산을 볼 수 있어요</p>
        </div>
        <Field label="이름">
          <TextInput value={name} onChange={setName} />
        </Field>
        <Field label="휴대폰 번호 뒤 4자리">
          <DigitsInput value={phone4} onChange={setPhone4} />
        </Field>
        <ErrorText error={error} />
      </Card>
      <div className="flex-1" />
      <Button onClick={verify} disabled={!name.trim() || phone4.length !== 4}>
        확인
      </Button>
      {onBack && (
        <button type="button" onClick={onBack} className="t-caption text-sub underline underline-offset-2">
          처음이에요, 새로 참여할게요
        </button>
      )}
    </Page>
  );
}

// ------------------------------------------------------------------ M2 탑승 전, M2-1 함께 가는 중

const dotFor = { done: dotDone, now: dotNow, next: dotNext };

function stopText(s: Stop, origin: string | null): { title: string; sub?: string } {
  const eta = s.eta_min != null ? `${s.eta_min}분 뒤` : undefined;
  const join = (...parts: (string | undefined)[]) => parts.filter(Boolean).join(' | ') || undefined;
  switch (s.kind) {
    case 'start':
      return { title: `출발  ${s.label ?? origin ?? '출발지'}`, sub: s.time ? `${s.time} 출발` : undefined };
    case 'my_pickup':
      return { title: '나 태우기', sub: s.state === 'done' ? join(s.time && `${s.time} 탑승`, s.label) : join(eta, s.label) };
    case 'my_dropoff':
      return { title: '나 내리기', sub: s.state === 'done' ? join(s.time && `${s.time} 하차`, s.label) : join(eta, s.label) };
    case 'other_pickup':
      return { title: '동승자 1명 탐', sub: s.state === 'now' ? eta : undefined };
    case 'other_dropoff':
      return { title: '동승자 1명 내림', sub: s.state === 'now' ? eta : undefined };
  }
}

function Riding({ trip, onEdit }: { trip: PublicTrip; onEdit?: () => void }) {
  const me = trip.me!;
  const stops: Stop[] = trip.progress?.stops ?? [
    { kind: 'start', label: trip.origin ?? undefined, state: 'next' },
    ...(me.pickup ? [{ kind: 'my_pickup' as const, label: me.pickup.label, state: 'next' as const }] : []),
    { kind: 'my_dropoff', label: me.dropoff?.area ?? me.dropoff?.label, state: 'next' },
  ];
  const myPickup = stops.find((s) => s.kind === 'my_pickup');
  const myDrop = stops.find((s) => s.kind === 'my_dropoff');
  const target = me.boarded ? myDrop : myPickup ?? stops.find((s) => s.kind === 'start');
  const car = trip.progress?.car ?? null;

  // "다음은 동승자 1명이 내리고, 그다음이 나예요"
  const ahead = stops.slice(stops.findIndex((s) => s.state !== 'done'), stops.indexOf(myDrop ?? stops[stops.length - 1]));
  const summary =
    ahead.length === 0
      ? '다음에 내리는 사람이 나예요'
      : ahead.every((s) => s.kind === 'other_dropoff')
        ? `다음은 동승자 ${ahead.length}명이 내리고, 그다음이 나예요`
        : `나보다 먼저 ${ahead.length}곳을 들러요`;

  return (
    <Page gap={14}>
      <Card className="gap-1">
        <p className="t-caption text-brand">
          {me.boarded ? '함께 가는 중' : trip.status === 'in_progress' ? `${trip.driver_name}님 차가 태우러 오는 중` : `${trip.driver_name}님이 출발하면 시간이 떠요`}
        </p>
        {target?.eta_min != null ? (
          <div className="flex items-baseline gap-1.5">
            <span className="t-number">{target.eta_min}</span>
            <span className="t-headline">{me.boarded ? '분 뒤 내려요' : '분 뒤 도착'}</span>
            {target.distance_km != null && <span className="t-body text-sub">{target.distance_km.toFixed(1)}km</span>}
          </div>
        ) : (
          <h1 className="t-title">{trip.title}</h1>
        )}
        <p className="t-body text-sub">
          {me.boarded ? summary : me.pickup ? `${me.pickup.label}에서 기다려 주세요` : `${trip.origin ?? '출발지'}에서 같이 타요`}
        </p>
      </Card>

      {me.boarded ? (
        <div className="flex w-full items-center gap-2.5 rounded-2xl bg-tile px-4 py-3.5">
          <img src={iconLocationOff} width={22} height={22} alt="" className="shrink-0" />
          <p className="t-caption text-sub">
            탑승해서 차 위치 공유가 꺼졌어요.
            <br />
            남은 시간과 순서만 보여요
          </p>
        </div>
      ) : (
        (hasPoint(car) || hasPoint(me.pickup) || hasPoint(me.dropoff)) && (
          <CourseMap
            height={200}
            car={hasPoint(car) ? car : null}
            pickup={hasPoint(me.pickup) ? me.pickup : null}
            dropoff={hasPoint(me.dropoff) ? me.dropoff : null}
            carLabel={
              hasPoint(car) && target?.distance_km != null
                ? `${trip.driver_name}님 차 | ${me.pickup?.label ?? '출발지'}까지 ${target.distance_km.toFixed(1)}km`
                : undefined
            }
          />
        )
      )}

      {me.estimate != null && (
        <Card className="gap-1">
          <div className="flex items-center gap-2">
            <span className="t-strong flex-1">예상 내 몫</span>
            <span className="t-title text-brand">약 {won(me.estimate)}</span>
          </div>
          <p className="t-caption text-sub">지금 코스 기준이에요. 운전자가 운행을 마치면 확정돼요</p>
        </Card>
      )}

      <Card>
        <h2 className="t-strong">코스</h2>
        {stops.map((s, i) => {
          const { title, sub } = stopText(s, trip.origin);
          return (
            <div key={i} className="flex items-start gap-3 py-[9px]">
              <img src={dotFor[s.state]} width={22} height={22} alt="" className="shrink-0" />
              <div className={`flex min-w-0 flex-1 flex-col ${s.state === 'done' ? 'text-sub' : ''}`}>
                <span className={`t-strong whitespace-pre ${s.state === 'now' ? 'text-brand' : ''}`}>{title}</span>
                {sub && <span className={`t-caption ${s.state === 'done' ? '' : 'text-sub'}`}>{sub}</span>}
              </div>
            </div>
          );
        })}
        <p className="t-caption text-faint">
          {me.boarded ? '운행이 끝나면 이 화면은 정산 결과로 바뀌어요' : '다른 동승자가 내리는 곳과 운전자의 목적지는 보이지 않아요'}
        </p>
      </Card>

      {onEdit && (
        <button type="button" onClick={onEdit} className="t-caption text-sub underline underline-offset-2">
          탈 곳, 내릴 곳 바꾸기
        </button>
      )}
      {me.place_removed && (
        <p className="t-caption text-center text-sub">{trip.driver_name}님이 내 장소를 지웠어요</p>
      )}
    </Page>
  );
}

// ------------------------------------------------------------------ M3 정산

const ruleText = {
  shapley: '함께 타서 아낀 돈은 모두 나누고, 돌아간 거리는 만든 사람이 더 냈어요.',
  distance: '총비용을 각자 함께 탄 거리만큼 나눴어요.',
  equal: '총비용을 똑같이 나눴어요.',
};

function Settled({ trip, deviceKey }: { trip: PublicTrip; deviceKey: string }) {
  const s = trip.settlement!;
  const me = trip.me!;
  const mine = s.shares.find((x) => x.is_me);
  const rule = s.rule ?? trip.rule;
  const withDriver = s.include_driver ?? trip.include_driver;

  return (
    <Page gap={14}>
      <Card className="gap-1">
        <div className="flex items-center gap-2">
          <p className="t-caption text-brand">운행이 끝났어요 | {trip.title}</p>
          {trip.settle_mode === 'direct' && <BatchPill status={trip.paid ? 'done' : 'requested'} />}
        </div>
        <p className="t-strong text-sub">내 몫</p>
        <div className="flex items-baseline gap-1">
          <span className="t-number">{mine ? Math.round(mine.amount).toLocaleString('ko-KR') : '-'}</span>
          <span className="t-headline text-sub">원</span>
        </div>
        {mine?.from && mine.to && (
          <p className="t-caption text-sub">
            {mine.from} → {mine.to}
            {mine.km != null && ` | ${mine.km.toFixed(1)}km 함께 탔어요`}
          </p>
        )}
        <div className="mt-1 flex flex-col gap-0.5 rounded-xl bg-brand-soft p-3.5">
          <div className="flex items-center gap-1.5">
            <span className="t-strong text-brand">
              {ruleName[rule]}, 운전자 {withDriver ? '포함' : '제외'}으로 나눴어요
            </span>
            <Link to={`/t/${trip.code}/how`} aria-label="계산 방법 보기" className="shrink-0">
              <img src={iconInfo} width={22} height={22} alt="" />
            </Link>
          </div>
          <p className="t-caption text-brand">
            {ruleText[rule]}
            <br />
            {withDriver ? '운전자도 한 사람 몫으로 포함했어요' : '운전자 몫은 빼고 동승자끼리 나눴어요'}
          </p>
        </div>
      </Card>

      {s.total != null && (
        <Card className="t-body gap-2.5">
          <div className="t-strong flex gap-2">
            <span className="flex-1">총비용</span>
            <span>{won(s.total)}</span>
          </div>
          {s.fuel && (
            <div className="flex gap-2">
              <span className="flex-1 text-sub">
                기름값 ({s.fuel.distance_km.toFixed(1)}km, {s.fuel.efficiency_km_l.toFixed(1)}km/L)
              </span>
              <span>{won(s.fuel.amount)}</span>
            </div>
          )}
          {s.toll != null && (
            <div className="flex gap-2">
              <span className="flex-1 text-sub">통행료</span>
              <span>{won(s.toll)}</span>
            </div>
          )}
        </Card>
      )}

      <Card className="t-body gap-2.5">
        <h2 className="t-strong">모두의 몫</h2>
        {s.shares.map((x, i) =>
          x.is_me ? (
            <div key={i} className="t-strong flex gap-2 rounded-[10px] bg-brand-soft px-2.5 py-2">
              <span className="flex-1">{x.name} (나)</span>
              <span>{won(x.amount)}</span>
            </div>
          ) : (
            <div key={i} className="flex gap-2">
              <span className="flex-1 text-sub">
                {x.name}
                {x.role === 'driver' && x.name !== '운전자' && ' (운전자)'}
              </span>
              <span>{won(x.amount)}</span>
            </div>
          ),
        )}
      </Card>

      <SavedPlace trip={trip} />

      {(hasPoint(me.pickup) || hasPoint(me.dropoff)) && (
        <CourseMap height={170} pickup={hasPoint(me.pickup) ? me.pickup : null} dropoff={hasPoint(me.dropoff) ? me.dropoff : null} />
      )}

      <PayActions
        bank={trip.bank}
        onReport={(message) => rpc('report_issue', { p_code: trip.code, p_device_key: deviceKey, p_message: message })}
      />
      {trip.corrections > 0 && (
        <p className="t-caption text-center text-sub">운전자가 이 정산을 {trip.corrections}번 정정했어요. 지금 금액이 정정한 금액이에요</p>
      )}
      <p className="t-caption text-center text-faint">
        {trip.paid
          ? '정산이 끝나 계좌번호는 내려갔어요'
          : trip.bank
            ? '내 은행 앱에서 보내 주세요. 페어풀은 송금하지 않아요'
            : '송금은 쓰시는 앱에서 해 주세요. 정확한 위치는 이미 지웠어요'}
      </p>
    </Page>
  );
}

// The place kept for the favourite's next trip. Only the driver deletes it (favourites x); a passenger
// who wants it gone asks the driver outside the app.
function SavedPlace({ trip }: { trip: PublicTrip }) {
  const me = trip.me!;
  const places = [me.pickup?.label, me.dropoff?.label].filter(Boolean).join(', ');
  if (!trip.carpool_code || !me.save_place || !places) return null;
  return (
    <section className="flex w-full flex-col items-start gap-1 rounded-[20px] bg-brand-soft px-[18px] py-4">
      <p className="t-strong text-brand">{trip.driver_name}님 차에 내 장소가 저장됐어요</p>
      <p className="t-caption text-sub">
        다음에 이 카풀로 운행하면 {josa(places, '이', '가')} 바로 채워져요. 지우고 싶으면 {trip.driver_name}님에게 말해 주세요
      </p>
    </section>
  );
}

// ------------------------------------------------------------------ M5 카풀 폴더 (요청 전)

// Folder trips have no settlement page of their own. Until the driver presses 합쳐서 정산하기 the page
// shows the running sum; after that it goes to the one settlement page of the batch (/s/<code>).
function FolderView({ trip }: { trip: PublicTrip }) {
  const f = trip.folder!;
  if (f.page) return <Navigate to={`/s/${f.page}`} replace />;
  const amounts = f.rides.map((r) => r.amount);
  const sameEach = amounts.length > 1 && amounts.every((a) => a === amounts[0]);
  const today = f.rides.find((r) => r.is_this);
  return (
    <Page gap={14}>
      <Card className="gap-1">
        <div className="flex items-center gap-2">
          <p className="t-caption text-brand">카풀 폴더 | {f.name}</p>
          <BatchPill status="open" />
        </div>
        <p className="t-strong text-sub">
          {f.name}: 지금까지 {f.rides.length}회
        </p>
        <Amount value={f.my_total} />
        {today && (
          <p className="t-caption text-sub">
            {isToday(today.date) ? '오늘' : dayLabel(today.date)} {today.title} {won(today.amount)}이 더해졌어요
          </p>
        )}
        <div className="w-full rounded-xl bg-brand-soft px-3.5 py-3">
          <p className="t-caption text-brand">
            {trip.driver_name}님이 합쳐서 정산하면 그때 한 번만 보내면 돼요. 지금은 보낼 필요 없어요
          </p>
        </div>
      </Card>
      <RideList title="쌓인 운행" rides={f.rides} shown={4} />
      {sameEach && (
        <Card className="gap-1">
          <h2 className="t-strong">1회 분담액이 매번 같은 이유</h2>
          <p className="t-caption text-sub">같은 길을 같은 사람과 다녀서 계산 결과가 같아요. 길이 달라지면 그날 금액도 달라져요</p>
        </Card>
      )}
      <SavedPlace trip={trip} />
    </Page>
  );
}
