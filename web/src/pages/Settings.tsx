import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { rpc, type Driver } from '../api';
import { store } from '../store';
import { Button, Card, Checkbox, ErrorText, Field, Page, TextInput } from '../ui';
import iconChevronDown from '../assets/figma/icon-chevron-down.svg';

const banks = [
  'KB국민은행', '신한은행', '우리은행', '하나은행', 'NH농협은행', 'IBK기업은행', '카카오뱅크', '토스뱅크', '케이뱅크',
  'SC제일은행', '한국씨티은행', 'iM뱅크', '부산은행', '경남은행', '광주은행', '전북은행', '제주은행', '수협은행',
  '새마을금고', '신협', '우체국',
];

// W1: the optional account passengers see while their settlement is in progress (정산 중).
export default function Settings() {
  const navigate = useNavigate();
  const secret = store.driverSecret();
  const [bankName, setBankName] = useState('');
  const [account, setAccount] = useState('');
  const [holder, setHolder] = useState('');
  const [consent, setConsent] = useState(false);
  const [hasBank, setHasBank] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function fill(d: Driver) {
    setBankName(d.bank?.bank_name ?? '');
    setAccount(d.bank?.account ?? '');
    setHolder(d.bank?.holder ?? d.display_name);
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
      fill(
        await rpc<Driver>('update_driver', {
          p_driver_secret: secret,
          p_bank_name: bankName,
          p_bank_account: account.trim(),
          p_account_holder: holder.trim(),
          p_bank_consent: true,
        }),
      );
      setSaved(true);
      window.setTimeout(() => setSaved(false), 1500);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function remove() {
    if (!window.confirm('등록한 계좌를 지울까요? 동승자 페이지에서도 바로 내려가요.')) return;
    setError(null);
    try {
      fill(await rpc<Driver>('update_driver', { p_driver_secret: secret, p_bank_consent: false }));
      setBankName('');
      setAccount('');
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function forget() {
    if (!window.confirm('이 휴대폰에서 운전자 정보를 지울까요? 만든 운행을 다시 관리할 수 없게 돼요.')) return;
    store.setDriverSecret(null);
    navigate('/', { replace: true });
  }

  const ready = consent && bankName !== '' && account.trim() !== '' && holder.trim() !== '';

  return (
    <Page tag="운전자 페이지" gap={14}>
      <div className="flex flex-col gap-1">
        <p className="t-caption text-brand">운행 준비 | 정산 설정</p>
        <h1 className="t-title">받을 계좌 등록 (선택)</h1>
        <p className="t-body text-sub">등록하면 정산 중인 동승자 페이지에만 계좌번호가 보여요. 정산이 끝나면 내려가요</p>
      </div>

      <Card className="gap-4">
        <Field label="은행">
          <label className="relative flex h-12 w-full items-center rounded-xl bg-tile px-3.5 focus-within:ring-2 focus-within:ring-brand">
            <select
              value={bankName}
              onChange={(e) => setBankName(e.target.value)}
              className={`t-body h-full w-full appearance-none bg-transparent pr-7 outline-none ${bankName ? '' : 'text-faint'}`}
            >
              <option value="" disabled>
                은행을 골라 주세요
              </option>
              {[...new Set([...banks, ...(bankName && !banks.includes(bankName) ? [bankName] : [])])].map((b) => (
                <option key={b} value={b} className="text-ink">
                  {b}
                </option>
              ))}
            </select>
            <img src={iconChevronDown} width={20} height={20} alt="" className="pointer-events-none absolute right-3.5" />
          </label>
        </Field>
        <Field label="계좌번호">
          <TextInput value={account} onChange={setAccount} inputMode="numeric" placeholder="- 없이 숫자만 써도 돼요" />
        </Field>
        <Field label="예금주">
          <TextInput value={holder} onChange={setHolder} />
        </Field>
        <Checkbox checked={consent} onChange={setConsent} title="[필수] 정산 중인 동승자에게 계좌번호를 보여 주는 데 동의해요" />
      </Card>

      <div className="flex w-full flex-col gap-1 rounded-2xl bg-brand-soft px-4 py-3.5">
        <p className="t-caption text-brand">• 페어풀은 송금하지 않아요. 동승자가 자기 은행 앱에서 보내요</p>
        <p className="t-caption text-brand">• 계좌번호는 서버에 암호화해 보관하고, 지우면 바로 삭제돼요</p>
      </div>

      <ErrorText error={error} />
      <div className="flex-1" />
      <div className="flex w-full gap-2.5">
        <Button variant="secondary" onClick={remove} disabled={!hasBank} className="flex-1">
          계좌 지우기
        </Button>
        <Button onClick={save} disabled={!ready} className="t-strong! flex-1">
          {saved ? '저장했어요' : '저장하기'}
        </Button>
      </div>
      <button type="button" onClick={forget} className="t-caption text-sub underline underline-offset-2">
        이 휴대폰에서 운전자 정보 지우기
      </button>
    </Page>
  );
}
