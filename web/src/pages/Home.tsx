import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, rpc, type Driver, type FolderItem } from '../api';
import { store } from '../store';
import { BatchPill, Button, Card, ErrorText, Field, Page, StatusPill, TextInput, won } from '../ui';

// Driver home: start a new trip, re-create a saved carpool, carpool folders, recent trips.
export default function Home() {
  const navigate = useNavigate();
  const [driver, setDriver] = useState<Driver | null>(null);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const [folders, setFolders] = useState<FolderItem[]>([]);

  useEffect(() => {
    const secret = store.driverSecret();
    if (!secret) {
      setLoading(false);
      return;
    }
    rpc<Driver>('get_driver', { p_driver_secret: secret })
      .then(setDriver)
      // forget the secret only when the server says it is not valid, not on a dropped connection
      .catch((e) => {
        if (e instanceof ApiError && e.code === 'not_allowed') store.setDriverSecret(null);
        else setError((e as Error).message);
      })
      .finally(() => setLoading(false));
    rpc<FolderItem[]>('list_folders', { p_secret: secret })
      .then(setFolders)
      .catch(() => setFolders([]));
  }, []);

  async function rename() {
    const next = window.prompt('동승자에게 보일 이름', driver?.display_name ?? '');
    if (!next?.trim()) return;
    setDriver(await rpc<Driver>('update_driver', { p_driver_secret: store.driverSecret(), p_display_name: next }));
  }

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

  // the x next to a kept address: the place for the next trip goes, trip records stay
  async function removePlace(carpoolCode: string, memberId: string, memberName: string) {
    if (!window.confirm(`즐겨찾기에 남긴 ${memberName} 님의 장소를 지울까요? 운행 기록은 그대로예요.`)) return;
    setError(null);
    try {
      await rpc('remove_member_place', { p_carpool_code: carpoolCode, p_secret: store.driverSecret(), p_member_id: memberId });
      setDriver(await rpc<Driver>('get_driver', { p_driver_secret: store.driverSecret() }));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function forget() {
    if (!window.confirm('이 휴대폰에서 운전자 정보를 지울까요? 만든 운행을 다시 관리할 수 없게 돼요.')) return;
    store.setDriverSecret(null);
    setDriver(null);
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
      <h1 className="pt-2 text-2xl font-bold">
        <button type="button" onClick={rename} className="text-left">
          {driver.display_name}님
        </button>
        , 어디로 갈까요?
      </h1>

      <Button onClick={() => navigate('/new')}>새 운행 만들기</Button>
      <ErrorText error={error} />

      {driver.carpools.length > 0 && (
        <Card className="flex flex-col gap-3">
          <h2 className="t-strong">즐겨찾기</h2>
          {driver.carpools.map((c) => (
            <div key={c.code} className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between gap-3">
                <span className="truncate">{c.title}</span>
                <button onClick={() => again(c.code)} className="shrink-0 rounded-lg bg-brand-soft px-3 py-2 text-sm font-semibold text-brand">
                  지난 운행 다시 만들기
                </button>
              </div>
              {c.members
                .filter((m) => m.pickup || m.dropoff)
                .map((m) => (
                  <div key={m.id} className="flex items-center gap-2 rounded-lg bg-page px-3 py-1.5">
                    <span className="t-caption min-w-0 flex-1 truncate text-sub">
                      {m.name} | {[m.pickup, m.dropoff].filter(Boolean).join(' → ')}
                    </span>
                    <button
                      type="button"
                      onClick={() => removePlace(c.code, m.id, m.name)}
                      aria-label={`${m.name} 장소 지우기`}
                      className="t-strong shrink-0 px-1 text-faint"
                    >
                      ×
                    </button>
                  </div>
                ))}
            </div>
          ))}
        </Card>
      )}

      {folders.length > 0 && (
        <Card className="gap-1">
          <h2 className="t-strong mb-1">카풀 폴더</h2>
          {folders.map((f) => (
            <Link key={f.code} to={`/f/${f.code}`} className="flex items-center gap-2 py-2">
              <span className="truncate">{f.name}</span>
              <BatchPill status={f.status} />
              <span className="t-caption ml-auto shrink-0 text-sub">
                {f.trips}회 {won(f.amount)}
              </span>
            </Link>
          ))}
        </Card>
      )}

      {driver.trips.length > 0 && (
        <Card className="flex flex-col gap-1">
          <h2 className="mb-2 font-semibold">최근 운행</h2>
          {driver.trips.map((t) =>
            // trips made on the car are managed there; this phone has no admin secret for them
            store.adminSecret(t.code) ? (
              <Link key={t.code} to={`/d/${t.code}`} className="flex items-center justify-between rounded-lg py-2">
                <span className="truncate">{t.title}</span>
                <StatusPill status={t.status} />
              </Link>
            ) : (
              <div key={t.code} className="flex items-center justify-between gap-2 rounded-lg py-2">
                <span className="flex min-w-0 flex-col">
                  <span className="truncate">{t.title}</span>
                  <span className="t-caption text-faint">차 화면에서 만든 운행이에요</span>
                </span>
                <StatusPill status={t.status} />
              </div>
            ),
          )}
        </Card>
      )}

      <p className="px-1 text-sm text-sub">
        {driver.vehicles > 0
          ? '차와 연결돼 있어요. 시동을 켜면 여기서 만든 운행이 차 화면에 떠요.'
          : '차 화면의 연결 QR을 찍으면, 여기서 만든 운행을 차에서 바로 불러올 수 있어요.'}{' '}
        운전자 이름, 휴대폰 번호 뒤 4자리, 정산 받을 계좌는 주차 중 차 화면의 운전자 정보 입력에서 넣어요.
      </p>
      <button type="button" onClick={forget} className="t-caption text-sub underline underline-offset-2">
        이 휴대폰에서 운전자 정보 지우기
      </button>
    </Page>
  );
}
