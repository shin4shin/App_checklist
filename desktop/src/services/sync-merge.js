export function shouldApplyRemote(local, remote) {
  return !local || (remote.updatedAt || 0) > (local.updatedAt || 0);
}
export function isAppVisible(packageName, settings) {
  if (packageName === 'personal') return true;
  const setting = settings.find(item => item.packageName === packageName);
  if (setting?.isInstalled === false) return false;
  return !(setting && setting.isInstalled !== true && setting.appName === packageName && !setting.appIcon);
}

export function appDisplayName(packageName, settings, tasks) {
  if (packageName === 'personal') return '개인 계획';
  const names = [settings.find(setting => setting.packageName === packageName)?.appName,
    ...tasks.filter(task => task.packageName === packageName && !task.isDeleted).map(task => task.gameName)];
  return names.find(name => typeof name === 'string' && name.trim() && !/^[a-z][\w]*(?:\.[\w]+)+$/i.test(name.trim())) || '앱 정보 확인 중';
}

