export function eventDeadline(now, days, hours, resetMinute) {
  const target = new Date(now);
  const startDate = target.toDateString();
  target.setDate(target.getDate() + days);
  target.setTime(target.getTime() + hours * 3600000);
  if (target.toDateString() !== startDate) {
    target.setHours(Math.floor(resetMinute / 60), resetMinute % 60, 0, 0);
  } else {
    target.setMinutes(0, 0, 0);
  }
  return target.getTime();
}
