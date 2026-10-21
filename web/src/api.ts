// Every server call goes through one of the SQL functions in supabase/migrations.
// Without Supabase settings the same SQL runs in the browser (demo mode).
import { createClient } from '@supabase/supabase-js';

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
export const demoMode = !url || !anonKey;

const supabase = demoMode ? null : createClient(url!, anonKey!, { auth: { persistSession: false } });

const messages: Record<string, string> = {
  trip_not_found: '운행을 찾을 수 없어요. 링크를 다시 확인해 주세요.',
  trip_closed: '이미 끝난 운행이에요.',
  trip_full: '자리가 모두 찼어요.',
  trip_started: '이미 출발한 운행이라 빠질 수 없어요.',
  not_allowed: '이 운행을 관리할 권한이 없어요.',
  name_required: '이름을 입력해 주세요.',
  place_needs_location: '장소는 검색 목록에서 골라 주세요.',
  verify_failed: '이름이나 번호가 맞지 않아요.',
  too_many_attempts: '확인을 너무 많이 시도했어요. 운전자에게 다시 초대해 달라고 해 주세요.',
  link_expired: '연결 코드가 만료됐어요. 차 화면에서 새 QR을 띄워 주세요.',
  carpool_not_found: '즐겨찾기 카풀을 찾을 수 없어요.',
  folder_not_found: '카풀 폴더를 찾을 수 없어요.',
  folder_required: '쌓을 카풀 폴더를 골라 주세요.',
  not_settled: '정산 결과가 오면 고를 수 있어요.',
  batch_locked: '이미 합쳐서 정산을 요청한 운행이라 바꿀 수 없어요.',
  batch_in_progress: '정산 중인 묶음이 끝나면 다시 요청할 수 있어요.',
  nothing_to_settle: '아직 쌓인 운행이 없어요.',
  no_request: '정산 중인 묶음이 없어요.',
};

export class ApiError extends Error {
  constructor(public code: string) {
    super(messages[code] ?? '잠시 후 다시 시도해 주세요.');
  }
}

export async function rpc<T>(fn: string, args: Record<string, unknown> = {}): Promise<T> {
  try {
    if (supabase) {
      const { data, error } = await supabase.rpc(fn, args);
      if (error) throw error;
      return data as T;
    }
    const { demoRpc } = await import('./demoDb');
    return (await demoRpc(fn, args)) as T;
  } catch (e) {
    const raw = e instanceof Error ? e.message : String(e);
    const code = Object.keys(messages).find((k) => raw.includes(k)) ?? 'unknown';
    if (code === 'unknown') console.error(e);
    throw new ApiError(code);
  }
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
};

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
  driver_name: string;
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
  } | null;
  progress: { car: { lat: number; lng: number } | null; stops: Stop[] } | null;
  settlement: Settlement | null;
  settle_mode: SettleMode | null;
  paid: boolean;
  folder: PassengerFolder | null;
  bank: Bank | null;
};

export type Bank = { bank_name: string; account: string; holder: string };
export type SettleMode = 'direct' | 'folder';
// open: 요청 전, requested: 정산 중, done: 정산 완료
export type BatchStatus = 'open' | 'requested' | 'done';

// The folder a trip went into, as one passenger sees it (M5, M6, M6-1).
export type PassengerFolder = {
  name: string;
  status: BatchStatus;
  requested_at: string | null;
  done_at: string | null;
  rides: { date: string; title: string; amount: number; is_this: boolean; from?: string; to?: string; km?: number }[];
  my_total: number;
  payment: { amount: number; trips: number; paid: boolean } | null;
  totals: { total: number; trips: number; people: { name: string; amount: number; trips: number }[]; driver_amount: number } | null;
};

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
    payments: { id: string; name: string; amount: number; trips: number; paid: boolean }[];
  }) | null;
  open: BatchSummary;
  last_done: { done_at: string } | null;
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
  carpool_code: string | null;
  passengers: AdminPassenger[];
  settlement: Settlement | null;
  settle_mode: SettleMode | null;
  folder: { code: string; name: string; status: BatchStatus } | null;
  folders: { code: string; name: string; trips: number; amount: number }[];
  suggested_folder: string | null;
  issues: { name: string | null; message: string; created_at: string }[];
};

export type Driver = {
  display_name: string;
  bank: Bank | null;
  carpools: { code: string; title: string }[];
  trips: { code: string; title: string; status: TripStatus; created_at: string }[];
  vehicles: number;
};

export const ruleName: Record<Rule, string> = {
  shapley: '섀플리 값',
  distance: '탄 거리 비례',
  equal: 'N분의 1',
};
