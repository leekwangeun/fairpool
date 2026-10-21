import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { rpc } from '../api';
import { store } from '../store';
import { Card, Page, won } from '../ui';

type Carpool = { code: string; title: string; latest_trip: string | null; month: { trips: number; my_amount: number } };

// Stable link of a regular carpool: always points at the newest trip and shows this month's total.
export default function CarpoolLink() {
  const { code = '' } = useParams();
  const [carpool, setCarpool] = useState<Carpool | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    rpc<Carpool>('get_carpool', { p_carpool_code: code, p_device_key: store.deviceKey() })
      .then(setCarpool)
      .catch((e) => setError((e as Error).message));
  }, [code]);

  if (error) {
    return (
      <Page>
        <Card>
          <p>{error}</p>
        </Card>
      </Page>
    );
  }
  if (!carpool) return <Page>{null}</Page>;

  const month = new Date().getMonth() + 1;
  return (
    <Page>
      <h1 className="pt-2 text-2xl font-bold">{carpool.title}</h1>
      <Card className="flex flex-col gap-1">
        <p className="text-sm text-sub">{month}월 함께 탄 횟수</p>
        <p className="text-2xl font-bold">{carpool.month.trips}번</p>
        <p className="mt-2 text-sm text-sub">{month}월 내 분담액</p>
        <p className="text-2xl font-bold text-brand">{won(Number(carpool.month.my_amount))}</p>
      </Card>
      {carpool.latest_trip ? (
        <Link to={`/t/${carpool.latest_trip}`} className="flex h-12 items-center justify-center rounded-xl bg-brand font-semibold text-white">
          이번 운행 열기
        </Link>
      ) : (
        <Card>
          <p className="text-sub">아직 새 운행이 없어요. 운전자가 만들면 여기서 열려요.</p>
        </Card>
      )}
    </Page>
  );
}
