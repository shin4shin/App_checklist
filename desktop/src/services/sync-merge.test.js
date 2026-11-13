import test from 'node:test';
import assert from 'node:assert/strict';
import { shouldApplyRemote, isAppVisible, appDisplayName } from './sync-merge.js';

test('unchanged remote revisions are not reapplied', () => {
  assert.equal(shouldApplyRemote({ updatedAt: 200 }, { updatedAt: 200 }), false);
});

test('unverified package-only records are hidden and package identifiers never become labels', () => {
  const settings = [{ packageName: 'com.old.game', appName: 'com.old.game', appIcon: '', isInstalled: null }];
  assert.equal(isAppVisible('com.old.game', settings), false);
  assert.equal(appDisplayName('com.old.game', settings, []), '앱 정보 확인 중');
  assert.equal(appDisplayName('com.old.game', settings, [{ packageName: 'com.old.game', gameName: '게임 이름' }]), '게임 이름');
});

test('uninstalled apps stay hidden after sync and reappear after reinstall', () => {
  assert.equal(isAppVisible('game', [{ packageName: 'game', isInstalled: false }]), false);
  assert.equal(isAppVisible('game', [{ packageName: 'game', isInstalled: true }]), true);
  assert.equal(isAppVisible('game', []), true);
  assert.equal(isAppVisible('personal', [{ packageName: 'personal', isInstalled: false }]), true);
});
test('late server response preserves a newer local check or uncheck', () => {
  assert.equal(shouldApplyRemote({ done: true, updatedAt: 200 }, { done: false, updatedAt: 100 }), false);
  assert.equal(shouldApplyRemote({ done: false, updatedAt: 200 }, { done: true, updatedAt: 100 }), false);
});
test('newer server changes and new tasks are accepted', () => {
  assert.equal(shouldApplyRemote({ updatedAt: 100 }, { updatedAt: 200 }), true);
  assert.equal(shouldApplyRemote(undefined, { updatedAt: 100 }), true);
});
