import test from 'node:test';
import assert from 'node:assert/strict';
import { shouldApplyRemote } from './sync-merge.js';
test('late server response preserves a newer local check or uncheck', () => {
  assert.equal(shouldApplyRemote({ done: true, updatedAt: 200 }, { done: false, updatedAt: 100 }), false);
  assert.equal(shouldApplyRemote({ done: false, updatedAt: 200 }, { done: true, updatedAt: 100 }), false);
});
test('newer server changes and new tasks are accepted', () => {
  assert.equal(shouldApplyRemote({ updatedAt: 100 }, { updatedAt: 200 }), true);
  assert.equal(shouldApplyRemote(undefined, { updatedAt: 100 }), true);
});
