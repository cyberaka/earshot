import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, nearest, fmtDuration, speakerColor, todayLocal, type ConversationDetail, type DayView, type Person, type SpeakerView } from '../api'
import AskBox from '../components/AskBox'
import ui from '../components/ui.module.css'
import s from './Today.module.css'

function shiftDate(date: string, days: number) {
  const d = new Date(date + 'T12:00:00')
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

function longDate(date: string) {
  return new Date(date + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}

export default function Today() {
  const params = useParams()
  const navigate = useNavigate()
  const [latest, setLatest] = useState<string | null>(null)
  const date = params.date ?? latest ?? todayLocal()
  const [day, setDay] = useState<DayView | null>(null)
  const [showAmbient, setShowAmbient] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const selected = params.conversationId ? Number(params.conversationId) : null
  const [pendingSeek, setPendingSeek] = useState<{ id: number; startS: number } | null>(null)

  // Without a date in the URL, open the most recent day that has notes.
  useEffect(() => {
    if (!params.date) api.days().then(d => setLatest(d[0]?.local_date ?? todayLocal())).catch(() => setLatest(todayLocal()))
  }, [params.date])

  const load = useCallback(() => {
    api.day(date).then(setDay).catch(e => setError(String(e)))
  }, [date])
  useEffect(() => { load(); const t = setInterval(load, 8000); return () => clearInterval(t) }, [load])

  const visible = (day?.conversations ?? []).filter(c => showAmbient || (!c.ambient && c.duplicateOfMeetingId === null))
  const firstId = visible[0]?.id ?? null
  const current = selected ?? firstId

  return (
    <>
      <div className={s.head}>
        <div>
          <div className={ui.muted}>Daily notes</div>
          <h1 className={s.title}>{longDate(date)}</h1>
        </div>
        <div className={s.dayNav}>
          <Link className={ui.btn} aria-label="Previous day" to={`/day/${shiftDate(date, -1)}`}>‹</Link>
          <Link className={ui.btn} aria-label="Next day" to={`/day/${shiftDate(date, 1)}`}>›</Link>
          <input type="date" aria-label="Pick a day" className={ui.input} value={date} onChange={e => e.target.value && navigate(`/day/${e.target.value}`)} />
        </div>
        {day && (
          <div className={s.counts}>
            {day.conversations.filter(c => !c.ambient && !c.duplicateOfMeetingId).length} conversations · {day.meetingCount} meeting{day.meetingCount === 1 ? '' : 's'}
            {day.ambientCount > 0 && <> · <button className={s.linkBtn} onClick={() => setShowAmbient(v => !v)}>{day.ambientCount} ambient {showAmbient ? 'shown' : 'hidden'}</button></>}
            {day.exportPath && <> · exported to <span className="mono">notes/{date}.md</span></>}
          </div>
        )}
      </div>

      {error && <div className={ui.error}>{error}</div>}
      <AskBox key={date} scopeType="day" scopeRef={date} placeholder={`Ask about this day, e.g. "What did I promise Ravi?"`}
        onCite={c => { navigate(`/day/${date}/${c.conversationId}`); setPendingSeek({ id: c.conversationId, startS: c.startS }) }} />

      {day && visible.length === 0 && (
        <div className={ui.empty}>No conversations on this day yet. <Link to="/import">Import recorder files</Link> or <Link to="/meeting">start a meeting</Link>.</div>
      )}

      {visible.length > 0 && (
        <div className={s.columns}>
          <section aria-label="Timeline" className={s.timeline}>
            {visible.map(c => {
              const cls = c.id === current ? s.cardSelected : c.ambient ? s.cardAmbient : s.card
              return (
                <Link key={c.id} to={`/day/${date}/${c.id}`} className={cls}>
                  <span className={s.cardTime}><span>{c.startClock} – {c.endClock}</span><span>{fmtDuration(c.durationS)}</span></span>
                  <span className={s.cardTitle}>{c.title}</span>
                  <span className={s.tags}>
                    {c.speakers.slice(0, 4).map(t => {
                      const col = speakerColor(t.key)
                      return <span key={t.label} className={ui.chip} style={{ background: col.bg, color: col.fg }}>{t.label}</span>
                    })}
                    {c.meetingId && <span className={ui.chip} style={{ background: 'var(--accent-soft)', color: 'var(--accent-ink)' }}>Meeting</span>}
                    {c.ambient && <span className={ui.chip} style={{ background: 'var(--warn-soft)', color: 'var(--warn)' }}>Ambient</span>}
                    {c.duplicateOfMeetingId && <span className={ui.chip} style={{ background: 'var(--border-soft)', color: 'var(--ink-2)' }}>Also recorded as meeting</span>}
                    {c.status !== 'done' && <span className={ui.chip} style={{ background: 'var(--border-soft)', color: 'var(--ink-2)' }}>{c.status === 'live' ? 'Live' : 'Processing'}</span>}
                  </span>
                </Link>
              )
            })}
          </section>
          {current && <Conversation key={current} id={current} onChanged={load} seek={pendingSeek?.id === current ? pendingSeek.startS : null} onSeekDone={() => setPendingSeek(null)} />}
        </div>
      )}
    </>
  )
}

function Conversation({ id, onChanged, seek, onSeekDone }: { id: number; onChanged: () => void; seek: number | null; onSeekDone: () => void }) {
  const [d, setD] = useState<ConversationDetail | null>(null)
  const [people, setPeople] = useState<Person[]>([])
  const [naming, setNaming] = useState<SpeakerView | null>(null)
  const [markMe, setMarkMe] = useState<Set<number> | null>(null)
  const [highlight, setHighlight] = useState<number | null>(null)
  const audio = useRef<HTMLAudioElement>(null)
  const rows = useRef<Map<number, HTMLDivElement>>(new Map())

  const load = useCallback(() => { api.conversation(id).then(setD).catch(() => setD(null)) }, [id])
  useEffect(() => { load(); api.people().then(p => setPeople(p.people)).catch(() => {}) }, [load])
  useEffect(() => {
    if (d && d.status !== 'done') { const t = setInterval(load, 5000); return () => clearInterval(t) }
  }, [d, load])

  // Scroll to a cited moment (B-5).
  useEffect(() => {
    if (seek === null || !d) return
    const target = nearest(d.segments, seek)
    if (target) {
      setHighlight(target.id)
      rows.current.get(target.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    onSeekDone()
  }, [seek, d, onSeekDone])

  function play(start: number, end: number) {
    if (!d || !audio.current) return
    audio.current.src = api.audioUrl(d.recordingId, start, end)
    audio.current.play().catch(() => {})
  }

  async function saveMe() {
    if (!markMe || markMe.size === 0) return
    await api.enrollSegments([...markMe])
    setMarkMe(null)
    setTimeout(load, 1500)
  }

  if (!d) return <article className={s.detail}><span className={ui.muted}>Loading…</span></article>

  return (
    <article className={s.detail}>
      <div className={s.detailHead}>
        <h2 className={s.detailTitle}>{d.title}</h2>
        <span className={s.meta}>{d.startClock} – {d.endClock} · {fmtDuration(d.durationS)} · {d.source === 'meeting' ? 'meeting' : 'recorder'}</span>
      </div>

      {d.status !== 'done' && <div className={ui.notice}>{d.status === 'live' ? 'This meeting is live.' : `Processing (${d.stage})…`}</div>}

      {d.speakers.length > 0 && (
        <div className={s.speakers}>
          <span className={ui.muted}>Speakers</span>
          {d.speakers.map(sp => {
            const col = speakerColor(sp.key)
            const unnamed = sp.personId === null && !sp.isMe
            return unnamed
              ? <button key={sp.clusterId} className={s.nameBtn} onClick={() => setNaming(sp)}>{sp.name} · Name this speaker</button>
              : <button key={sp.clusterId} className={ui.chip} style={{ background: col.bg, color: col.fg, border: 0, cursor: 'pointer' }}
                onClick={() => setNaming(sp)} title="Rename or reassign">{sp.name}</button>
          })}
        </div>
      )}
      {naming && <NameSpeaker speaker={naming} people={people} onClose={() => setNaming(null)} onSaved={() => { setNaming(null); load(); onChanged() }} />}

      {d.ambient && (
        <div className={ui.notice}>
          Ambient: you were not detected speaking, so this was not transcribed.{' '}
          <button className={ui.btnSmall} onClick={() => api.transcribeAmbient(d.id).then(load)}>Transcribe anyway</button>
        </div>
      )}

      {(d.summary.length > 0 || d.actionItems.length > 0) && (
        <div className={s.twoCol}>
          {d.summary.length > 0 && (
            <section className={s.col}>
              <h3 className={ui.sectionLabel}>Summary</h3>
              <ul className={s.bullets}>{d.summary.map((b, i) => <li key={i}>{b}</li>)}</ul>
              {d.topics.length > 0 && <div className={s.tags}>{d.topics.map(t => <span key={t} className={ui.chip} style={{ background: 'var(--border-soft)', color: 'var(--ink-2)' }}>{t}</span>)}</div>}
            </section>
          )}
          <section className={s.col}>
            <h3 className={ui.sectionLabel}>Action items</h3>
            {d.actionItems.length === 0 ? <span className={ui.muted}>None</span> : d.actionItems.map(a => (
              <label key={a.id} className={s.action}>
                <input type="checkbox" checked={a.done} onChange={e => api.setActionDone(a.id, e.target.checked).then(load)} />
                <span>{a.owner && <b>{a.owner}: </b>}{a.text}</span>
              </label>
            ))}
          </section>
        </div>
      )}

      {d.segments.length > 0 && (
        <section>
          <div className={s.transcriptHead}>
            <h3 className={ui.sectionLabel}>Transcript</h3>
            {d.source !== 'meeting' && (markMe
              ? <span className={s.markBar}>{markMe.size} selected <button className={ui.btnSmall} onClick={saveMe} disabled={markMe.size === 0}>Save as my voice</button><button className={ui.btnSmall} onClick={() => setMarkMe(null)}>Cancel</button></span>
              : <button className={ui.btnSmall} onClick={() => setMarkMe(new Set())}>Mark lines that are me</button>)}
          </div>
          <audio ref={audio} hidden />
          <div>
            {d.segments.map(seg => {
              const col = speakerColor(seg.key)
              return (
                <div key={seg.id} ref={el => { if (el) rows.current.set(seg.id, el) }} className={highlight === seg.id ? s.lineCited : s.line}>
                  {markMe
                    ? <input type="checkbox" aria-label={`Mark line at ${seg.clock} as me`} className={s.markBox} checked={markMe.has(seg.id)}
                      onChange={e => { const n = new Set(markMe); if (e.target.checked) n.add(seg.id); else n.delete(seg.id); setMarkMe(n) }} />
                    : <button className={s.play} aria-label={`Play from ${seg.clock}`} disabled={!d.hasAudio} onClick={() => play(seg.startS, seg.endS + 0.3)}>
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4l13 8-13 8z" /></svg>
                    </button>}
                  <span className={s.clock}>{seg.clock}</span>
                  <span className={s.who} style={{ color: col.fg }}>{seg.speaker}</span>
                  <span className={s.text}>{seg.text}</span>
                </div>
              )
            })}
          </div>
        </section>
      )}
      <div className={s.footer}>
        <ConfirmButton label="Delete conversation" confirm="Delete audio, transcript and index for good" onConfirm={() => api.deleteConversation(d.id).then(onChanged)} />
      </div>
    </article>
  )
}

/** Two-step delete: the second click is the confirmation (deletes cannot be undone). */
export function ConfirmButton({ label, confirm, onConfirm }: { label: string; confirm: string; onConfirm: () => void }) {
  const [armed, setArmed] = useState(false)
  useEffect(() => { if (armed) { const t = setTimeout(() => setArmed(false), 5000); return () => clearTimeout(t) } }, [armed])
  return armed
    ? <span className={s.markBar}><button className={ui.danger} onClick={onConfirm}>{confirm}</button><button className={ui.btnSmall} onClick={() => setArmed(false)}>Keep</button></span>
    : <button className={ui.danger} onClick={() => setArmed(true)}>{label}</button>
}

function NameSpeaker({ speaker, people, onClose, onSaved }: { speaker: SpeakerView; people: Person[]; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState('')
  const [addToLibrary, setAdd] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save(body: { personId?: number | null; name?: string; me?: boolean }) {
    setBusy(true)
    try { await api.assignCluster(speaker.clusterId, { ...body, addToLibrary }); onSaved() } catch (e) { setError(String(e)); setBusy(false) }
  }
  const others = people.filter(p => !p.is_me)
  return (
    <div className={s.namePanel} role="group" aria-label={`Name ${speaker.name}`}>
      <div className={s.namePanelHead}><b>Who is {speaker.name}?</b><span className={ui.muted}>{Math.round(speaker.speechS)} s of speech</span></div>
      <div className={s.nameRow}>
        {speaker.suggestion && speaker.suggestionPersonId && (
          <button className={ui.btnSmall} disabled={busy} onClick={() => save(people.find(p => p.id === speaker.suggestionPersonId)?.is_me ? { me: true } : { personId: speaker.suggestionPersonId })}>
            Sounds like {speaker.suggestion}
          </button>
        )}
        <button className={ui.btnSmall} disabled={busy} onClick={() => save({ me: true })}>This is me</button>
        {others.slice(0, 8).map(p => <button key={p.id} className={ui.btnSmall} disabled={busy} onClick={() => save({ personId: p.id })}>{p.name}</button>)}
      </div>
      <form className={s.nameRow} onSubmit={e => { e.preventDefault(); if (name.trim()) save({ name: name.trim() }) }}>
        <label className="sr-only" htmlFor="new-name">New name</label>
        <input id="new-name" className={ui.input} placeholder="Type a new name" value={name} onChange={e => setName(e.target.value)} />
        <button className={ui.primary} disabled={busy || !name.trim()}>Save</button>
        <label className={s.check}><input type="checkbox" checked={addToLibrary} onChange={e => setAdd(e.target.checked)} /> Add to voiceprint library</label>
        <button type="button" className={ui.btnSmall} onClick={onClose}>Cancel</button>
      </form>
      {error && <div className={ui.error}>{error}</div>}
    </div>
  )
}
