// npm run reset: delete the local database, make it again from the migration and put the demo data in.
// Stop the server first (the database folder is in use while it runs).
import { rmSync } from 'node:fs';
import { join } from 'node:path';
import { defaultDataDir } from './db.mjs';
import { seedInto } from './seed.mjs';

rmSync(defaultDataDir, { recursive: true, force: true });
const info = await seedInto();
console.log('개발 DB를 새로 만들고 시연 데이터를 넣었어요.');
console.log(`  차 비밀값, 운행 코드: ${join(defaultDataDir, 'seed.json')}`);
console.log(`  출근 카풀 폴더 ${info.folder_code}, 동아리 MT 귀갓길 ${info.trips.mt_return.code}`);
