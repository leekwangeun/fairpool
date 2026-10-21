// Demo mode: the real migration runs in PGlite (Postgres compiled to wasm) and persists in IndexedDB,
// so the pages can be tried without a Supabase project. Not used once Supabase settings are present.
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import migration from '../../supabase/migrations/20261022000000_init.sql?raw';

let ready: Promise<PGlite> | null = null;

// A changed migration gets a fresh demo database instead of running against an old schema.
const version = Array.from(migration).reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 0).toString(36);

function open(): Promise<PGlite> {
  ready ??= (async () => {
    const db = new PGlite(`idb://fairpool-demo-${version}`, { extensions: { pgcrypto } });
    const exists = await db.query<{ ok: boolean }>(`select to_regclass('public.trips') is not null as ok`);
    if (!exists.rows[0].ok) await db.exec(migration);
    return db;
  })();
  return ready;
}

export async function demoRpc(fn: string, args: Record<string, unknown>) {
  if (!/^[a-z_]+$/.test(fn)) throw new Error('bad function name');
  const db = await open();
  const names = Object.keys(args).filter((k) => args[k] !== undefined);
  const params = names.map((n) => {
    const v = args[n];
    return v !== null && typeof v === 'object' ? JSON.stringify(v) : v;
  });
  const sql = `select public.${fn}(${names.map((n, i) => `${n} => $${i + 1}`).join(', ')}) as r`;
  const res = await db.query<{ r: unknown }>(sql, params);
  return res.rows[0].r;
}
