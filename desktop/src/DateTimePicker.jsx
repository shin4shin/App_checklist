import React, { useState } from 'react';
import TimeNumberInput from './TimeNumberInput';

export default function DateTimePicker({ value, onChange }) {
  const [draft, setDraft] = useState(null);
  const [month, setMonth] = useState('');
  const pad = number => String(number).padStart(2, '0');
  const open = () => {
    const now = new Date();
    const initial = value || `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
    setDraft(initial);
    setMonth(initial.slice(0, 7));
  };
  const year = Number(month.slice(0, 4));
  const monthIndex = Number(month.slice(5)) - 1;
  const offset = new Date(year, monthIndex, 1).getDay();
  const days = new Date(year, monthIndex + 1, 0).getDate();
  const moveMonth = delta => {
    const date = new Date(year, monthIndex + delta, 1);
    setMonth(`${date.getFullYear()}-${pad(date.getMonth() + 1)}`);
  };
  return (
    <>
      <button type="button" onClick={open} className="mt-1 w-full rounded-xl border border-[#30363d] bg-[#21262d] px-3 py-2 text-left text-xs text-white">
        {value ? value.replace('T', ' ') : '날짜 및 시간 선택'}
      </button>
      {draft !== null && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-3" onClick={event => event.stopPropagation()}>
          <section role="dialog" aria-modal="true" aria-label="시간 설정" className="w-full max-w-xs rounded-2xl border border-[#30363d] bg-[#161b22] p-4 text-[#f0f6fc]">
            <div className="mb-3 flex items-center justify-between text-sm">
              <button type="button" aria-label="이전 달" onClick={() => moveMonth(-1)} className="px-3 py-1">‹</button>
              <span>{year}년 {monthIndex + 1}월</span>
              <button type="button" aria-label="다음 달" onClick={() => moveMonth(1)} className="px-3 py-1">›</button>
            </div>
            <div className="grid grid-cols-7 gap-1 text-center text-xs">
              {['일', '월', '화', '수', '목', '금', '토'].map(day => <span key={day} className="py-1 text-[#8b949e]">{day}</span>)}
              {Array.from({ length: offset }, (_, index) => <span key={`empty-${index}`} />)}
              {Array.from({ length: days }, (_, index) => {
                const date = `${month}-${pad(index + 1)}`;
                return <button type="button" key={date} onClick={() => setDraft(date + draft.slice(10))}
                  className={`rounded-lg py-2 ${draft.slice(0, 10) === date ? 'bg-[#2f81f7]' : 'hover:bg-[#30363d]'}`}>{index + 1}</button>;
              })}
            </div>
            <div className="mt-3 flex items-center justify-center gap-2 text-sm">
              <TimeNumberInput label="시" max={23} value={Number(draft.slice(11, 13))} onChange={hour => setDraft(draft.slice(0, 11) + pad(hour) + draft.slice(13))} />
              <span>시</span>
              <TimeNumberInput label="분" max={59} value={Number(draft.slice(14, 16))} onChange={minute => setDraft(draft.slice(0, 14) + pad(minute))} />
              <span>분</span>
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button type="button" onClick={() => setDraft(null)} className="px-3 py-2 text-xs">취소</button>
              <button type="button" onClick={() => { onChange(draft); setDraft(null); }} className="rounded-lg bg-[#2f81f7] px-3 py-2 text-xs text-white">저장</button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
