import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { rpc, type Driver } from '../api';
import { store } from '../store';
import { Button, Card, ErrorText, Field, Page, StatusPill, TextInput } from '../ui';

// Driver home: start a new trip, re-create a saved carpool, open recent trips.
export default function Home() {
  const navigate = useNavigate();
  const [driver, setDriver] = useState<Driver | null>(null);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const secret = store.driverSecret();
    if (!secret) {
      setLoading(false);
      return;
    }
    rpc<Driver>('get_driver', { p_driver_secret: secret })
      .then(setDriver)
      .catch(() => store.setDriverSecret(null))
      .finally(() => setLoading(false));
  }, []);

  async function start() {
    setError(null);
    try {
      const r = await rpc<{ driver_secret: string }>('create_driver', { p_display_name: name });
      store.setDriverSecret(r.driver_secret);
      setDriver(await rpc<Driver>('get_driver', { p_driver_secret: r.driver_secret }));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function again(carpoolCode: string) {
    setError(null);
    try {
      const r = await rpc<{ code: string; admin_secret: string }>('recreate_trip', {
        p_carpool_code: carpoolCode,
        p_driver_secret: store.driverSecret(),
      });
      store.setAdminSecret(r.code, r.admin_secret);
      navigate(`/d/${r.code}`);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (loading) return <Page>{null}</Page>;

  if (!driver) {
    return (
      <Page>
        <div className="pt-6">
          <h1 className="text-2xl font-bold leading-snug">
            함께 타는 길,
            <br />
            기름값은 공정하게
          </h1>
          <p className="mt-2 text-[15px] text-sub">
            운행을 만들면 동승자에게 보낼 링크와 QR이 생겨요. 회원가입은 없어요.
          </p>
        </div>
        <Card className="flex flex-col gap-4">
          <Field label="동승자에게 보일 이름">
            <TextInput value={name} onChange={setName} placeholder="예: 지훈" />
          </Field>
          <Button onClick={start} disabled={!name.trim()}>
            운전자로 시작하기
          </Button>
          <ErrorText error={error} />
        </Card>
        <p className="px-1 text-sm text-sub">동승자라면 운전자가 보낸 링크나 차 안의 QR로 들어오면 돼요.</p>
      </Page>
    );
  }

  return (
    <Page>
      <div className="flex items-end justify-between pt-2">
        <h1 className="text-2xl font-bold">{driver.display_name}님, 어디로 갈까요?</h1>
        <Link to="/settings" className="text-sm text-sub underline-offset-2 hover:underline">
          설정
        </Link>
      </div>

      <Button onClick={() => navigate('/new')}>새 운행 만들기</Button>
      <ErrorText error={error} />

      {driver.carpools.length > 0 && (
        <Card className="flex flex-col gap-3">
          <h2 className="font-semibold">정기 카풀</h2>
          {driver.carpools.map((c) => (
            <div key={c.code} className="flex items-center justify-between gap-3">
              <span className="truncate">{c.title}</span>
              <button onClick={() => again(c.code)} className="shrink-0 rounded-lg bg-brand-soft px-3 py-2 text-sm font-semibold text-brand">
                지난 운행 다시 만들기
              </button>
            </div>
          ))}
        </Card>
      )}

      {driver.trips.length > 0 && (
        <Card className="flex flex-col gap-1">
          <h2 className="mb-2 font-semibold">최근 운행</h2>
          {driver.trips.map((t) => (
            <Link key={t.code} to={`/d/${t.code}`} className="flex items-center justify-between rounded-lg py-2">
              <span className="truncate">{t.title}</span>
              <StatusPill status={t.status} />
            </Link>
          ))}
        </Card>
      )}

      <p className="px-1 text-sm text-sub">
        {driver.vehicles > 0
          ? '차와 연결돼 있어요. 시동을 켜면 여기서 만든 운행이 차 화면에 떠요.'
          : '차 화면의 연결 QR을 찍으면, 여기서 만든 운행을 차에서 바로 불러올 수 있어요.'}
      </p>
    </Page>
  );
}
