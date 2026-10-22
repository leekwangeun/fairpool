import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, rpc, type DriverView, type SettlePage as Settle } from '../api';
import { store } from '../store';
import DriverSend from './DriverSend';
import {
  Amount,
  AmountRow,
  BatchPill,
  Button,
  Card,
  DigitsInput,
  ErrorText,
  Field,
  Page,
  PayActions,
  RideList,
  TextInput,
  josa,
  usePolling,
  won,
} from '../ui';
import iconLock from '../assets/figma/icon-lock.svg';

// The one settlement page of a carpool folder batch, made when the driver presses 합쳐서 정산하기.
// Everyone gets the same link or QR; each phone sees only its own rides and sum (M6 정산 중, M6-1 정산 완료).
export default function SettlePage() {
  const { code = '' } = useParams();
  const deviceKey = store.deviceKey();
  const [page, setPage] = useState<Settle | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [driverView, setDriverView] = useState<DriverView | null>(null);

  async function load() {
    try {
      setPage(await rpc<Settle>('get_settle_page', { p_code: code, p_device_key: deviceKey }));
      setError(null);
    } catch (e) {
      // the driver cancelled the request while this page was open; other errors keep the last view
      if (e instanceof ApiError && e.code === 'page_not_found') setPage(null);
      setError((e as Error).message);
    }
  }
  usePolling(load);

  if (error && !page) {
    return (
      <Page>
        <Card>
          <p className="t-body">{error}</p>
        </Card>
      </Page>
    );
  }
  if (!page) return <Page>{null}</Page>;
  if (driverView) return <DriverSend view={driverView} />;
  if (!page.me) return <Verify page={page} deviceKey={deviceKey} onDone={setPage} onDriver={setDriverView} />;

  const me = page.me;
  const done = page.status === 'done' || me.paid;
  const amounts = me.rides.map((r) => r.amount);
  const sameEach = amounts.length > 1 && amounts.every((a) => a === amounts[0]);
  const first = me.rides[0];
  const sameCourse = first?.from && first.to && me.rides.every((r) => r.from === first.from && r.to === first.to);
  // the whole batch: "16회 총비용", "수진 (나)", "민호, 지은", "운전자 몫"
  const batch = page.batch;
  const total = batch?.total ?? me.rides.reduce((sum, r) => sum + r.total, 0);
  const driver = batch?.driver_amount ?? me.rides.reduce((sum, r) => sum + r.driver, 0);
  const others = batch?.others_amount ?? total - me.amount - driver;
  // each ride: who rode along, and each one's share when it was more than me
  const rides = me.rides.map((r) => ({
    ...r,
    title: r.people.map((x) => x.name).join(', ') || r.title,
    sub: r.people.length > 1 ? r.people.map((x) => `${x.name} ${won(x.amount)}`).join(' | ') : undefined,
  }));

  return (
    <Page gap={14}>
      <Card className="gap-1">
        <div className="flex items-center gap-2">
          <p className="t-caption text-brand">
            {done ? `${page.driver_name}님이 받았다고 확인했어요` : `${page.driver_name}님이 정산을 요청했어요`}
          </p>
          <BatchPill status={done ? 'done' : 'requested'} />
        </div>
        <p className="t-strong text-sub">
          {page.name} {batch ? `${batch.trips}회 중 ` : ''}
          {me.trips}회, {me.name} 분담액
        </p>
        <Amount value={me.amount} />
        <p className="t-caption text-sub">
          {sameEach ? `${me.trips}회 × ${won(amounts[0])}` : '운행마다 함께 탄 사람과 코스가 달라 금액도 달라요'}
          {sameCourse && ` | ${first.from} → ${first.to}${first.km != null ? ` ${Math.round(first.km)}km` : ''}`}
        </p>
      </Card>

      <Card className="gap-2.5">
        <AmountRow strong label={`${batch?.trips ?? me.trips}회 총비용`} amount={total} />
        <AmountRow label={`${me.name} (나)`} amount={me.amount} />
        {others > 0 && <AmountRow label={batch?.others.join(', ') || '함께 탄 다른 사람'} amount={others} />}
        {driver > 0 && <AmountRow label="운전자 몫" amount={driver} />}
      </Card>

      <RideList title="운행별 내역" rides={rides} shown={3} />

      {done ? (
        <section className="flex w-full flex-col gap-1 rounded-[20px] bg-[#e3f7eb] px-[18px] py-4">
          <p className="t-strong text-[#148f4d]">{josa(page.name, '은', '는')} 계속 이어져요</p>
          <p className="t-caption text-sub">다음 {page.next_title ?? '운행'}부터 다시 0원에서 쌓여요</p>
        </section>
      ) : (
        <PayActions
          bank={page.bank}
          driverName={page.driver_name}
          context={`${page.name} ${me.trips}회 | 내 분담액 ${won(me.amount)}`}
          onReport={(kind, message) =>
            rpc('report_settle_issue', { p_code: page.code, p_device_key: deviceKey, p_kind: kind, p_message: message })
          }
        />
      )}
      <p className="t-caption text-center text-faint">
        {done ? '정산이 끝나 계좌번호는 내려갔어요' : '내 은행 앱에서 보내 주세요. 페어풀은 송금하지 않아요'}
      </p>
    </Page>
  );
}

// Opened on a phone that did not ride: the same QR works for everyone after name + last 4 digits.
// The driver's own name and digits (saved on the car) open the read-only "내 휴대폰으로 보내기" view.
function Verify({
  page,
  deviceKey,
  onDone,
  onDriver,
}: {
  page: Settle;
  deviceKey: string;
  onDone: (p: Settle) => void;
  onDriver: (v: DriverView) => void;
}) {
  const [name, setName] = useState(store.myName());
  const [phone4, setPhone4] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function verify() {
    setError(null);
    try {
      const res = await rpc<Settle | { driver_view: DriverView }>('verify_settle_page', {
        p_code: page.code,
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
          <h1 className="t-title">{page.name} 정산 페이지예요</h1>
          <p className="t-body text-sub">
            {page.driver_name}님이 보낸 페이지예요. 이름과 휴대폰 번호 뒤 4자리로 확인하면 내 내역과 분담액만 보여요
          </p>
          <p className="t-caption text-faint">{page.driver_name}님이라면 차 화면에 넣은 이름과 번호 뒤 4자리로 보낼 정산을 볼 수 있어요</p>
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
    </Page>
  );
}
