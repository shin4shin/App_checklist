export function shouldApplyRemote(local, remote) {
  return !local || (remote.updatedAt || 0) >= (local.updatedAt || 0);
}
