import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../App.jsx', import.meta.url), 'utf8');
const saveEdit = source.slice(source.indexOf('  const saveEdit ='), source.indexOf('  const deleteTask ='));

function editTask(category, resetMinute, editTime, originalEditTime = '2026-11-05T04:00') {
  const task = { packageName: 'personal', category, taskId: 'test', title: 'Before',
    resetMinute, deadline: new Date(originalEditTime).getTime() };
  let saved;
  runInNewContext(`${saveEdit}\nsaveEdit({ preventDefault() {} });`, {
    editingTask: task, editTitle: 'After', editTime, originalEditTime,
    setTasks: update => { saved = update([task])[0]; },
    setEditingTask() {},
  });
  return saved;
}

test('personal schedule preserves selected time even with a 4 AM reset', () => {
  for (const time of ['2026-11-05T15:37', '2026-11-06T00:00', '2026-11-06T23:59']) {
    assert.equal(editTask('Personal', 240, time).deadline, new Date(time).getTime());
  }
});

test('personal schedule without a reset preserves selected time', () => {
  const time = '2026-11-05T12:45';
  assert.equal(editTask('Personal', -1, time).deadline, new Date(time).getTime());
});

test('title-only edit preserves the deadline', () => {
  const time = '2026-11-05T15:37';
  const saved = editTask('Personal', 240, time, time);
  assert.equal(saved.deadline, new Date(time).getTime());
  assert.equal(saved.title, 'After');
});

test('event reset time behavior is preserved', () => {
  assert.equal(editTask('Event', 240, '2026-11-06T15:37').deadline,
    new Date('2026-11-06T04:00').getTime());
});
