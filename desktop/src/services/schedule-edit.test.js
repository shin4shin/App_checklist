import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { eventDeadline } from './event-deadline.js';
import { routinePeriod } from './event-routine.js';

const source = readFileSync(new URL('../App.jsx', import.meta.url), 'utf8');
const saveEdit = source.slice(source.indexOf('  const saveEdit ='), source.indexOf('  const deleteTask ='));

function editTask(category, resetMinute, editTime, originalEditTime = '2026-11-05T04:00', overrides = {}) {
  const task = { packageName: 'personal', category, taskId: 'test', title: 'Before',
    resetMinute, deadline: new Date(originalEditTime).getTime() };
  let saved;
  runInNewContext(`${saveEdit}\nsaveEdit({ preventDefault() {} });`, {
    editingTask: task, editTitle: 'After', editTime, originalEditTime,
    editDays: 1, editHours: 0, eventDeadline, editRoutine: false, routinePeriod, showToast() {},
    originalEditHours: 0,
    appSettings: [{ packageName: task.packageName, dailyResetMinute: 240 }],
    Date: class extends Date { static now() { return new Date('2026-11-05T16:37').getTime(); } },
    setTasks: update => { saved = update([overrides.editingTask || task])[0]; },
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

test('only explicitly fixed event times use the reset clock', () => {
  for (const [reset, expected] of [[-1, '16:00'], [240, '04:00'], [385, '06:25']]) {
    assert.equal(editTask('Event', reset, '').deadline, new Date('2026-11-06T' + expected).getTime());
  }
});

test('unfixed durations keep hours across midnight and fixed durations use the reset clock', () => {
  const now = new Date('2026-11-05T16:37').getTime();
  assert.equal(eventDeadline(now, 1, 2, 240), new Date('2026-11-06T18:00').getTime());
  assert.equal(eventDeadline(now, 0, 8, 240), new Date('2026-11-06T00:00').getTime());
  assert.equal(eventDeadline(now, 1, 8, 240), new Date('2026-11-07T00:00').getTime());
  assert.equal(eventDeadline(now, 0, 2, 240), new Date('2026-11-05T18:00').getTime());
});

test('event edit saves the interval from the previous deadline when routine is selected', () => {
  const original = '2026-11-05T04:00';
  const result = editTask('Event', -1, '', original, { editRoutine: true, editDays: 8 });
  assert.equal(result.routineDays, routinePeriod(new Date(original).getTime(), result.deadline));
});

test('saving a changed event or personal time clears completion while a title-only edit preserves it', () => {
  const original = '2026-11-05T04:00';
  const completed = { packageName: 'personal', category: 'Personal', taskId: 'test', title: 'Before', done: true, deadline: new Date(original).getTime() };
  const personal = editTask('Personal', -1, '2026-11-06T05:00', original, { editingTask: completed });
  assert.equal(personal.done, false);
  assert.equal(editTask('Personal', -1, original, original, { editingTask: completed }).done, true);
  const event = { ...completed, category: 'Event' };
  assert.equal(editTask('Event', -1, '', original, { editingTask: event }).done, false);
  assert.equal(editTask('Event', -1, '', original, { editingTask: event, editDays: 0, editHours: 0, originalEditHours: 0 }).done, true);
});

test('11 days 16 hours stays independent of the default reset clock', () => {
  const now = new Date('2026-11-20T17:37').getTime();
  assert.equal(eventDeadline(now, 11, 16, 180), new Date('2026-12-02T09:00').getTime());
  assert.equal(eventDeadline(now, 11, 16, 180, true), new Date('2026-12-02T03:00').getTime());
});
