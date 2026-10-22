// Local stand-in for Supabase while there is no deployed project: the car app and the web call
// POST /rest/v1/rpc/<function> exactly as they would on Supabase (PostgREST), so only the address
// changes later. Errors come back as PostgREST does: HTTP 400 with { code, message, details, hint }.
import http from 'node:http';
import os from 'node:os';
import { pathToFileURL } from 'node:url';
import { callRpc, defaultDataDir, granted, openDb } from './db.mjs';

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'apikey, authorization, content-type, x-client-info, prefer, accept-profile, content-profile',
  'Access-Control-Max-Age': '86400',
};

function send(res, status, body) {
  res.writeHead(status, { ...cors, 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

const pgError = (code, message) => ({ code, message, details: null, hint: null });

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const text = Buffer.concat(chunks).toString('utf8').trim();
  return text ? JSON.parse(text) : {};
}

export function createHandler(db, { log = false } = {}) {
  return async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url ?? '/', 'http://localhost');
    let status = 200;
    try {
      if (req.method === 'OPTIONS') {
        status = 204;
        res.writeHead(204, cors);
        return res.end();
      }
      if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
        return send(res, 200, { ok: true, functions: [...granted].sort() });
      }
      const m = url.pathname.match(/^\/rest\/v1\/rpc\/([a-z_][a-z0-9_]*)$/);
      if (!m || req.method !== 'POST') {
        status = 404;
        return send(res, 404, pgError('PGRST125', `Invalid path: ${req.method} ${url.pathname}`));
      }
      const fn = m[1];
      if (!granted.has(fn)) {
        status = 404;
        return send(res, 404, pgError('PGRST202', `Could not find the function public.${fn}`));
      }
      let args;
      try {
        args = await readBody(req);
      } catch {
        status = 400;
        return send(res, 400, pgError('PGRST102', 'Invalid JSON body'));
      }
      if (args === null || typeof args !== 'object' || Array.isArray(args)) {
        status = 400;
        return send(res, 400, pgError('PGRST102', 'The body must be a JSON object of named arguments'));
      }
      try {
        return send(res, 200, await callRpc(db, fn, args));
      } catch (e) {
        // 42883: no function with these argument names (PostgREST answers 404 PGRST202)
        if (e.code === '42883') {
          status = 404;
          return send(res, 404, pgError('PGRST202', `Could not find the function public.${fn} with these arguments`));
        }
        status = 400;
        return send(res, 400, pgError(e.code ?? 'P0001', e.message));
      }
    } finally {
      if (log && req.method !== 'OPTIONS') {
        console.log(`${new Date().toTimeString().slice(0, 8)} ${req.method} ${url.pathname} ${status} ${Date.now() - started}ms`);
      }
    }
  };
}

export async function start({ port = 54321, host = '0.0.0.0', dataDir = defaultDataDir, log = true } = {}) {
  const db = await openDb(dataDir);
  const server = http.createServer(createHandler(db, { log }));
  await new Promise((resolve) => server.listen(port, host, resolve));
  return { server, db, port: server.address().port };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const port = Number(process.env.PORT ?? 54321);
  const { db } = await start({ port, host: process.env.HOST ?? '0.0.0.0' });
  const lan = Object.values(os.networkInterfaces()).flat()
    .filter((a) => a && a.family === 'IPv4' && !a.internal).map((a) => a.address);
  console.log(`페어풀 개발 서버: http://localhost:${port}/rest/v1/rpc/<함수 이름>`);
  console.log(`  안드로이드 에뮬레이터: http://10.0.2.2:${port}`);
  for (const ip of lan) console.log(`  같은 와이파이의 기기: http://${ip}:${port}`);
  console.log(`  데이터: ${defaultDataDir} (초기화와 시연 데이터: npm run reset)`);
  const shutdown = async () => {
    await db.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
