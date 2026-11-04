import postgres from 'postgres';
const device = 'migration-check-' + crypto.randomUUID();
const db = postgres(process.env.SUPABASE_DATABASE_URL, { prepare: false, max: 1, ssl: 'require' });
let userId;
try {
  const login = await fetch('https://events.yulda.kr/v1/auth/device', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceId: device }) });
  const auth = await login.json();
  if (!login.ok) throw Error('Login HTTP ' + login.status);
  userId = auth.user.id;
  const task = { packageName: 'migration.test', category: 'Daily', taskId: crypto.randomUUID(), title: 'migration probe', done: true, updatedAt: Date.now() };
  const send = async tasks => {
    const response = await fetch('https://events.yulda.kr/v1/sync', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.token }, body: JSON.stringify({ tasks, settings: [], since: 0 }) });
    if (!response.ok) throw Error('Sync HTTP ' + response.status);
    return response.json();
  };
  const result = await send([task]);
  if (!result.serverChanges.tasks.some(t => t.taskId === task.taskId && t.done)) throw Error('Roundtrip mismatch');
  const deleted = await send([{ ...task, isDeleted: true, updatedAt: Date.now() + 1 }]);
  if (!deleted.serverChanges.tasks.some(t => t.taskId === task.taskId && t.isDeleted)) throw Error('Delete mismatch');
  console.log('Production Supabase auth/create/read/delete: PASS');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (userId) await db.begin(async tx => {
    await tx`delete from user_tasks where user_id = ${userId}`;
    await tx`delete from users where id = ${userId}`;
  });
  await db.end();
}
