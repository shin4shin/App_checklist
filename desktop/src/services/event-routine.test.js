import test from 'node:test';
import assert from 'node:assert/strict';
import { routinePeriod, advanceEventRoutine } from './event-routine.js';
const day = 86400000;
test('routine interval matches mobile whole-day and exact-time formats', () => {
  assert.equal(routinePeriod(day, day * 8), 7);
  assert.equal(routinePeriod(day, day * 3 + 195000), -(day * 2 + 195000));
  assert.throws(() => routinePeriod(0, day));
  assert.throws(() => routinePeriod(day, day));
});
test('routine advances at boundary, skips downtime and clears completion', () => {
  const deadline = new Date(2026, 10, 20, 5).getTime();
  const task = { category: 'Event', deadline, routineDays: 7, done: true, resetMinute: -1 };
  assert.equal(advanceEventRoutine(task, deadline - 1), task);
  assert.equal(advanceEventRoutine(task, deadline).deadline, new Date(2026, 10, 27, 5).getTime());
  assert.equal(advanceEventRoutine(task, new Date(2026, 11, 4, 5).getTime()).deadline, new Date(2026, 11, 11, 5).getTime());
  assert.equal(advanceEventRoutine(task, deadline).done, false);
  const exact = { ...task, routineDays: -(day * 2 + 195000) };
  assert.equal(advanceEventRoutine(exact, deadline).deadline, deadline - exact.routineDays);
  assert.equal(advanceEventRoutine(exact, deadline - exact.routineDays * 3).deadline, deadline - exact.routineDays * 4);
  for (const other of [{ ...task, isDeleted: true }, { ...task, category: 'Personal' }, { ...task, routineDays: 0 }]) assert.equal(advanceEventRoutine(other, deadline), other);
});
