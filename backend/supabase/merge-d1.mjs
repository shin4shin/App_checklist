import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import postgres from 'postgres';
const source = new DatabaseSync(':memory:');
source.exec(readFileSync(process.argv[2], 'utf8'));
const db = postgres(process.env.SUPABASE_DATABASE_URL, { prepare: false, max: 1, ssl: { ca: readFileSync(new URL('./supabase-ca.crt', import.meta.url), 'utf8'), rejectUnauthorized: true } });
const keys = { users: ['id'], user_tasks: ['user_id', 'package_name', 'category', 'task_id'], user_settings: ['user_id', 'package_name'], pairing_codes: ['code'] };
try {
  await db.begin(async tx => {
    for (const [table, primary] of Object.entries(keys)) {
      const rows = source.prepare('SELECT * FROM ' + table).all();
      for (const row of rows) {
        const columns = Object.keys(row);
        const updates = columns.filter(c => !primary.includes(c)).map(c => `${c}=excluded.${c}`).join(',');
        const time = table === 'pairing_codes' ? 'created_at' : 'updated_at';
        await tx.unsafe(`INSERT INTO ${table} (${columns.join(',')}) VALUES (${columns.map((_, i) => '$' + (i + 1)).join(',')}) ON CONFLICT (${primary.join(',')}) DO UPDATE SET ${updates} WHERE excluded.${time} >= ${table}.${time}`, Object.values(row));
      }
      console.log(table + ': source ' + rows.length);
    }
  });
} finally { source.close(); await db.end(); }
