import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import postgres from 'postgres';

if (!process.env.SUPABASE_DATABASE_URL || !process.argv[2]) {
  throw new Error('SUPABASE_DATABASE_URL 환경변수와 D1 SQL 내보내기 파일 경로가 필요합니다.');
}
const source = new DatabaseSync(':memory:');
source.exec(readFileSync(process.argv[2], 'utf8'));
const sql = postgres(process.env.SUPABASE_DATABASE_URL, { prepare: false, max: 1, ssl: 'require' });
try {
  await sql.unsafe(readFileSync(new URL('./schema.sql', import.meta.url), 'utf8'));
  await sql.begin(async tx => {
    for (const table of ['users', 'user_tasks', 'user_settings', 'pairing_codes']) {
      const [{ count }] = await tx`select count(*) from ${tx(table)}`;
      if (Number(count) !== 0) throw new Error('대상 DB에 기존 데이터가 있습니다: ' + table);
      const rows = source.prepare('SELECT * FROM ' + table).all();
      for (const row of rows) await tx`insert into ${tx(table)} ${tx(row)}`;
      console.log(table + ': ' + rows.length);
    }
  });
} finally {
  source.close();
  await sql.end();
}
