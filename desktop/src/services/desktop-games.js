export const isDesktopGame = packageName => packageName.startsWith('pc:');

export function mergeGameSettings(settings, games) {
  const result = new Map(settings.map(setting => [setting.packageName, setting]));
  for (const game of games) {
    const setting = result.get(game.packageName);
    result.set(game.packageName, {
      ...setting, packageName: game.packageName,
      appName: setting?.appName && setting.appName !== game.packageName ? setting.appName : game.name,
      appIcon: setting?.appIcon || game.icon,
      isInstalled: true,
    });
  }
  return [...result.values()];
}

export function desktopNameRepairs(settings, games, pending, now) {
  return games.filter(game => isDesktopGame(game.packageName)).flatMap(game => {
    const setting = settings.find(item => item.packageName === game.packageName);
    if (setting?.appName?.trim() && !setting.appName.trim().startsWith('pc:')) return [];
    if (!game.name?.trim() || game.name.trim().startsWith('pc:')) return [];
    const queued = pending.find(item => item.packageName === game.packageName);
    if (queued?.appName?.trim() && !queued.appName.trim().startsWith('pc:')) return [];
    return [{ dailyResetMinute: 240, weeklyResetDay: 2, weeklyResetMinute: 240,
      ...setting, packageName: game.packageName, appName: game.name,
      appIcon: setting?.appIcon || game.icon, updatedAt: Math.max(now, (setting?.updatedAt || 0) + 1) }];
  });
}
