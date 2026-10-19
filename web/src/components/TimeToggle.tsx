import { useState } from 'react'
import s from './TimeToggle.module.css'

export type TimeMode = 'clock' | 'elapsed'
const KEY = 'earshot.timeMode'

/** Clock or elapsed time for transcript lines; remembered in this browser only. */
export function useTimeMode(): [TimeMode, (m: TimeMode) => void] {
  const [mode, setMode] = useState<TimeMode>(() => {
    try { return localStorage.getItem(KEY) === 'elapsed' ? 'elapsed' : 'clock' } catch { return 'clock' }
  })
  return [mode, m => { setMode(m); try { localStorage.setItem(KEY, m) } catch { /* private window: not remembered */ } }]
}

export default function TimeToggle({ mode, onChange }: { mode: TimeMode; onChange: (m: TimeMode) => void }) {
  return (
    <span className={s.toggle} role="group" aria-label="Transcript times">
      <button type="button" className={mode === 'clock' ? s.on : s.off} aria-pressed={mode === 'clock'} onClick={() => onChange('clock')}
        title="Time of day">Clock</button>
      <button type="button" className={mode === 'elapsed' ? s.on : s.off} aria-pressed={mode === 'elapsed'} onClick={() => onChange('elapsed')}
        title="Time since the start, across all parts of a meeting">Elapsed</button>
    </span>
  )
}
