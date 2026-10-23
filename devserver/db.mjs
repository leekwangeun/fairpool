// The same migration as Supabase, run in PGlite (Postgres compiled to wasm) and kept in a folder on disk.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';

export const migration = readFileSync(
  new URL('../supabase/migrations/20261022000000_init.sql', import.meta.url),
  'utf8',
);
export const migrationHash = createHash('sha256').update(migration).digest('hex').slice(0, 16);

// Only the functions the anon key may call on Supabase: the api array at the end of the migration.
export const granted = new Set(
  [...(migration.match(/api text\[\] := array\[([\s\S]*?)\];/)?.[1] ?? '').matchAll(/'([a-z_]+)'/g)].map((m) => m[1]),
);

export const defaultDataDir = process.env.DATA_DIR ?? fileURLToPath(new URL('./data', import.meta.url));

// dataDir null keeps everything in memory (tests). Otherwise the database lives in <dataDir>/pg and
// survives restarts; <dataDir>/migration.hash tells when the schema changed since it was made.
export async function openDb(dataDir = defaultDataDir) {
  let db;
  if (dataDir === null) {
    db = new PGlite({ extensions: { pgcrypto } });
  } else {
    mkdirSync(dataDir, { recursive: true });
    db = new PGlite(join(dataDir, 'pg'), { extensions: { pgcrypto } });
  }
  const { rows } = await db.query(`select to_regclass('public.trips') is not null as ok`);
  const hashFile = dataDir === null ? null : join(dataDir, 'migration.hash');
  if (!rows[0].ok) {
    await db.exec(migration);
    if (hashFile) writeFileSync(hashFile, migrationHash);
  } else if (hashFile && (!existsSync(hashFile) || readFileSync(hashFile, 'utf8').trim() !== migrationHash)) {
    console.warn('[devserver] 마이그레이션이 바뀌었어요. 새 스키마로 다시 만들려면 npm run reset 을 실행하세요.');
  }
  return db;
}

// npm run upgrade: when only functions changed in the migration, run every `create or replace function`
// again on the existing database (in file order), so the data and the secrets stay.
export async function applyFunctions(db, dataDir = null) {
  const blocks = migration.match(/create or replace function [\s\S]*?\$\$;/g) ?? [];
  for (const sql of blocks) await db.exec(sql);
  if (dataDir !== null) writeFileSync(join(dataDir, 'migration.hash'), migrationHash);
  return blocks.length;
}

// One RPC call with named arguments, as PostgREST does it: select public.fn(a => $1, b => $2).
export async function callRpc(db, fn, args = {}) {
  const names = Object.keys(args).filter((k) => args[k] !== undefined);
  const bad = names.find((n) => !/^[a-z_][a-z0-9_]*$/.test(n));
  if (bad) throw Object.assign(new Error(`bad argument name: ${bad}`), { code: 'PGRST100' });
  const params = names.map((n) => {
    const v = args[n];
    return v !== null && typeof v === 'object' ? JSON.stringify(v) : v;
  });
  const sql = `select public.${fn}(${names.map((n, i) => `${n} => $${i + 1}`).join(', ')}) as r`;
  const res = await db.query(sql, params);
  return res.rows[0].r;
}
