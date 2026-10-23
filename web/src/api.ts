// Every server call goes through one of the SQL functions in supabase/migrations.
// Without Supabase settings the same SQL runs in the browser (demo mode).
import { createClient } from '@supabase/supabase-js';

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
// the local dev server (fairpool/devserver), which answers the same POST /rest/v1/rpc/<fn> as Supabase
const apiUrl = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, '');
export const demoMode = !apiUrl && (!url || !anonKey);

const supabase = demoMode || apiUrl ? null : createClient(url!, anonKey!, { auth: { persistSession: false } });

async function devServerRpc(fn: string, args: Record<string, unknown>) {
  const res = await fetch(`${apiUrl}/rest/v1/rpc/${fn}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(args),
  });
  const body = await res.json();
  if (!res.ok) throw new Error((body as { message?: string }).message ?? res.statusText);
  return body;
}

const messages: Record<string, string> = {
  trip_not_found: '운행을 찾을 수 없어요. 링크를 다시 확인해 주세요.',
  trip_closed: '이미 끝난 운행이에요.',
  join_closed: '이미 출발한 운행이에요. 차가 멈추면 운전자에게 차 화면의 QR을 다시 띄워 달라고 해 주세요.',
  trip_full: '자리가 모두 찼어요.',
  bad_seats: '함께 탈 사람은 1명에서 4명까지예요.',
  not_allowed: '이 운행을 관리할 권한이 없어요.',
  name_required: '이름을 입력해 주세요.',
  place_needs_location: '장소는 검색 목록에서 골라 주세요.',
  verify_failed: '이름이나 번호가 맞지 않아요.',
  too_many_attempts: '확인을 너무 많이 시도했어요. 1시간 뒤에 다시 해 주세요.',
  settle_started: '이미 정산을 마친 사람이 있어 바꿀 수 없어요. 정산 완료 체크를 모두 풀면 다시 바꿀 수 있어요.',
  not_direct: '바로 정산한 운행에서만 정산 완료를 체크할 수 있어요.',
  bad_kind: '문제 종류를 골라 주세요.',
  record_deleted: '탑승 기록을 지운 사람은 정산 완료를 풀 수 없어요.',
  link_expired: '연결 코드가 만료됐어요. 차 화면에서 새 QR을 띄워 주세요.',
  carpool_not_found: '즐겨찾기 카풀을 찾을 수 없어요.',
  folder_not_found: '카풀 폴더를 찾을 수 없어요.',
  folder_required: '쌓을 카풀 폴더를 골라 주세요.',
  not_settled: '정산 결과가 오면 고를 수 있어요.',
  batch_locked: '이미 합쳐서 정산을 요청한 운행이라 바꿀 수 없어요.',
  batch_in_progress: '정산 중인 묶음이 끝나면 다시 요청할 수 있어요.',
  nothing_to_settle: '아직 쌓인 운행이 없어요.',
  no_request: '정산 중인 묶음이 없어요.',
  page_not_found: '정산 페이지를 찾을 수 없어요. 운전자가 정산 요청을 취소했을 수 있어요.',
  record_locked: '탑승이 기록된 뒤에는 정산이 완료될 때까지 지울 수 없고 정정만 할 수 있어요.',
  settlement_not_done: '정산이 완료된 뒤에 탑승 기록을 지울 수 있어요.',
  not_chosen: '바로 정산이나 폴더에 쌓기를 먼저 골라 주세요.',
  phone4_required: '휴대폰 번호 뒤 4자리를 입력해 주세요.',
  bad_phone4: '휴대폰 번호 뒤 4자리는 숫자 4개예요.',
};

export class ApiError extends Error {
  constructor(public code: string) {
    super(messages[code] ?? '잠시 후 다시 시도해 주세요.');
  }
}

export async function rpc<T>(fn: string, args: Record<string, unknown> = {}): Promise<T> {
  let data: unknown;
  try {
    if (apiUrl) {
      data = await devServerRpc(fn, args);
    } else if (supabase) {
      const res = await supabase.rpc(fn, args);
      if (res.error) throw res.error;
      data = res.data;
    } else {
      const { demoRpc } = await import('./demoDb');
      data = await demoRpc(fn, args);
    }
  } catch (e) {
    const raw = e instanceof Error ? e.message : String(e);
    const code = Object.keys(messages).find((k) => raw.includes(k)) ?? 'unknown';
    if (code === 'unknown') console.error(e);
    throw new ApiError(code);
  }
  // failures that must not roll back (a wrong verification is still counted) come back as { error }
  const failed = (data as { error?: unknown } | null)?.error;
  if (typeof failed === 'string') throw new ApiError(failed);
  return data as T;
}

// label: what people call it, area: district level ("서대문구 연희동") kept after the trip,
// address + lat/lng (WGS84): what the car hands to NaviHelper as a waypoint.
export type Place = {
  label: string;
  area?: string | null;
  address?: string | null;
  lat?: number | null;
  lng?: number | null;
};

// Place search for the passenger form. Uses OpenStreetMap search (Photon) until the server-side
// Kakao Local proxy is set up, so no API key ships with the web page.
export async function searchPlaces(query: string): Promise<Place[]> {
  const q = query.trim();
  if (q.length < 2) return [];
  const res = await fetch(
    `https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=6&lat=37.5665&lon=126.978&location_bias_scale=0.3`,
  );
  if (!res.ok) return [];
  const data = (await res.json()) as {
    features: { geometry: { coordinates: [number, number] }; properties: Record<string, string | undefined> }[];
  };
  return data.features
    .filter((f) => f.properties.countrycode === 'KR')
    .map((f) => {
      const p = f.properties;
      // district level as in the designs ("서대문구 연희동"); the city only when nothing finer exists
      const area = [p.county, p.district, p.locality].filter((v, i, all) => v && all.indexOf(v) === i).join(' ') || p.city;
      const address = [p.city ?? p.state, p.county, p.district ?? p.locality, p.street, p.housenumber]
        .filter((v, i, all) => v && all.indexOf(v) === i)
        .join(' ');
      return {
        label: p.name ?? [p.street, p.housenumber].filter(Boolean).join(' '),
        area: area || null,
        address: address || null,
        lng: f.geometry.coordinates[0],
        lat: f.geometry.coordinates[1],
      };
    })
    .filter((p) => p.label);
}

export type Rule = 'shapley' | 'distance' | 'equal';
export type TripStatus = 'preparing' | 'in_progress' | 'settled' | 'cancelled';
export type StopState = 'done' | 'now' | 'next';

export type Share = {
  name: string;
  role?: 'driver' | 'passenger';
  amount: number;
  km?: number;
  from?: string;
  to?: string;
  is_me?: boolean;
  passenger_id?: string | null;
  // what this passenger would have paid alone (optional, sent by the car)
  alone?: number;
};

// M3-1 "어떤 문제가 있나요?"
export type IssueKind = 'place' | 'people' | 'amount' | 'other';
export const issueKinds: { kind: IssueKind; label: string }[] = [
  { kind: 'place', label: '탄 곳이나 내린 곳이 달라요' },
  { kind: 'people', label: '함께 탄 사람이 달라요' },
  { kind: 'amount', label: '금액이 이상해요' },
  { kind: 'other', label: '기타' },
];
export const issueLabel = (kind: IssueKind) => issueKinds.find((k) => k.kind === kind)?.label ?? '기타';
export type Issue = { id: string; kind: IssueKind; name: string | null; message: string | null; created_at: string; resolved_at: string | null };

export type Settlement = {
  rule?: Rule;
  include_driver?: boolean;
  total?: number;
  fuel?: { distance_km: number; efficiency_km_l: number; amount: number };
  toll?: number;
  shares: Share[];
};

export type Stop = {
  kind: 'start' | 'my_pickup' | 'my_dropoff' | 'other_pickup' | 'other_dropoff';
  label?: string;
  time?: string;
  eta_min?: number;
  distance_km?: number;
  state: StopState;
};

export type PublicTrip = {
  code: string;
  title: string;
  origin: string | null;
  status: TripStatus;
  include_driver: boolean;
  rule: Rule;
  seats: number;
  seats_left: number;
  // false once the trip has started, unless the car shows the invite QR again at a stop
  joinable: boolean;
  driver_name: string;
  // made from a favourite (anyone may know); the favourite's link itself only for members
  favourite: boolean;
  carpool_code: string | null;
  passengers: { name: string; is_me: boolean; boarded: boolean; alighted: boolean }[];
  me: {
    name: string;
    pickup: Place | null;
    dropoff: Place | null;
    save_place: boolean;
    estimate: number | null;
    boarded: boolean;
    alighted: boolean;
    place_removed: boolean;
  } | null;
  progress: { car: { lat: number; lng: number } | null; stops: Stop[] } | null;
  settlement: Settlement | null;
  settle_mode: SettleMode | null;
  paid: boolean;
  folder: PassengerFolder | null;
  corrections: number;
  bank: Bank | null;
};

export type Bank = { bank_name: string; account: string; holder: string };
export type SettleMode = 'direct' | 'folder';
// open: 요청 전, requested: 정산 중, done: 정산 완료
export type BatchStatus = 'open' | 'requested' | 'done';

// A trip stacked into a carpool folder, as one passenger sees it (M5). `page` is the settlement page
// made at 합쳐서 정산하기 that this passenger should open instead.
export type PassengerFolder = {
  name: string;
  status: BatchStatus;
  page: string | null;
  rides: { date: string; title: string; amount: number; is_this: boolean }[];
  my_total: number;
};

// The one settlement page of a folder batch (M6, M6-1): only this phone's own rides and sum.
export type SettlePage = {
  code: string;
  name: string;
  status: BatchStatus;
  requested_at: string | null;
  done_at: string | null;
  driver_name: string;
  next_title: string | null;
  me: {
    name: string;
    amount: number;
    trips: number;
    paid: boolean;
    rides: {
      date: string; title: string; amount: number; from?: string; to?: string; km?: number; total: number; driver: number;
      // who rode along and each one's share (M6); places only for this rider
      people: { name: string; amount: number; is_me: boolean }[];
    }[];
  } | null;
  // the batch as a whole ("16회 중 14회", "민호, 지은 23,610원"); only for someone who rode
  batch: { trips: number; total: number; driver_amount: number; others: string[]; others_amount: number } | null;
  bank: Bank | null;
};

// "내 휴대폰으로 보내기" from the car: a settlement the driver passes on from their own phone after
// the driver's name and last 4 digits on M1-1 or the settlement page. Read-only.
export type ShareCard = {
  kind: 'invite' | 'trip' | 'batch';
  code: string;
  title: string;
  date: string;
  settle_mode?: SettleMode | null;
  folder?: string | null;
  origin?: string | null;
  seats?: number;
  seats_left?: number;
  total: number | null;
  people: { name: string; amount: number | null; from?: string | null; to?: string | null; trips?: number; paid: boolean }[];
  rides?: { title: string; date: string; shares: { name: string; amount: number; from?: string | null; to?: string | null }[] }[];
  bank: Bank | null;
};

export type DriverView = { driver_name: string; cards: ShareCard[] };

export type BatchSummary = {
  trips: { code: string; title: string; date: string; total: number; km: number | null;
           shares: { name: string; role: string | null; amount: number }[] }[];
  people: { name: string; amount: number; trips: number }[];
  driver_amount: number;
  receivable: number;
  total: number;
};

export type FolderAdmin = {
  code: string;
  name: string;
  requested: (BatchSummary & {
    requested_at: string;
    code: string;
    issues: Issue[];
    payments: { id: string; name: string; amount: number; trips: number; paid: boolean }[];
  }) | null;
  open: BatchSummary;
  last_done: {
    done_at: string;
    code: string;
    payments: { id: string; name: string; amount: number; trips: number; paid: boolean }[];
  } | null;
};

export type FolderItem = { code: string; name: string; status: BatchStatus; trips: number; amount: number };

export type AdminPassenger = {
  id: string;
  name: string;
  pickup: Place | null;
  dropoff: Place | null;
  seat: string | null;
  save_place: boolean;
  boarded_at: string | null;
  alighted_at: string | null;
  paid_at: string | null;
  place_removed_at: string | null;
  record_deleted_at: string | null;
  settle_done: boolean;
};

export type AdminTrip = {
  code: string;
  title: string;
  origin: Place | null;
  destination: Place | null;
  include_driver: boolean;
  seats: number;
  rule: Rule;
  status: TripStatus;
  join_open_until: string | null;
  carpool_code: string | null;
  passengers: AdminPassenger[];
  settlement: Settlement | null;
  settle_mode: SettleMode | null;
  folder: { code: string; name: string; status: BatchStatus } | null;
  folders: { code: string; name: string; trips: number; amount: number }[];
  suggested_folder: string | null;
  revisions: { total: number | null; reason: string | null; replaced_at: string }[];
  issues: Issue[];
};

export type Driver = {
  display_name: string;
  carpools: { code: string; title: string; members: CarpoolMember[] }[];
  trips: { code: string; title: string; status: TripStatus; created_at: string }[];
  vehicles: number;
};

// A favourite's member and the places kept for its next trip (labels only).
export type CarpoolMember = { id: string; name: string; pickup: string | null; dropoff: string | null };

export const ruleName: Record<Rule, string> = {
  shapley: '공정 정산',
  distance: '탄 거리 비례',
  equal: 'N분의 1',
};
