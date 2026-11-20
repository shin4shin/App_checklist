const DAY = 86400000;

export function routinePeriod(previous, next) {
  if (!(previous > 0 && next > previous)) throw new Error('이전 종료 시간보다 늦게 설정해 주세요.');
  const interval = next - previous;
  return interval % DAY === 0 ? interval / DAY : -interval;
}

export function advanceEventRoutine(task, now) {
  if (task.isDeleted || task.category !== 'Event' || !task.routineDays || !(task.deadline > 0) || task.deadline > now) return task;
  let deadline;
  if (task.routineDays < 0) {
    const interval = -task.routineDays;
    deadline = task.deadline + (Math.floor((now - task.deadline) / interval) + 1) * interval;
  } else {
    const original = new Date(task.deadline);
    const current = new Date(now);
    const dateKey = date => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
    const days = Math.floor((dateKey(current) - dateKey(original)) / DAY);
    const next = new Date(original);
    next.setDate(next.getDate() + Math.max(0, Math.floor(days / task.routineDays)) * task.routineDays);
    if (next.getTime() <= now) next.setDate(next.getDate() + task.routineDays);
    deadline = next.getTime();
  }
  if (task.resetMinute >= 0) {
    const fixed = new Date(deadline);
    fixed.setHours(Math.floor(task.resetMinute / 60), task.resetMinute % 60, 0, 0);
    deadline = fixed.getTime();
  }
  return { ...task, deadline, done: false, updatedAt: now };
}
