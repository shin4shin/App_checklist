import test from 'node:test';
import assert from 'node:assert/strict';
import { isDesktopGame, mergeGameSettings, desktopNameRepairs } from './desktop-games.js';

test('PC registrations retain names and icons while sharing reset settings for linked mobile games', () => {
  const settings = [{ packageName: 'mobile.game', appName: '모바일 게임', dailyResetMinute: 300, isInstalled: false }];
  const games = [{ packageName: 'mobile.game', name: '바로가기 이름', icon: 'icon' }, { packageName: 'pc:123', name: 'PC 게임', icon: 'pc-icon' }];
  const merged = mergeGameSettings(settings, games);
  assert.equal(merged[0].appName, '모바일 게임');
  assert.equal(merged[0].dailyResetMinute, 300);
  assert.equal(merged[0].appIcon, 'icon');
  assert.equal(merged[0].isInstalled, true);
  assert.equal(merged[1].appName, 'PC 게임');
  assert.equal(settings[0].isInstalled, false);
  assert.equal(isDesktopGame(merged[1].packageName), true);
  assert.equal(isDesktopGame(merged[0].packageName), false);
});

test('identifier names are repaired from local registration once without uploading shortcut paths', () => {
  const settings = [{ packageName: 'pc:123', appName: 'pc:123', dailyResetMinute: 300, updatedAt: 200 }];
  const games = [{ packageName: 'pc:123', name: '게임 이름', icon: 'icon', id: 'C:\\Games\\game.exe' }];
  const repairs = desktopNameRepairs(settings, games, [], 100);
  assert.equal(repairs[0].appName, '게임 이름');
  assert.equal(repairs[0].dailyResetMinute, 300);
  assert.equal(repairs[0].updatedAt, 201);
  assert.equal(repairs[0].id, undefined);
  assert.deepEqual(desktopNameRepairs(settings, games, repairs, 300), []);
  assert.deepEqual(desktopNameRepairs(repairs, games, [], 300), []);
});
