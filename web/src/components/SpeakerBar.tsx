import { useState } from 'react'
import { speakerColor, type SpeakerView } from '../api'
import ui from './ui.module.css'
import s from './SpeakerBar.module.css'

// Unnamed speakers shown before "+N more": the most heard ones.
const UNNAMED_SHOWN = 3

/**
 * A conversation's speakers, kept small (owner, 2026-10-26: the panel was "taking more space than the
 * actual transcript"). A room recording can split one person into many voice groups: a named person is
 * one chip, and only the most heard unnamed speakers show until "+N more". "Each voice group" lists every
 * group on its own, as before, to correct one of them; that list is at most two rows high and scrolls.
 */
export default function SpeakerBar({ speakers, onPick, unnamedLabel = 'Name', hint }: {
  speakers: SpeakerView[]; onPick: (sp: SpeakerView) => void; unnamedLabel?: string; hint?: string
}) {
  const [all, setAll] = useState(false)
  const byHeard = [...speakers].sort((a, b) => b.speechS - a.speechS)
  const unnamed = byHeard.filter(sp => sp.personId === null && !sp.isMe)

  // One chip per person: "Me" and each named person, with their voice groups behind it.
  const people = new Map<string, SpeakerView[]>()
  for (const sp of byHeard) {
    if (sp.personId === null && !sp.isMe) continue
    const k = sp.isMe ? 'me' : `p${sp.personId}`
    people.set(k, [...(people.get(k) ?? []), sp])
  }
  const named = [...people.values()].sort((a, b) => Number(b[0].isMe) - Number(a[0].isMe))
  const split = named.some(g => g.length > 1)

  const chip = (sp: SpeakerView, groups = 1) => {
    const col = speakerColor(sp.key)
    return sp.personId === null && !sp.isMe
      ? <button key={sp.clusterId} className={s.nameBtn} onClick={() => onPick(sp)}>{sp.name} · {unnamedLabel}</button>
      : <button key={sp.clusterId} className={ui.chip} style={{ background: col.bg, color: col.fg, border: 0, cursor: 'pointer' }}
        onClick={() => onPick(sp)}
        title={groups > 1 ? `Heard as ${groups} voice groups: "Each voice group" lists them to correct one` : sp.recognised ? 'Recognised by voice: click to confirm or correct' : 'Rename or reassign'}>
        {sp.name}{groups > 1 && <span className={ui.byVoice}> ×{groups}</span>}{groups === 1 && sp.recognised && <span className={ui.byVoice}> · by voice</span>}
      </button>
  }

  if (all) {
    return (
      <div className={s.bar}>
        <div className={s.allList}>{byHeard.map(sp => chip(sp))}</div>
        <button className={s.toggle} onClick={() => setAll(false)}>Show less</button>
      </div>
    )
  }
  const more = unnamed.length - UNNAMED_SHOWN
  return (
    <div className={s.bar}>
      <div className={s.row}>
        <span className={ui.muted} title={hint}>Speakers</span>
        {/* A person's chip names their most heard voice group; renaming it renames that group. */}
        {named.map(g => chip(g[0], g.length))}
        {unnamed.slice(0, UNNAMED_SHOWN).map(sp => chip(sp))}
        {more > 0 && <button className={s.toggle} onClick={() => setAll(true)}>+{more} more</button>}
        {more <= 0 && split && <button className={s.toggle} onClick={() => setAll(true)}>Each voice group</button>}
      </div>
    </div>
  )
}
