import { useNavigate, useParams } from 'react-router-dom';
import { Button, Card, Page } from '../ui';

const steps = [
  ['한 사람씩 탄다고 보고,', '그 사람 때문에 늘어난 비용을 재요'],
  ['타는 순서를 모두 바꿔 보며 평균 내요.', '먼저 타든 늦게 타든 같아요'],
  ['돌아간 거리는 만든 사람이 더 내고,', '함께 타서 아낀 돈은 모두 나눠요'],
];

// M4: how the shares are calculated (opened from the i icon).
export default function HowItWorks() {
  const { code = '' } = useParams();
  const navigate = useNavigate();
  return (
    <Page gap={14}>
      <div className="flex flex-col gap-0.5">
        <p className="t-caption text-brand">계산 방법</p>
        <h1 className="t-title">이렇게 계산해요</h1>
      </div>

      <Card className="gap-2.5">
        <h2 className="t-headline whitespace-pre">{'1  총비용을 구해요'}</h2>
        <div className="t-strong rounded-xl bg-brand-soft px-3.5 py-3 text-brand">주행 거리 ÷ 연비 × 유가 + 통행료</div>
        <p className="t-caption text-sub">
          누가 어디서 타고 내렸는지는 좌석 센서와 정차 위치로 자동 기록해서, 탄 거리를 따로 입력하지 않아도 돼요
        </p>
      </Card>

      <Card className="gap-2">
        <div className="flex items-center gap-2.5">
          <h2 className="t-headline whitespace-pre">{'2  공정하게 나눠요'}</h2>
          <span className="t-caption rounded-full bg-brand px-2 py-[3px] text-white">섀플리 값</span>
        </div>
        <ol className="flex flex-col gap-2">
          {steps.map(([a, b], i) => (
            <li key={i} className="flex items-start gap-2.5">
              <span className="t-caption flex size-6 shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand">{i + 1}</span>
              <span className="t-body">
                {a}
                <br />
                {b}
              </span>
            </li>
          ))}
        </ol>
        <div className="flex flex-col gap-1 rounded-xl bg-tile px-3.5 py-3">
          <p className="t-strong">게임이론의 섀플리 값(Shapley value)으로 나눠요</p>
          <p className="t-caption text-sub">
            탄 거리 비례, N분의 1 같은
            <br />
            간단한 규칙도 고를 수 있어요
          </p>
        </div>
      </Card>

      <Button onClick={() => (window.history.length > 1 ? navigate(-1) : navigate(`/t/${code}`))}>확인</Button>
    </Page>
  );
}
