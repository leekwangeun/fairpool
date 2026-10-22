import { rpc } from './api';

// Secrets live only on this device. The server keeps their hashes.
const KEY = {
  driver: 'fairpool.driverSecret',
  device: 'fairpool.deviceKey',
  admin: 'fairpool.adminSecrets',
  name: 'fairpool.myName',
  demoCar: 'fairpool.demoCar',
};

// Fallback when storage is blocked (private mode): keeps working for this visit only.
const memory: Record<string, string> = {};

function read(key: string): string | null {
  try {
    return localStorage.getItem(key) ?? memory[key] ?? null;
  } catch {
    return memory[key] ?? null;
  }
}

function write(key: string, value: string | null) {
  if (value === null) delete memory[key];
  else memory[key] = value;
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* memory copy above is enough */
  }
}

export function randomKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function adminMap(): Record<string, string> {
  try {
    return JSON.parse(read(KEY.admin) ?? '{}') as Record<string, string>;
  } catch {
    return {};
  }
}

export const store = {
  driverSecret: () => read(KEY.driver),
  setDriverSecret: (s: string | null) => write(KEY.driver, s),

  deviceKey: (): string => {
    let k = read(KEY.device);
    if (!k) {
      k = randomKey();
      write(KEY.device, k);
    }
    return k;
  },

  adminSecret: (code: string): string | null => adminMap()[code.toUpperCase()] ?? null,
  setAdminSecret: (code: string, secret: string) => {
    const map = adminMap();
    map[code.toUpperCase()] = secret;
    write(KEY.admin, JSON.stringify(map));
  },

  myName: () => read(KEY.name) ?? '',
  setMyName: (n: string) => write(KEY.name, n),
  // demo mode: a new driver on this phone gets a new stand-in car
  forgetDemoCar: () => write(KEY.demoCar, null),
};

// Demo mode only: trips are made with a car's secret, so the pages standing in for the car screen use a
// stand-in car linked to this driver.
export async function demoCarSecret(): Promise<string> {
  const saved = read(KEY.demoCar);
  if (saved) return saved;
  const car = await rpc<{ vehicle_secret: string; link_code: string }>('create_vehicle_link', {});
  if (store.driverSecret()) {
    await rpc('claim_vehicle_link', { p_link_code: car.link_code, p_driver_secret: store.driverSecret() });
  }
  write(KEY.demoCar, car.vehicle_secret);
  return car.vehicle_secret;
}

export const baseUrl = (): string =>
  ((import.meta.env.VITE_PUBLIC_BASE_URL as string | undefined) || window.location.origin).replace(/\/$/, '');

export const tripLink = (code: string) => `${baseUrl()}/t/${code}`;
export const carpoolLink = (code: string) => `${baseUrl()}/c/${code}`;
export const settleLink = (code: string) => `${baseUrl()}/s/${code}`;
