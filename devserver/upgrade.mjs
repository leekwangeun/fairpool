// npm run upgrade: apply changed server functions to the existing dev database without a reset, so the car's
// secret, trip codes and data stay. Stop the server first. Table changes still need npm run reset.
import { applyFunctions, defaultDataDir, openDb } from './db.mjs';

const db = await openDb(defaultDataDir);
const n = await applyFunctions(db, defaultDataDir);
await db.close();
console.log(`서버 함수 ${n}개를 다시 적용했어요. 데이터와 비밀값은 그대로예요. 이제 npm start`);
