import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { demoMode, issueLabel, rpc, type BatchSummary, type FolderAdmin as Folder } from '../api';
import { settleLink, store } from '../store';
import { BatchPill, Button, Card, CarStandIn, Checkbox, ErrorText, Page, QrImage, copyText, dayLabel, usePolling, won } from '../ui';

// Driver view of one carpool folder, like the car screens L1-6 (요청 전) and L1-7 (정산 중):
// the stacked trips, what each passenger owes, and the request / received buttons.
export default function FolderAdmin() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const secret = store.driverSecret();
  const [folder, setFolder] = useState<Folder | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  async function load() {
    if (!secret) return;
    try {
      setFolder(await rpc<Folder>('get_folder_admin', { p_folder_code: code, p_secret: secret }));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  usePolling(load);

  async function run(fn: () => Promise<Folder>) {
    setError(null);
    try {
      setFolder(await fn());
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!secret) {
    return (
      <Page back="/">
        <Card>
          <p className="t-body">운전자로 시작한 휴대폰에서만 카풀 폴더를 볼 수 있어요.</p>
        </Card>
      </Page>
    );
  }
  if (!folder) {
    return (
      <Page back="/">
        <ErrorText error={error} />
      </Page>
    );
  }

  const req = folder.requested;
  const batch: BatchSummary = req ?? folder.open;
  const args = { p_folder_code: folder.code, p_secret: secret };

  function request() {
    if (!window.confirm(`${folder!.name} ${batch.trips.length}회를 합쳐서 정산할까요? 동승자 페이지에 금액과 계좌번호가 보여요.`)) return;
    run(() => rpc<Folder>('request_batch', args));
  }

  function cancel() {
    if (!window.confirm('정산 요청을 취소할까요? 동승자 페이지는 다시 요청 전으로 돌아가요.')) return;
    run(() => rpc<Folder>('cancel_batch_request', args));
  }

  function markPaid(id: string, name: string, amount: number, paid: boolean) {
    const ask = paid ? `${name} 님의 ${won(amount)} 정산을 완료로 바꿀까요?` : `${name} 님의 정산 완료를 취소할까요?`;
    if (!window.confirm(ask)) return;
    run(() => rpc<Folder>('mark_batch_paid', { ...args, p_payment_id: id, p_paid: paid }));
  }

  // one settlement page for the whole batch; each phone sees only its own part
  async function share() {
    const url = settleLink(req!.code);
    const text = `${folder!.name} ${batch.trips.length}회 정산을 요청했어요. 내 분담액과 계좌번호를 확인해 주세요.`;
    if (navigator.share) await navigator.share({ title: '페어풀', text, url }).catch(() => undefined);
    else if (await copyText(url)) {
      setSent(true);
      window.setTimeout(() => setSent(false), 1500);
    }
  }

  const km = batch.trips.reduce((sum, t) => sum + (t.km ?? 0), 0);
  const sameEach = (name: string) => {
    const amounts = batch.trips.flatMap((t) => t.shares.filter((s) => s.name === name).map((s) => s.amount));
    return amounts.length > 1 && amounts.every((a) => a === amounts[0]);
  };

  return (
    <Page back="/" gap={14}>
      <CarStandIn />
      <Card className="gap-1">
        <div className="flex items-center gap-2">
          <p className="t-caption text-brand">카풀 폴더 | {folder.name}</p>
          <BatchPill status={req ? 'requested' : 'open'} />
        </div>
        <h1 className="t-title">쌓인 운행 {batch.trips.length}회</h1>
        <p className="t-caption text-sub">
          {req
            ? `${dayLabel(req.requested_at).split(' (')[0]}에 정산을 요청했어요. 동승자 페이지에 금액과 내역이 전달됐어요`
            : folder.last_done && batch.trips.length === 0
              ? '지난 정산이 끝났어요. 다음 운행부터 다시 0원에서 쌓여요'
              : '운행마다 계산해 둔 금액이에요. 확정하면 한 번에 정산해요'}
        </p>
      </Card>

      <Card className="gap-2.5">
        <div className="flex flex-col gap-0.5">
          <p className="t-caption text-sub">
            {folder.name} {batch.trips.length}회, 동승자 {batch.people.length}명 받을 합계
          </p>
          <div className="flex items-baseline gap-1">
            <span className="t-number">{Math.round(batch.receivable).toLocaleString('ko-KR')}</span>
            <span className="t-headline text-sub">원</span>
          </div>
          <p className="t-caption text-sub">
            {km > 0 && `${Math.round(km)}km | `}총비용 {won(batch.total)} | 운전자 몫 {won(batch.driver_amount)} 포함
          </p>
        </div>

        {(req ? req.payments : batch.people.map((p) => ({ ...p, id: p.name, paid: false }))).map((p) => (
          <div key={p.id} className="flex items-center gap-2 rounded-xl bg-brand-soft px-3.5 py-2.5">
            <div className="flex min-w-0 flex-1 flex-col">
              <div className="flex items-center gap-1.5">
                <span className="t-strong">{p.name}</span>
                {req && <BatchPill status={p.paid ? 'done' : 'requested'} />}
              </div>
              <span className="t-caption truncate text-sub">
                {p.trips}회 탐 | {sameEach(p.name) ? '회마다 금액이 같아요' : '회마다 금액이 달라요'}
              </span>
            </div>
            <span className="t-headline shrink-0 text-brand">{won(p.amount)}</span>
            {req && (
              <span className="w-[22px] shrink-0">
                <Checkbox checked={p.paid} onChange={(v) => markPaid(p.id, p.name, p.amount, v)} title={<span className="sr-only">정산 완료</span>} />
              </span>
            )}
          </div>
        ))}
        <div className="flex items-center gap-2 rounded-xl bg-tile px-3.5 py-2.5">
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="t-strong">운전자 몫 (나)</span>
            <span className="t-caption text-sub">{batch.trips.length}회 | 받을 금액 아님</span>
          </div>
          <span className="t-headline shrink-0">{won(batch.driver_amount)}</span>
        </div>
        {req ? (
          <div className="flex items-center gap-3 rounded-xl bg-brand-soft p-3">
            <span className="shrink-0 rounded-lg bg-card p-1.5">
              <QrImage text={settleLink(req.code)} size={96} />
            </span>
            <div className="flex min-w-0 flex-col gap-1">
              <p className="t-strong text-brand">동승자에게 보낼 QR</p>
              <p className="t-caption text-sub">
                같은 QR을 찍어도 이름과 휴대폰 번호 뒤 4자리로 확인해 각자 자기 내역과 분담액만 보여요
              </p>
              <p className="t-caption text-sub">송금을 받은 사람부터 정산 완료를 눌러 주세요</p>
            </div>
          </div>
        ) : (
          <div className="rounded-xl bg-brand-soft px-3.5 py-3">
            <p className="t-caption text-brand">
              합쳐서 정산하면 동승자마다 자기 운행과 분담액만 담긴 정산 페이지가 하나 만들어져요. 카풀 이름과 동승자는 남고 금액만 비워 다음 운행을 쌓아요
            </p>
          </div>
        )}
      </Card>

      {req && req.issues.length > 0 && (
        <Card className="gap-2">
          <h2 className="t-strong">동승자가 남긴 문제</h2>
          {req.issues.map((i) => (
            <p key={i.id} className={`t-body ${i.resolved_at ? 'text-faint' : ''}`}>
              <span className="text-sub">{i.name ?? '동승자'}</span> {issueLabel(i.kind)}
              {i.message && ` | "${i.message}"`}
              {i.resolved_at && ' (처리함)'}
            </p>
          ))}
        </Card>
      )}

      <ErrorText error={error} />
      <div className="flex w-full gap-2.5">
        {req ? (
          <>
            {/* a received payment would be erased: untick it first (the server refuses with settle_started) */}
            <Button variant="secondary" onClick={cancel} disabled={req.payments.some((p) => p.paid)} className="flex-1">
              정산 요청 취소
            </Button>
            <Button onClick={share} className="t-strong! flex-1">
              {sent ? '링크를 복사했어요' : '링크로 보내기'}
            </Button>
          </>
        ) : (
          <>
            <Button variant="secondary" onClick={() => navigate('/')} className="flex-1">
              계속 쌓기
            </Button>
            <Button onClick={request} disabled={batch.trips.length === 0} className="t-strong! flex-1">
              합쳐서 정산하기
            </Button>
          </>
        )}
      </div>
      {demoMode && req && (
        // demo only: the L1-7 car screen's "내 휴대폰으로 보내기"
        <Button
          variant="secondary"
          onClick={() =>
            run(async () => {
              await rpc('queue_batch_share', args);
              return rpc<Folder>('get_folder_admin', args);
            })
          }
        >
          시연: 차에서 '내 휴대폰으로 보내기' 누르기
        </Button>
      )}

      <TripList batch={batch} />

      {/* a tick taken by mistake can be undone; the batch goes back to 정산 중 */}
      {!req && folder.last_done && (
        <Card className="gap-2">
          <h2 className="t-strong">지난 정산 ({dayLabel(folder.last_done.done_at).split(' (')[0]} 완료)</h2>
          {folder.last_done.payments.map((p) => (
            <div key={p.id} className="flex items-center gap-2">
              <span className="t-body min-w-0 flex-1 text-sub">
                {p.name} {p.trips}회
              </span>
              <span className="t-body">{won(p.amount)}</span>
              <span className="w-[22px] shrink-0">
                <Checkbox
                  checked={p.paid}
                  onChange={(v) => markPaid(p.id, p.name, p.amount, v)}
                  title={<span className="sr-only">정산 완료</span>}
                />
              </span>
            </div>
          ))}
          <p className="t-caption text-faint">잘못 체크했다면 풀어 주세요. 그 묶음이 다시 정산 중이 돼요</p>
        </Card>
      )}

      {req && folder.open.trips.length > 0 && (
        <Card className="gap-1">
          <h2 className="t-strong">요청 뒤에 쌓인 운행 {folder.open.trips.length}회</h2>
          <p className="t-caption text-sub">{won(folder.open.receivable)} | 지금 정산이 끝나면 다음 묶음으로 이어져요</p>
        </Card>
      )}
    </Page>
  );
}

function TripList({ batch }: { batch: BatchSummary }) {
  const [all, setAll] = useState(false);
  if (batch.trips.length === 0) return null;
  const visible = all ? batch.trips : batch.trips.slice(0, 5);
  const rest = batch.trips.slice(5);
  return (
    <Card className="gap-0">
      <h2 className="t-strong pb-1">운행별 내역</h2>
      {visible.map((t) => {
        const riders = t.shares.filter((s) => s.role !== 'driver').map((s) => s.name);
        return (
          <Link key={t.code} to={`/d/${t.code}`} className="flex items-center gap-3 border-b border-line py-2.5 last:border-b-0">
            <span className="t-strong w-[118px] shrink-0">{dayLabel(t.date)}</span>
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="t-caption truncate">
                {t.title}
                {t.km != null && ` ${Math.round(t.km)}km`}
              </span>
              <span className="t-caption truncate text-sub">{riders.join(', ')} 태움</span>
            </span>
            <span className="t-body shrink-0">{won(t.total)}</span>
          </Link>
        );
      })}
      {!all && rest.length > 0 && (
        <button type="button" onClick={() => setAll(true)} className="t-caption pt-2 text-left text-brand">
          {rest.length}회 더 보기
        </button>
      )}
    </Card>
  );
}
