import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { rpc, ruleName, type Place, type Rule } from '../api';
import { demoCarSecret, store } from '../store';
import { Button, Card, CarStandIn, Checkbox, ErrorText, Field, OptionCard, Page, PlaceSearch, TextInput } from '../ui';

// Every trip gets its own code, its own passenger page (/t/CODE) and its own QR.
// Demo mode only: stands in for the car's L1 screen and makes the trip with the stand-in car's secret.
export default function NewTrip() {
  const navigate = useNavigate();
  const [title, setTitle] = useState('');
  const [origin, setOrigin] = useState<Place | null>(null);
  const [destination, setDestination] = useState<Place | null>(null);
  const [seats, setSeats] = useState(4);
  const [rule, setRule] = useState<Rule>('shapley');
  const [includeDriver, setIncludeDriver] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const r = await rpc<{ code: string; admin_secret: string }>('create_trip', {
        p_title: title,
        p_origin: origin,
        p_destination: destination,
        p_include_driver: includeDriver,
        p_seats: seats,
        p_rule: rule,
        p_vehicle_secret: await demoCarSecret(),
      });
      store.setAdminSecret(r.code, r.admin_secret);
      navigate(`/d/${r.code}`, { replace: true });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <Page back="/">
      <CarStandIn />
      <h1 className="t-title">새 운행 만들기</h1>
      <Card className="gap-[18px]">
        <Field label="운행 이름">
          <TextInput value={title} onChange={setTitle} placeholder="예: MT 귀갓길" />
        </Field>
        <Field label="출발지" hint="동승자에게 보여요">
          <PlaceSearch value={origin} onChange={setOrigin} placeholder="어디서 출발해요?" />
        </Field>
        <Field label="내 목적지" hint="동승자에게는 보이지 않아요">
          <PlaceSearch value={destination} onChange={setDestination} placeholder="마지막에 어디로 가요?" />
        </Field>
        <Field label="태울 수 있는 자리">
          <div className="flex gap-2.5">
            {[1, 2, 3, 4].map((n) => (
              <button
                key={n}
                type="button"
                onClick={() => setSeats(n)}
                className={`t-strong h-12 flex-1 rounded-xl ${seats === n ? 'border-2 border-brand bg-brand-soft text-brand' : 'bg-tile'}`}
              >
                {n}자리
              </button>
            ))}
          </div>
        </Field>
        <Field label="나누는 방법">
          <div className="flex flex-col gap-2">
            <OptionCard selected={rule === 'shapley'} onClick={() => setRule('shapley')} title={ruleName.shapley} sub="돌아간 거리는 만든 사람이, 아낀 돈은 모두 나눠요" />
            <OptionCard selected={rule === 'distance'} onClick={() => setRule('distance')} title={ruleName.distance} sub="각자 함께 탄 거리만큼 나눠요" />
            <OptionCard selected={rule === 'equal'} onClick={() => setRule('equal')} title={ruleName.equal} sub="모두 똑같이 나눠요" />
          </div>
        </Field>
        <Checkbox checked={includeDriver} onChange={setIncludeDriver} title="운전자 몫도 함께 나누기" sub="끄면 운전자 몫을 빼고 동승자끼리만 나눠요" />
      </Card>
      <ErrorText error={error} />
      <Button onClick={create} disabled={busy}>
        {busy ? '만드는 중…' : '운행 만들고 QR 받기'}
      </Button>
    </Page>
  );
}
