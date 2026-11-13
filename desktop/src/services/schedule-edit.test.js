import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { eventDeadline } from './event-deadline.js';

const source = readFileSync(new URL('../App.jsx', import.meta.url), 'utf8');
const saveEdit = source.slice(source.indexOf('  const saveEdit ='), source.indexOf('  const deleteTask ='));

function editTask(category, resetMinute, editTime, originalEditTime = '2026-11-05T04:00', overrides = {}) {
  const task = { packageName: 'personal', category, taskId: 'test', title: 'Before',
    resetMinute, deadline: new Date(originalEditTime).getTime() };
  let saved;
  runInNewContext(`${saveEdit}\nsaveEdit({ preventDefault() {} });`, {
    editingTask: task, editTitle: 'After', editTime, originalEditTime,
    editDays: 1, editHours: 0, eventDeadline,
    originalEditHours: 0,
    appSettings: [{ packageName: task.packageName, dailyResetMinute: 240 }],
    Date: class extends Date { static now() { return new Date('2026-11-05T16:37').getTime(); } },
    setTasks: update => { saved = update([task])[0]; },
    setEditingTask() {},
    ...overrides,
  });
  return saved;
}

test('personal schedule preserves selected time even with a 4 AM reset', () => {
  for (const time of ['2026-11-05T15:37', '2026-11-06T00:00', '2026-11-06T23:59']) {
    assert.equal(editTask('Personal', 240, time).deadline, new Date(time).getTime());
  }
});

test('fixed event time applies when an edit stays on the same date', () => {
  const saved = editTask('Event', 300, '', undefined, { editDays: 0, editHours: 2 });
  assert.equal(saved.deadline, new Date('2026-11-05T05:00').getTime());
});

test('retyping or restoring the initial duration preserves the exact deadline', () => {
  const time = '2026-11-05T20:15';
  const saved = editTask('Event', -1, '', time, { editDays: 0, editHours: 3, originalEditHours: 3 });
  assert.equal(saved.deadline, new Date(time).getTime());
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

test('one event day uses the next calendar date and the task or game reset', () => {
  for (const [reset, expected] of [[-1, '04:00'], [240, '04:00'], [385, '06:25']]) {
    assert.equal(editTask('Event', reset, '').deadline, new Date('2026-11-06T' + expected).getTime());
  }
});

test('future dates use their reset time even when extra hours cross midnight', () => {
  const now = new Date('2026-11-05T16:37').getTime();
  assert.equal(eventDeadline(now, 1, 2, 240), new Date('2026-11-06T04:00').getTime());
  assert.equal(eventDeadline(now, 0, 8, 240), new Date('2026-11-06T04:00').getTime());
  assert.equal(eventDeadline(now, 1, 8, 240), new Date('2026-11-07T04:00').getTime());
  assert.equal(eventDeadline(now, 0, 2, 240), new Date('2026-11-05T18:00').getTime());
});
