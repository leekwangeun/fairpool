import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { rpc } from '../api';
import { store } from '../store';
import { Button, Card, ErrorText, Field, Page, TextInput } from '../ui';

// Opened from the link QR on the car screen: ties this driver page to that car, once.
export default function VehicleLink() {
  const { code = '' } = useParams();
  const [name, setName] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const hasDriver = !!store.driverSecret();

  async function link() {
    setError(null);
    try {
      if (!store.driverSecret()) {
        const r = await rpc<{ driver_secret: string }>('create_driver', { p_display_name: name });
        store.setDriverSecret(r.driver_secret);
      }
      await rpc('claim_vehicle_link', { p_link_code: code, p_driver_secret: store.driverSecret() });
      setDone(true);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Page back="/">
      <h1 className="pt-2 text-2xl font-bold">차와 연결하기</h1>
      {done ? (
        <Card className="flex flex-col gap-3">
          <p>연결됐어요. 이제 여기서 만든 운행을 차 화면에서 바로 불러올 수 있어요.</p>
          <Link to="/" className="font-semibold text-brand">
            운행 만들러 가기
          </Link>
        </Card>
      ) : (
        <Card className="flex flex-col gap-4">
          <p className="text-[15px] text-sub">회원가입 없이 이 휴대폰과 차만 연결해요. 차 화면에서 언제든 연결을 끊을 수 있어요.</p>
          {!hasDriver && (
            <Field label="동승자에게 보일 이름">
              <TextInput value={name} onChange={setName} placeholder="예: 지훈" />
            </Field>
          )}
          <Button onClick={link} disabled={!hasDriver && !name.trim()}>
            이 차와 연결
          </Button>
          <ErrorText error={error} />
        </Card>
      )}
    </Page>
  );
}
