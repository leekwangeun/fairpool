import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import type { BatchStatus, DriverView, ShareCard } from '../api';
import { settleLink, tripLink } from '../store';
import { AmountRow, BatchPill, Button, Card, Page, QrImage, copyText, dayLabel, won } from '../ui';

// The driver's own phone, opened with the driver's name and last 4 digits on M1-1 or the settlement page:
// what "내 휴대폰으로 보내기" sent from the car (an invite or a settlement), ready to pass on to the passengers.
// Read-only; the trip and its settlement change only on the car screen.
export default function DriverSend({ view }: { view: DriverView }) {
  return (
    <Page gap={14}>
      <Card className="gap-1">
        <p className="t-caption text-brand">{view.driver_name}님의 휴대폰</p>
        <h1 className="t-title">동승자에게 보내기</h1>
        <p className="t-body text-sub">차 화면에서 보낸 초대와 정산이에요. 공유하기로 단톡방이나 문자에 보내 주세요</p>
      </Card>
      {view.cards.length === 0 && (
        <Card>
          <p className="t-body text-sub">아직 보낼 것이 없어요. 차 화면에서 '내 휴대폰으로 보내기'를 누르면 여기에 보여요</p>
        </Card>
      )}
      {view.cards.map((c) => (
        <SendCard key={`${c.kind}-${c.code}`} card={c} />
      ))}
      <p className="t-caption text-center text-faint">보기 전용이에요. 운행과 정산은 차 화면에서만 바꿀 수 있어요</p>
    </Page>
  );
}

function SendCard({ card }: { card: ShareCard }) {
  const [copied, setCopied] = useState(false);
  const [rides, setRides] = useState(false);
  const stacking = card.kind === 'trip' && card.settle_mode === 'folder';
  const status: BatchStatus = stacking ? 'open' : card.people.every((p) => p.paid) ? 'done' : 'requested';
  const url = card.kind === 'batch' ? settleLink(card.code) : tripLink(card.code);
  const invite = card.kind === 'invite';
  const text = invite
    ? `${card.title} 카풀에 초대해요. 링크에서 탈 곳과 내릴 곳을 골라 주세요.`
    : stacking
    ? `${card.title} 운행이 ${card.folder ?? '카풀 폴더'}에 쌓였어요. 내 몫은 링크에서 확인해 주세요.`
    : `${card.title} 정산이에요. 내 몫과 계좌번호는 링크에서 확인해 주세요.`;

  // the QR image is made ahead of time: share() has to run right in the tap, before any other await
  const [qr, setQr] = useState<File | null>(null);
  useEffect(() => {
    QRCode.toDataURL(url, { margin: 2, width: 720 })
      .then((data) => fetch(data))
      .then((res) => res.blob())
      .then((blob) => setQr(new File([blob], `fairpool-${card.code}.png`, { type: 'image/png' })))
      .catch(() => setQr(null));
  }, [url, card.code]);

  async function share() {
    if (navigator.share) {
      const withQr = qr && navigator.canShare?.({ files: [qr] });
      try {
        // with a file some apps drop the url field, so the link goes in the text
        await navigator.share(withQr ? { title: '페어풀', text: `${text}\n${url}`, files: [qr] } : { title: '페어풀', text, url });
        return;
      } catch (e) {
        if ((e as Error).name === 'AbortError') return;
      }
    }
    if (await copyText(`${text}\n${url}`)) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    }
  }

  return (
    <Card className="gap-2.5">
      <div className="flex items-center gap-2">
        <h2 className="t-strong flex-1">{card.kind === 'batch' ? `카풀 폴더 | ${card.title}` : card.title}</h2>
        {!invite && <BatchPill status={status} />}
      </div>
      <p className="t-caption text-sub">
        {dayLabel(card.date)}{' '}
        {invite
          ? `초대 | 자리 ${card.seats}개 중 ${card.seats_left}개 남음${card.origin ? ` | ${card.origin} 출발` : ''}`
          : card.kind === 'batch' ? '합쳐서 정산 요청' : stacking ? `${card.folder ?? '카풀 폴더'}에 쌓는 중` : '바로 정산'}
      </p>
      {card.total != null && <AmountRow strong label={card.kind === 'batch' ? '받을 합계' : '총비용'} amount={card.total} />}
      {invite && card.people.length === 0 && <p className="t-caption text-sub">아직 참여한 사람이 없어요</p>}
      {card.people.map((p, i) => (
        <div key={i} className="flex items-center gap-2 rounded-xl bg-page px-3.5 py-2.5">
          <div className="flex min-w-0 flex-1 flex-col">
            <p className="t-strong">
              {p.name}
              {p.paid && <span className="t-caption ml-1.5 text-[#148f4d]">받음</span>}
            </p>
            {p.trips != null && <p className="t-caption text-sub">{p.trips}회 탐</p>}
            {(p.from || p.to) && (
              <p className="t-caption truncate text-sub">
                {p.from ?? '출발지'} → {p.to ?? ''}
              </p>
            )}
          </div>
          {p.amount != null ? <span className="t-body shrink-0">{won(p.amount)}</span> : <span className="t-caption shrink-0 text-brand">참여함</span>}
        </div>
      ))}
      {card.rides && card.rides.length > 0 && (
        <>
          <button type="button" onClick={() => setRides(!rides)} className="t-caption text-left text-brand">
            {rides ? '운행별 내역 접기' : `운행별 내역 ${card.rides.length}회 보기`}
          </button>
          {rides &&
            card.rides.map((r, i) => (
              <div key={i} className="flex flex-col gap-0.5 border-b border-line pb-2 last:border-b-0">
                <p className="t-caption text-sub">
                  {dayLabel(r.date)} {r.title}
                </p>
                {r.shares.map((s, j) => (
                  <p key={j} className="t-caption flex justify-between gap-2">
                    <span className="truncate">
                      {s.name}
                      {(s.from || s.to) && ` | ${s.from ?? '출발지'} → ${s.to ?? ''}`}
                    </span>
                    <span className="shrink-0">{won(s.amount)}</span>
                  </p>
                ))}
              </div>
            ))}
        </>
      )}
      {card.bank && (
        <p className="t-caption text-sub">
          받을 계좌 {card.bank.bank_name} {card.bank.account} ({card.bank.holder})
        </p>
      )}
      <div className="flex items-center gap-3 rounded-xl bg-brand-soft p-3">
        <span className="shrink-0 rounded-lg bg-card p-1.5">
          <QrImage text={url} size={96} />
        </span>
        <p className="t-caption text-sub">
          옆에 있는 사람은 이 QR을 찍으면 돼요. 공유하기로 보내면 QR 이미지와 링크가 함께 가요
        </p>
      </div>
      <Button onClick={share}>{copied ? '안내 문구와 링크를 복사했어요' : 'QR과 링크 공유하기'}</Button>
    </Card>
  );
}
