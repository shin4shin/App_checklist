import React, { useEffect, useState } from 'react';

export default function TimeNumberInput({ value, onChange, max, label }) {
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  return <input type="text" inputMode="numeric" aria-label={label} value={text}
    onFocus={event => event.target.select()}
    onChange={event => {
      const next = event.target.value;
      if (!/^\d*$/.test(next) || Number(next) > max) return;
      setText(next);
      if (next !== '') onChange(Number(next));
    }}
    onBlur={() => setText(String(value))}
    className="w-16 rounded-lg bg-[#21262d] p-2 text-center text-white" />;
}
