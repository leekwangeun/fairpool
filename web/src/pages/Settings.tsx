import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { rpc, type Driver } from '../api';
import { store } from '../store';
import { Button, Card, Checkbox, ErrorText, Field, Page, TextInput } from '../ui';

// Driver profile and the optional bank account shown to passengers after settlement.
export default function Settings() {
  const navigate = useNavigate();
  const secret = store.driverSecret();
  const [name, setName] = useState('');
  const [bankName, setBankName] = useState('');
  const [account, setAccount] = useState('');
  const [holder, setHolder] = useState('');
  const [consent, setConsent] = useState(false);
  const [hasBank, setHasBank] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function fill(d: Driver) {
    setName(d.display_name);
    setBankName(d.bank?.bank_name ?? '');
    setAccount(d.bank?.account ?? '');
    setHolder(d.bank?.holder ?? '');
    setHasBank(!!d.bank);
    setConsent(!!d.bank);
  }

  useEffect(() => {
    if (!secret) {
      navigate('/', { replace: true });
      return;
    }
    rpc<Driver>('get_driver', { p_driver_secret: secret }).then(fill).catch((e) => setError((e as Error).message));
  }, [secret, navigate]);

  async function save() {
    setError(null);
    try {
      const withBank = consent && account.trim() !== '';
      fill(
        await rpc<Driver>('update_driver', {
          p_driver_secret: secret,
          p_display_name: name,
          p_bank_name: withBank ? bankName.trim() : null,
          p_bank_account: withBank ? account.trim() : null,
          p_account_holder: withBank ? holder.trim() : null,
          p_bank_consent: withBank ? true : hasBank ? false : null,
        }),
      );
      setSaved(true);
      window.setTimeout(() => setSaved(false), 1500);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function forget() {
    if (!window.confirm('이 휴대폰에서 운전자 정보를 지울까요? 만든 운행을 다시 관리할 수 없게 돼요.')) return;
    store.setDriverSecret(null);
    navigate('/', { replace: true });
  }

  return (
    <Page back="/">
      <h1 className="pt-2 text-2xl font-bold">설정</h1>
      <Card className="gap-4">
        <Field label="동승자에게 보일 이름">
          <TextInput value={name} onChange={setName} />
        </Field>
      </Card>
      <Card className="gap-4">
        <div>
          <h2 className="t-strong">정산 받을 계좌 (선택)</h2>
          <p className="t-caption mt-1 text-sub">정산이 끝난 운행에 실제로 탄 사람에게만 보여요. 페어풀은 송금하지 않아요.</p>
        </div>
        <Field label="은행">
          <TextInput value={bankName} onChange={setBankName} placeholder="예: 페어은행" />
        </Field>
        <Field label="계좌번호">
          <TextInput value={account} onChange={setAccount} inputMode="numeric" />
        </Field>
        <Field label="예금주">
          <TextInput value={holder} onChange={setHolder} />
        </Field>
        <Checkbox
          checked={consent}
          onChange={setConsent}
          title="계좌번호를 동승자에게 보여 주는 데 동의해요"
          sub="정산 결과와 함께 보여요. 동의를 끄고 저장하면 바로 지워져요."
        />
      </Card>
      <Button onClick={save}>{saved ? '저장했어요' : '저장'}</Button>
      <ErrorText error={error} />
      <button onClick={forget} className="mt-4 text-sm text-sub underline">
        이 휴대폰에서 운전자 정보 지우기
      </button>
    </Page>
  );
}
