import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, groupTurns, nearest, swapIn, turnBands, fmtDuration, fmtElapsed, speakerColor, todayLocal, type ConversationDetail, type LineProposal, type RetranscribeOptions, type DayView, type Provider, type NoteView, type Person, type SpeakerView } from '../api'
import AskBox from '../components/AskBox'
import SummarizeButton from '../components/Summarize'
import { useHealth } from '../components/Shell'
import { ScreenshotLine } from '../components/Screenshots'
import TimeToggle, { useTimeMode } from '../components/TimeToggle'
import NotesSection, { NoteEditor, NoteItem, NotesButton, NotesDrawer } from '../components/Notes'
import TranscriptCompare from '../components/TranscriptCompare'
import SpeakerBar from '../components/SpeakerBar'
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
  // Ask is an optional AI feature (Settings → Insights); without it the day is transcript and notes.
  const health = useHealth(30_000)
  const features = health?.insights?.features
  const liveNow = health?.activeMeetingId != null
  const askOn = (features?.ask ?? 'off') !== 'off'
  const summaryWith = features?.meeting_summary ?? 'off'
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
    // Today if it has notes, else the most recent earlier day; future-dated imports never hide today.
    if (!params.date) api.days().then(d => {
      const today = todayLocal()
      const past = d.map(x => x.local_date).filter(x => x <= today)
      setLatest(past.includes(today) ? today : past[0] ?? d[0]?.local_date ?? today)
    }).catch(() => setLatest(todayLocal()))
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
      {askOn && <AskBox key={date} scopeType="day" scopeRef={date} placeholder={`Ask about this day, e.g. "What did I promise Ravi?"`}
        onCite={c => { navigate(`/day/${date}/${c.conversationId}`); setPendingSeek({ id: c.conversationId, startS: c.startS }) }} />}

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
          {current && <Conversation summaryWith={summaryWith} liveNow={liveNow} key={current} id={current} onChanged={load} seek={pendingSeek?.id === current ? pendingSeek.startS : null} onSeekDone={() => setPendingSeek(null)} />}
        </div>
      )}
    </>
  )
}

function Conversation({ id, onChanged, seek, onSeekDone, summaryWith, liveNow }: {
  id: number; onChanged: () => void; seek: number | null; onSeekDone: () => void; summaryWith: Provider; liveNow: boolean
}) {
  const [d, setD] = useState<ConversationDetail | null>(null)
  const [people, setPeople] = useState<Person[]>([])
  const [naming, setNaming] = useState<SpeakerView | null>(null)
  const [namingLine, setNamingLine] = useState<{ id: number; ids: number[]; text: string } | null>(null)
  // Lines picked with the checkboxes ("Select lines"): to save as the user's voice, or to give to one speaker.
  const [markMe, setMarkMe] = useState<Set<number> | null>(null)
  const [notesOpen, setNotesOpen] = useState(false)
  const [lastPick, setLastPick] = useState<number | null>(null)
  // A paragraph being corrected (R-4), by the id of its first line.
  const [fixing, setFixing] = useState<{ id: number; mode: 'edit' | 'redo' } | null>(null)
  const [offer, setOffer] = useState<{ wrong: string; fixed: string; id?: number; applied?: number } | null>(null)
  const [highlight, setHighlight] = useState<number | null>(null)
  const [pinTo, setPinTo] = useState<number | null>(null)
  const [timeMode, setTimeMode] = useTimeMode()
  const audio = useRef<HTMLAudioElement>(null)
  const rows = useRef<Map<number, HTMLDivElement>>(new Map())

  const load = useCallback(() => { api.conversation(id).then(setD).catch(() => setD(null)) }, [id])
  const estimateSummary = useCallback(() => api.conversationSummaryEstimate(id), [id])
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

  function jump(at: number) {
    const target = d && nearest(d.segments, at)
    if (!target) return
    setHighlight(target.id)
    rows.current.get(target.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

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

  // Pinned notes sit under the line nearest their moment.
  const pinned = new Map<number, NoteView[]>()
  for (const n of d.notes) {
    const seg = n.anchorS === null ? undefined : nearest(d.segments, n.anchorS)
    if (seg) pinned.set(seg.id, [...(pinned.get(seg.id) ?? []), n])
  }

  return (
    <article className={s.detail}>
      <div className={s.detailHead}>
        <h2 className={s.detailTitle}>{d.title}</h2>
        <span className={s.meta}>{d.startClock} – {d.endClock} · {fmtDuration(d.durationS)} · {d.source === 'meeting' ? 'meeting' : 'recorder'}</span>
        {d.meetingId !== null && (
          <span className={s.meetingLinks}>
            <Link className={ui.btnSmall} to={`/meeting/${d.meetingId}`}>Open meeting</Link>
            {d.status === 'done' && <Link className={ui.btnSmall} to={`/meeting?resume=${d.meetingId}`}>Resume meeting</Link>}
          </span>
        )}
      </div>

      {d.status !== 'done' && <div className={ui.notice}>{d.status === 'live' ? 'This meeting is live.' : `Processing (${d.stage})…`}</div>}

      {d.speakers.length > 0 && (
        <SpeakerBar speakers={d.speakers} unnamedLabel="Name this speaker" onPick={sp => { setNaming(sp); setNamingLine(null) }} />
      )}
      {naming && !namingLine && <NameSpeaker speaker={naming} people={people} onClose={() => setNaming(null)} onSaved={() => { setNaming(null); load(); onChanged() }} />}

      {d.ambient && (
        <div className={ui.notice}>
          Ambient: you were not detected speaking, so this was not transcribed.{' '}
          <button className={ui.btnSmall} onClick={() => api.transcribeAmbient(d.id).then(load)}>Transcribe anyway</button>
        </div>
      )}

      {summaryWith !== 'off' && d.segments.length > 0 && (
        <SummarizeButton provider={summaryWith} estimate={estimateSummary} again={d.summary.length > 0} liveNow={liveNow}
          onRun={() => api.summarizeConversation(d.id).then(() => { load(); onChanged() })} />
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

      {/* With a transcript, notes open from its header (owner, 2026-10-26); without one there is nowhere else for them. */}
      {d.segments.length === 0 && <NotesSection conversationId={d.id} notes={d.notes} onChanged={load} onJump={jump} />}
      {notesOpen && <NotesDrawer notes={d.notes} placeholder="Add a note to this conversation…" onClose={() => setNotesOpen(false)}
        onChanged={load} onJump={jump} onSave={t => api.addNote(d.id, t, null).then(load)} />}

      {d.segments.length > 0 && (
        <section>
          <div className={s.transcriptHead}>
            <h3 className={ui.sectionLabel}>Transcript</h3>
            <TimeToggle mode={timeMode} onChange={setTimeMode} />
            <NotesButton count={d.notes.length} open={notesOpen} onClick={() => setNotesOpen(!notesOpen)} />
            {(markMe
              ? <span className={s.markBar}>{markMe.size} selected
                <button className={ui.btnSmall} disabled={markMe.size === 0} onClick={() => {
                  const ids = d.segments.filter(x => markMe.has(x.id)).map(x => x.id)
                  const first = d.segments.find(x => x.id === ids[0])
                  const sp = d.speakers.find(x => x.clusterId === first?.clusterId) ?? d.speakers[0]
                  if (first && sp) { setNaming(sp); setNamingLine({ id: first.id, ids, text: `${ids.length} line${ids.length === 1 ? '' : 's'} selected` }) }
                }}>Who said these?</button>
                <button className={ui.btnSmall} onClick={saveMe} disabled={markMe.size === 0}>Save as my voice</button>
                <button className={ui.btnSmall} onClick={() => { setMarkMe(null); setLastPick(null) }}>Cancel</button></span>
              : <button className={ui.btnSmall} title="Tick lines, shift-click for a run: give them to one speaker, or save them as your voice"
                onClick={() => setMarkMe(new Set())}>Select lines</button>)}
          </div>
          <TranscriptCompare conversationId={d.id} hasAudio={d.hasAudio} play={play} onChanged={() => { load(); onChanged() }} />
          {offer && (
            <div className={ui.notice}>
              {offer.id === undefined
                ? <>Always change “{offer.wrong}” to “{offer.fixed}” in new transcripts?{' '}
                  <button className={ui.btnSmall} onClick={() => api.addCorrection(offer.wrong, offer.fixed).then(c => setOffer({ ...offer, id: c.id }))}>Always change it</button>{' '}
                  <button className={ui.btnSmall} onClick={() => setOffer(null)}>Just here</button></>
                : offer.applied === undefined
                  ? <>Saved: new transcripts will say “{offer.fixed}”.{' '}
                    <button className={ui.btnSmall} onClick={() => api.applyCorrection(offer.id!).then(r => { setOffer({ ...offer, applied: r.lines }); load(); onChanged() })}>Fix existing transcripts too</button>{' '}
                    <button className={ui.btnSmall} onClick={() => setOffer(null)}>Done</button></>
                  : <>Fixed “{offer.wrong}” in {offer.applied} more line{offer.applied === 1 ? '' : 's'}. Manage corrections in Settings.{' '}
                    <button className={ui.btnSmall} onClick={() => setOffer(null)}>Done</button></>}
            </div>
          )}
          <audio ref={audio} hidden />
          <div>
            {(() => {
              const speakerById = new Map(d.speakers.map(sp => [sp.clusterId, sp]))
              const shotAt = (d.attachments ?? []).map(a => a.anchorS)
              const groups = groupTurns(d.segments, x => x.clusterId ?? x.key,
                (x, i) => i > 0 && shotAt.some(t => t <= x.startS && t > d.segments[i - 1].startS))
              // Shaded by person, not voice group: one person heard as two groups is still one turn.
              const bands = turnBands(groups, g => g[0].key)
              return groups.map((g, i) => {
              const seg = g[0]
              const ids = g.map(x => x.id)
              const text = g.map(x => x.text).join(' ')
              const col = speakerColor(seg.key)
              // Screenshots (R-1) go before the first row that starts after them; the rest after the last row.
              const shots = (d.attachments ?? []).filter(a => a.anchorS <= seg.startS && (i === 0 || a.anchorS > groups[i - 1][0].startS))
              const marked = markMe !== null && ids.every(id => markMe.has(id))
              return (
                <div key={seg.id}>
                {shots.map(a => <ScreenshotLine key={`shot-${a.id}`} shot={a} editable onChanged={load}
                  time={timeMode === 'clock' ? a.clock : fmtElapsed(a.elapsedS)} />)}
                <div ref={el => { if (el) ids.forEach(id => rows.current.set(id, el)) }}
                  className={highlight !== null && ids.includes(highlight) ? s.lineCited : bands[i] ? s.lineBand : s.line}>
                  {markMe
                    ? <input type="checkbox" aria-label={`Select the line at ${seg.clock}`} className={s.markBox} checked={marked}
                      onClick={e => {
                        // Shift-click: everything from the last line picked to this one.
                        const on = !marked, from = e.shiftKey && lastPick !== null ? Math.min(lastPick, i) : i, to = e.shiftKey && lastPick !== null ? Math.max(lastPick, i) : i
                        const n = new Set(markMe)
                        groups.slice(from, to + 1).flat().forEach(x => on ? n.add(x.id) : n.delete(x.id))
                        setMarkMe(n); setLastPick(i)
                      }} readOnly />
                    : <button className={s.play} aria-label={`Play from ${seg.clock}`} disabled={!d.hasAudio} onClick={() => play(seg.startS, g[g.length - 1].endS + 0.3)}>
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4l13 8-13 8z" /></svg>
                    </button>}
                  <span className={s.clock} title={timeMode === 'clock' ? `${fmtElapsed(seg.elapsedS)} from the start` : `at ${seg.clock}`}>
                    {timeMode === 'clock' ? seg.clock : fmtElapsed(seg.elapsedS)}
                  </span>
                  {seg.clusterId !== null && speakerById.get(seg.clusterId) && !markMe
                    ? <button className={s.whoBtn} style={{ color: col.fg }} title="Who said this?"
                      onClick={() => { setNaming(speakerById.get(seg.clusterId!)!); setNamingLine({ id: seg.id, ids, text }) }}>{seg.speaker}</button>
                    : <span className={s.who} style={{ color: col.fg }}>{seg.speaker}</span>}
                  <span className={s.text}>{text}{g.some(x => x.originalText !== null) &&
                    <span className={s.edited} title={`Earshot first wrote: ${g.map(x => x.originalText ?? x.text).join(' ')}`}> · edited</span>}</span>
                  {!markMe && <span className={s.lineTools}>
                    <button className={s.noteBtn} aria-label={`Correct the text at ${seg.clock}`} title="Correct the text"
                      onClick={() => setFixing(fixing?.id === seg.id && fixing.mode === 'edit' ? null : { id: seg.id, mode: 'edit' })}>edit</button>
                    <button className={s.noteBtn} aria-label={`Transcribe again at ${seg.clock}`} title="Hear it again with another model"
                      disabled={!d.hasAudio} onClick={() => setFixing(fixing?.id === seg.id && fixing.mode === 'redo' ? null : { id: seg.id, mode: 'redo' })}>redo</button>
                    <button className={s.noteBtn} aria-label={`Add a note at ${seg.clock}`} title="Add a note here"
                      onClick={() => setPinTo(pinTo === seg.id ? null : seg.id)}>+ note</button>
                  </span>}
                </div>
                {fixing?.id === seg.id && (
                  <div className={s.pinned}>
                    <FixLine key={`${seg.id}-${fixing.mode}`} ids={ids} text={text} mode={fixing.mode} audioS={g[g.length - 1].endS - seg.startS}
                      original={g.length === 1 ? seg.originalText : null}
                      onClose={() => setFixing(null)}
                      onSaved={(before, after) => { setFixing(null); setOffer(swapIn(before, after)); load(); onChanged() }}
                      onReverted={() => { setFixing(null); load(); onChanged() }} />
                  </div>
                )}
                {naming && namingLine?.id === seg.id && (
                  <div className={s.pinned}>
                    <NameSpeaker key={seg.id} speaker={naming} line={namingLine} people={people} onClose={() => { setNaming(null); setNamingLine(null) }}
                      onSaved={() => { setNaming(null); setNamingLine(null); setMarkMe(null); setLastPick(null); load(); onChanged() }} />
                  </div>
                )}
                {ids.flatMap(id => pinned.get(id) ?? []).map(n => <div key={n.id} className={s.pinned}><NoteItem note={n} onChanged={load} /></div>)}
                {pinTo === seg.id && (
                  <div className={s.pinned}>
                    <NoteEditor placeholder={`Note at ${seg.clock}`} autoFocus onCancel={() => setPinTo(null)}
                      onSave={t => api.addNote(d.id, t, seg.startS).then(() => { setPinTo(null); load() })} />
                  </div>
                )}
                </div>
              )
            }) })()}
            {(d.attachments ?? []).filter(a => a.anchorS > d.segments[d.segments.length - 1].startS).map(a =>
              <ScreenshotLine key={`shot-${a.id}`} shot={a} editable onChanged={load} time={timeMode === 'clock' ? a.clock : fmtElapsed(a.elapsedS)} />)}
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

/**
 * Corrects a paragraph (owner request R-4): the user's own text, or another model's proposal kept by
 * the user. Nothing is stored until Save or "Use this"; the paragraph becomes one line.
 */
function FixLine({ ids, text, mode, audioS, original, onClose, onSaved, onReverted }: {
  ids: number[]; text: string; mode: 'edit' | 'redo'; audioS: number; original: string | null
  onClose: () => void; onSaved: (before: string, after: string) => void; onReverted: () => void
}) {
  const [draft, setDraft] = useState(text)
  const [editing, setEditing] = useState(mode === 'edit')
  const [by, setBy] = useState('user')
  const [options, setOptions] = useState<RetranscribeOptions | null>(null)
  const [proposal, setProposal] = useState<LineProposal | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { if (mode === 'redo') api.retranscribeOptions().then(setOptions).catch(e => setError(String(e))) }, [mode])

  async function save(t: string, who: string) {
    setBusy('save'); setError(null)
    try { await api.editLines(ids, t, who); onSaved(text, t) } catch (e) { setError(String(e)); setBusy(null) }
  }
  async function redo(engine: 'local' | 'cloud') {
    setBusy(engine); setError(null); setProposal(null)
    try { setProposal(await api.retranscribeLines(ids, engine)) } catch (e) { setError(String(e)) }
    setBusy(null)
  }
  const cost = options ? Math.max(0.001, (audioS / 60) * options.cloud.usdPerMinute) : 0

  return (
    <div className={s.fixPanel} role="group" aria-label={editing ? 'Correct the text' : 'Transcribe again'}>
      {editing ? (
        <>
          <label className="sr-only" htmlFor={`fix-${ids[0]}`}>Corrected text</label>
          <textarea id={`fix-${ids[0]}`} className={ui.textarea} rows={Math.min(8, Math.max(2, Math.ceil(draft.length / 90)))} autoFocus
            value={draft} onChange={e => { setDraft(e.target.value); setBy('user') }} />
          <div className={s.nameRow}>
            <button className={ui.primary} disabled={busy !== null || !draft.trim() || draft.trim() === text} onClick={() => save(draft.trim(), by)}>Save</button>
            <button className={ui.btnSmall} onClick={onClose}>Cancel</button>
            {original && <button className={ui.btnSmall} disabled={busy !== null} title={original}
              onClick={() => { setBusy('revert'); api.revertLine(ids[0]).then(onReverted).catch(e => { setError(String(e)); setBusy(null) }) }}>Put back Earshot's text</button>}
            {ids.length > 1 && <span className={ui.muted}>Saving makes these {ids.length} lines one line.</span>}
          </div>
        </>
      ) : (
        <>
          <div className={s.nameRow}>
            <span className={ui.muted}>Hear this again ({Math.round(audioS)} s) with:</span>
            <button className={ui.btnSmall} disabled={busy !== null} onClick={() => redo('local')}
              title={options?.local.note}>{busy === 'local' ? 'Listening…' : `${options?.local.model ?? 'Whisper large-v3'} on this Mac`}</button>
            <button className={ui.btnSmall} disabled={busy !== null || !options?.cloud.available} onClick={() => redo('cloud')}
              title={options?.cloud.available ? `Sends these ${Math.round(audioS)} s of audio to OpenAI` : 'Set OPENAI_API_KEY in .env and restart Earshot'}>
              {busy === 'cloud' ? 'Sending…' : `OpenAI ${options?.cloud.model ?? ''}`}</button>
            <button className={ui.btnSmall} onClick={onClose}>Cancel</button>
          </div>
          {options && <span className={ui.muted}>{options.cloud.available
            ? `OpenAI: this paragraph's audio leaves the Mac, about $${cost.toFixed(3)}. This Mac: slower, nothing leaves it.`
            : 'OpenAI: set OPENAI_API_KEY in .env and restart Earshot to use it.'}</span>}
          {proposal && (
            <div className={s.proposal}>
              <span className={ui.muted}>{proposal.model.replace(/^local:/, 'This Mac · ').replace(/^openai:/, 'OpenAI · ')} heard:</span>
              <div>{proposal.text || <i className={ui.muted}>nothing</i>}</div>
              <div className={s.nameRow}>
                <button className={ui.primary} disabled={!proposal.text || busy !== null} onClick={() => save(proposal.text, proposal.model)}>Use this</button>
                <button className={ui.btnSmall} disabled={!proposal.text} onClick={() => { setDraft(proposal.text); setBy(proposal.model); setEditing(true) }}>Edit first</button>
                <button className={ui.btnSmall} onClick={onClose}>Keep current</button>
              </div>
            </div>
          )}
        </>
      )}
      {error && <div className={ui.error}>{error}</div>}
    </div>
  )
}

/**
 * Names a speaker, or with `line`, says who said one line (two similar voices, e.g. siblings on one
 * phone, can share a speaker). A line of an already-named speaker starts on "this line only".
 */
export function NameSpeaker({ speaker, line, people, onClose, onSaved }: {
  speaker: SpeakerView; line?: { id: number; ids?: number[]; text: string } | null; people: Person[]; onClose: () => void; onSaved: () => void
}) {
  const [name, setName] = useState('')
  const [addToLibrary, setAdd] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const named = speaker.personId !== null || speaker.isMe
  const [scope, setScope] = useState<'line' | 'speaker'>(line && named ? 'line' : 'speaker')
  const oneLine = scope === 'line' && !!line
  async function save(body: { personId?: number | null; name?: string; me?: boolean }) {
    setBusy(true)
    try {
      if (oneLine) await api.assignLines(line!.ids ?? [line!.id], { ...body, addToLibrary })
      else await api.assignCluster(speaker.clusterId, { ...body, addToLibrary })
      onSaved()
    } catch (e) { setError(String(e)); setBusy(false) }
  }
  // Moving a line to the speaker it already has changes nothing.
  const others = people.filter(p => !p.is_me && !(speaker.personId === p.id && (oneLine || speaker.recognised)))
  return (
    <div className={s.namePanel} role="group" aria-label={oneLine ? 'Who said this line' : `Name ${speaker.name}`}>
      <div className={s.namePanelHead}>
        <b>{oneLine ? ((line?.ids?.length ?? 1) > 1 ? 'Who said these lines?' : 'Who said this line?') : speaker.recognised ? `Is this ${speaker.name}?` : `Who is ${speaker.name}?`}</b>
        <span className={ui.muted}>{oneLine
          ? `now ${speaker.name}`
          : `${Math.round(speaker.speechS)} s of speech${speaker.recognised && speaker.matchScore !== null ? ` · recognised by voice (${speaker.matchScore.toFixed(2)})` : ''}`}</span>
      </div>
      {line && (
        <>
          <div className={s.nameQuote}>“{line.text}”</div>
          <div className={s.nameRow} role="radiogroup" aria-label="What to change">
            <label className={s.check}><input type="radio" checked={scope === 'line'} onChange={() => setScope('line')} /> This line only</label>
            <label className={s.check}><input type="radio" checked={scope === 'speaker'} onChange={() => setScope('speaker')} /> Every line of {speaker.name}</label>
          </div>
        </>
      )}
      <div className={s.nameRow}>
        {!oneLine && speaker.suggestion && speaker.suggestionPersonId && (
          <button className={ui.btnSmall} disabled={busy} onClick={() => save(people.find(p => p.id === speaker.suggestionPersonId)?.is_me ? { me: true } : { personId: speaker.suggestionPersonId })}>
            Sounds like {speaker.suggestion}
          </button>
        )}
        {!oneLine && speaker.recognised && speaker.personId !== null && (
          <button className={ui.btnSmall} disabled={busy} onClick={() => save({ personId: speaker.personId })}>Yes, it's {speaker.name}</button>
        )}
        {!(oneLine && speaker.isMe) && <button className={ui.btnSmall} disabled={busy} onClick={() => save({ me: true })}>This is me</button>}
        {others.slice(0, 8).map(p => <button key={p.id} className={ui.btnSmall} disabled={busy} onClick={() => save({ personId: p.id })}>{p.name}</button>)}
      </div>
      <form className={s.nameRow} onSubmit={e => { e.preventDefault(); if (name.trim()) save({ name: name.trim() }) }}>
        <label className="sr-only" htmlFor="new-name">New name</label>
        <input id="new-name" className={ui.input} placeholder="Type a new name" value={name} onChange={e => setName(e.target.value)} />
        <button className={ui.primary} disabled={busy || !name.trim()}>Save</button>
        <button type="button" className={ui.btnSmall} onClick={onClose}>Cancel</button>
      </form>
      <label className={s.check}>
        <input type="checkbox" checked={addToLibrary} onChange={e => setAdd(e.target.checked)} />
        <span>Remember this voice <span className={ui.muted}>· recognise them in future meetings and recordings</span></span>
      </label>
      {error && <div className={ui.error}>{error}</div>}
    </div>
  )
}
