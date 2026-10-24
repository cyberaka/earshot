import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import SummarizeButton from '../components/Summarize'
import { api, LANGUAGES, fmtElapsed, nearest, speakerColor, type AttachmentView, type Citation, type Devices, type MeetingSummary, type MeetingView, type NoteView, type Person, type SegmentView, type SpeakerView } from '../api'
import { Answer, useAsk } from '../components/AskBox'
import { NoteEditor, NoteItem } from '../components/Notes'
import { NameSpeaker } from './Today'
import TimeToggle, { useTimeMode } from '../components/TimeToggle'
import { useHealth } from '../components/Shell'
import { ScreenshotLine, useScreenshotDrop } from '../components/Screenshots'
import ui from '../components/ui.module.css'
import s from './Meeting.module.css'

export default function Meeting() {
  const { id } = useParams()
  return id ? <LiveMeeting key={id} id={Number(id)} /> : <StartMeeting />
}

const MODES = [
  { value: 'mic_system', label: 'Online call', hint: 'Mic = you, system audio (BlackHole) = others. Needs headphones.' },
  { value: 'mic', label: 'In-person', hint: 'Room audio through the mic. Speakers are separated live; click one to name them.' },
  { value: 'system', label: 'Listen only', hint: 'System audio only: remote participants.' },
]

function StartMeeting() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const resumeId = params.get('resume') ? Number(params.get('resume')) : null
  const [resumeTitle, setResumeTitle] = useState<string | null>(null)
  const [recordedS, setRecordedS] = useState<number | null>(null)
  const [devices, setDevices] = useState<Devices | null>(null)
  const [active, setActive] = useState<number | ''>('')
  const [past, setPast] = useState<{ id: number; title: string; started_at: string; status: string; audio_mode: string; parts: number; duration_s: number | null }[]>([])
  const [title, setTitle] = useState('')
  const [mode, setMode] = useState('mic_system')
  const [device, setDevice] = useState('')
  const [micChannel, setMicChannel] = useState(0)
  const [systemChannel, setSystemChannel] = useState(1)
  const [preContext, setPreContext] = useState('')
  const [language, setLanguage] = useState('en+hi')
  const [vocabulary, setVocabulary] = useState('')
  useEffect(() => { api.settings().then(v => { if (typeof v.language === 'string') setLanguage(v.language) }).catch(() => {}) }, [])
  const [replay, setReplay] = useState(false)
  const [micFile, setMicFile] = useState('')
  const [systemFile, setSystemFile] = useState('')
  const [busy, setBusy] = useState(false)
  const health = useHealth(30_000)
  const features = health?.insights?.features
  // The agenda only feeds the optional AI (live summary, Ask); without them it would do nothing.
  const usesAgenda = !!features && (features.live_summary !== 'off' || features.meeting_summary !== 'off' || features.ask !== 'off')
  const cloud = !!health?.insights?.sendsTextOff.length
  // Resuming defaults to the audio source the meeting last used.
  useEffect(() => { if (resumeId) api.meeting(resumeId).then(mv => { setResumeTitle(mv.title); setMode(mv.audio_mode); setRecordedS(mv.parts.reduce((t, p) => t + p.duration_s, 0)) }).catch(() => {}) }, [resumeId])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.preflight().then(p => {
      setDevices(p.devices)
      setActive(p.activeMeetingId)
      // An online call needs mic + system audio: preselect a multi-channel (Aggregate) input if there is one.
      // BlackHole alone has two channels but no mic, so it is never the pick.
      const multi = p.devices.inputs.filter(d => d.channels >= 2 && !/blackhole/i.test(d.name))
      const pick = multi.find(d => /aggregate/i.test(d.name)) ?? multi[0]
      if (pick) setDevice(pick.name)
    }).catch(e => setError(`Capture process unreachable: ${e}`))
    api.meetings().then(setPast).catch(() => {})
  }, [])

  const selected = devices?.inputs.find(d => d.name === (device || devices.default_input))
  const hasMulti = !!devices?.inputs.some(d => d.channels >= 2 && !/blackhole/i.test(d.name))
  // Checked here so Start never opens a device that cannot work (capture refuses it too).
  const channelProblem = !replay && devices && selected && (mode === 'mic_system'
    ? (/blackhole/i.test(selected.name) ? 'blackholeOnly' : selected.channels < 2 ? 'twoChannels' : Math.max(micChannel, systemChannel) >= selected.channels ? 'range' : null)
    : mode === 'mic' && micChannel >= selected.channels ? 'range' : null)
  const blocked = !!channelProblem

  async function upload(file: File) {
    try { const r = await api.meetingContext(file); setPreContext(p => (p ? p + '\n\n' : '') + r.text) } catch (e) { setError(String(e)) }
  }

  async function start(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const body: Record<string, unknown> = { title, audioMode: mode, preContext, language, vocabulary, source: replay ? 'file' : 'device' }
      if (replay) body.files = { mic: micFile, system: systemFile }
      else { body.device = device || null; body.micChannel = micChannel; body.systemChannel = mode === 'system' ? 0 : systemChannel }
      const r = resumeId ? await api.resumeMeeting(resumeId, body) : await api.startMeeting(body)
      navigate(`/meeting/${r.meetingId}`)
    } catch (err) {
      setError(String(err))
      setBusy(false)
    }
  }

  if (active) {
    return <div className={ui.notice}>A meeting is recording. <Link to={`/meeting/${active}`}>Open it</Link></div>
  }

  return (
    <>
      <h1 className={s.pageTitle}>{resumeId ? `Resume “${resumeTitle ?? '…'}”` : 'Start a meeting'}</h1>
      {resumeId && <p className={ui.muted} style={{ margin: 0 }}>Recording continues in the same meeting as a new part. People heard earlier keep their names.</p>}
      {resumeId && recordedS !== null && (
        <div className={ui.notice}>Recorded so far: <b className="mono">{fmtElapsed(recordedS)}</b>. If you are following a video, resume it from about <b className="mono">{fmtElapsed(recordedS)}</b>.</div>
      )}
      <form className={s.setup} onSubmit={start}>
        <section className={ui.card}>
          {!resumeId && (
            <label className={ui.field}>Title (optional)
              <input className={ui.input} value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. Vendor review" />
            </label>
          )}
          <fieldset className={s.modes}>
            <legend className={ui.sectionLabel}>Audio source</legend>
            {MODES.map(m => (
              <label key={m.value} className={mode === m.value ? s.modeOn : s.mode}>
                <input type="radio" name="mode" value={m.value} checked={mode === m.value} onChange={() => setMode(m.value)} />
                <span><b>{m.label}</b><span className={ui.muted}>{m.hint}</span></span>
              </label>
            ))}
          </fieldset>
          {!replay && (
            <div className={s.row}>
              <label className={ui.field}>Input device
                <select className={ui.input} value={device} onChange={e => setDevice(e.target.value)}>
                  <option value="">System default{devices?.default_input ? ` (${devices.default_input})` : ''}</option>
                  {devices?.inputs.map(d => <option key={d.index} value={d.name}>{d.name} ({d.channels} ch)</option>)}
                </select>
              </label>
              {mode === 'mic_system' && (
                <>
                  <label className={ui.field}>Mic channel
                    <input type="number" min={0} max={(selected?.channels ?? 2) - 1} className={ui.input} value={micChannel} onChange={e => setMicChannel(Number(e.target.value))} />
                  </label>
                  <label className={ui.field}>System channel
                    <input type="number" min={0} max={(selected?.channels ?? 2) - 1} className={ui.input} value={systemChannel} onChange={e => setSystemChannel(Number(e.target.value))} />
                  </label>
                </>
              )}
            </div>
          )}
          {channelProblem === 'twoChannels' && (
            <div className={ui.error}>
              “{selected!.name}” has {selected!.channels} input channel; an online call needs two: your mic and the call's audio.{' '}
              {hasMulti
                ? 'Pick the Aggregate Device (mic + BlackHole) above.'
                : 'No such device on this Mac yet: install BlackHole and create an Aggregate Device (mic + BlackHole) as in the setup guide. Until then, use In-person.'}
            </div>
          )}
          {channelProblem === 'blackholeOnly' && (
            <div className={ui.error}>BlackHole carries only the call's audio, not your mic. Create an Aggregate Device (your mic + BlackHole 2ch) in Audio MIDI Setup and pick that.</div>
          )}
          {channelProblem === 'range' && <div className={ui.error}>“{selected!.name}” has {selected!.channels} channel{selected!.channels === 1 ? '' : 's'}: channel numbers start at 0.</div>}
          <label className={s.check}><input type="checkbox" checked={replay} onChange={e => setReplay(e.target.checked)} /> Replay audio files instead of a device (testing)</label>
          {replay && (
            <div className={s.row}>
              {mode !== 'system' && <label className={ui.field}>Mic file (path on this Mac)<input className={ui.input} value={micFile} onChange={e => setMicFile(e.target.value)} placeholder="/path/to/mic.wav" /></label>}
              {mode !== 'mic' && <label className={ui.field}>System file (path on this Mac)<input className={ui.input} value={systemFile} onChange={e => setSystemFile(e.target.value)} placeholder="/path/to/system.wav" /></label>}
            </div>
          )}
          <label className={ui.field}>Language
            <select className={ui.input} value={language} onChange={e => setLanguage(e.target.value)}>
              {LANGUAGES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
            </select>
          </label>
          <label className={ui.field}>Names and terms (optional)
            <input className={ui.input} value={vocabulary} onChange={e => setVocabulary(e.target.value)}
              placeholder="e.g. Mukesh Jain, Abhinav, Hardik, Rapido, Zomato" />
            <span className={ui.muted}>Helps spell people, companies and jargon right. Comma-separated; added to the list in Settings. {resumeId ? 'Leave empty to keep this meeting’s list.' : ''}</span>
          </label>
        </section>
        <section className={ui.card}>
          {!resumeId && usesAgenda && (
            <>
              <label className={ui.field}>Agenda or notes (optional)
                <textarea className={ui.textarea} rows={8} value={preContext} onChange={e => setPreContext(e.target.value)} placeholder="Paste the agenda, one item per line…" />
              </label>
              <label className={ui.field}>Or upload .md, .txt or .pdf
                <input type="file" accept=".md,.txt,.pdf" onChange={e => e.target.files?.[0] && upload(e.target.files[0])} />
              </label>
            </>
          )}
          {error && <div className={ui.error}>{error}</div>}
          <button className={ui.live} disabled={busy || blocked}><span className={s.whiteDot} />{busy ? 'Starting…' : resumeId ? 'Resume recording' : 'Start recording'}</button>
          <span className={ui.muted}>{cloud
            ? 'Audio stays on this Mac. Transcript text goes to your chosen AI provider for the Insights you turned on.'
            : 'Audio and transcript stay on this Mac. The transcript is also written to data/live/current.md as it happens.'}</span>
        </section>
      </form>
      {!resumeId && past.length > 0 && (
        <section className={ui.card}>
          <h2 className={s.h2}>Past meetings</h2>
          <div className={s.pastList}>
            {past.map(m => (
              <div key={m.id} className={s.past}>
                <Link to={`/meeting/${m.id}`} className={s.pastTitle}>{m.title}</Link>
                <span className={ui.muted}>
                  {new Date(m.started_at).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}
                  {m.duration_s ? ` · ${Math.max(1, Math.round(m.duration_s / 60))} min` : ''}
                  {m.parts > 1 ? ` · ${m.parts} parts` : ''}
                  {m.status !== 'done' ? ` · ${m.status}` : ''}
                </span>
                {m.status === 'done' && <Link className={ui.btnSmall} to={`/meeting?resume=${m.id}`}>Resume</Link>}
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}

/** "Resumed 20 Oct, 10:05 · after 1 day" for the divider before a part. */
function resumedLabel(parts: MeetingView['parts'], part: number) {
  const p = parts.find(x => x.part === part)
  const prev = parts.find(x => x.part === part - 1)
  if (!p) return 'Resumed'
  const start = new Date(p.started_at)
  const when = start.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
  if (!prev) return `Resumed ${when}`
  const gapMin = Math.max(0, (start.getTime() - new Date(prev.started_at).getTime()) / 60000 - prev.duration_s / 60)
  const gap = gapMin < 60 ? `${Math.round(gapMin)} min` : gapMin < 1440 ? `${Math.round(gapMin / 60)} h` : `${Math.round(gapMin / 1440)} day${Math.round(gapMin / 1440) === 1 ? '' : 's'}`
  return `Resumed ${when} · after ${gap}`
}

function localDate(t: string | number) {
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

interface LiveStatus {
  lagS: number; asrRtf: number; asrRtfAvg?: number; elapsedS: number; warning: string | null; device: string | null; language?: string | null
  /** Plain-language warning when transcription can't keep up (null when it can). */
  behind?: string | null
  mlMemoryMb?: number | null; transcriptFile?: string; summaryWith?: string | null
}

function gb(mb: number) { return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB` }

/** This meeting's names and terms (R-3); during a meeting the next sentence uses them. */
function VocabularyEditor({ id, initial, live, onDone }: { id: number; initial: string; live: boolean; onDone: () => void }) {
  const [text, setText] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  return (
    <form className={s.vocab} onSubmit={async e => {
      e.preventDefault()
      try { await api.switchMeetingVocabulary(id, text); onDone() } catch (err) { setError(String(err)) }
    }}>
      <label className={ui.field}>Names and terms for this meeting
        <input className={ui.input} value={text} autoFocus onChange={e => setText(e.target.value)}
          placeholder="e.g. Abhinav, Hardik, Sajal, Rapido, Capgemini" />
        <span className={ui.muted}>Comma-separated, on top of the list in Settings. {live ? 'The next sentence uses them; earlier lines stay as they are.' : 'Used when this meeting is resumed.'}</span>
      </label>
      {error && <div className={ui.error}>{error}</div>}
      <div className={s.vocabActions}>
        <button className={ui.btnSmall}>Save</button>
        <button type="button" className={ui.btnSmall} onClick={onDone}>Cancel</button>
      </div>
    </form>
  )
}

/** "45 s", "3 min", for the lag chip. */
function fmtLag(sec: number) {
  return sec < 90 ? `${sec.toFixed(sec < 10 ? 1 : 0)} s` : `${Math.round(sec / 60)} min`
}

/** The chip after Stop: what the finishing pass is doing. */
function finishingLabel(stage?: string | null) {
  if (stage?.startsWith('catching up')) return 'Catching up…'
  return 'Finishing…'
}

function clockOf(sec: number) {
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), x = Math.floor(sec % 60)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(x).padStart(2, '0')}`
}

/** Moves the live recording to another input (speakers -> headset) without stopping the meeting. */
function DeviceSwitch({ id, mode, current, onSwitched, onError }: {
  id: number; mode: string; current: string; onSwitched: (device: string) => void; onError: (e: string) => void
}) {
  const [devices, setDevices] = useState<Devices | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.preflight().then(p => setDevices(p.devices)).catch(() => {}) }, [])
  // An online call needs mic + call audio on one device: only Aggregate-style inputs qualify.
  const names = (devices?.inputs ?? []).filter(d => mode !== 'mic_system' || (d.channels >= 2 && !/blackhole/i.test(d.name))).map(d => d.name)
  if (!names.includes(current)) names.unshift(current)

  async function change(device: string) {
    setBusy(true)
    try { onSwitched((await api.switchMeetingDevice(id, device)).device ?? device) } catch (e) { onError(String(e)) }
    setBusy(false)
  }

  return (
    <label className={s.deviceSwitch} title="Switch the input without stopping, e.g. when you put your headset on">
      <span>{busy ? 'Switching…' : 'Input'}</span>
      <select value={current} disabled={busy || names.length < 2} onChange={e => change(e.target.value)}>
        {names.map(n => <option key={n} value={n}>{n}</option>)}
      </select>
    </label>
  )
}

function LanguageSwitch({ id, current, onSwitched, onError }: {
  id: number; current: string; onSwitched: (language: string) => void; onError: (e: string) => void
}) {
  const [busy, setBusy] = useState(false)
  async function change(language: string) {
    setBusy(true)
    try { onSwitched((await api.switchMeetingLanguage(id, language)).language ?? language) } catch (e) { onError(String(e)) }
    setBusy(false)
  }
  const known = LANGUAGES.some(([v]) => v === current)
  return (
    <label className={s.deviceSwitch} title="Applies from the next sentence; earlier lines stay as they were">
      <span>{busy ? 'Switching…' : 'Language'}</span>
      <select value={current} disabled={busy} onChange={e => change(e.target.value)}>
        {!known && <option value={current}>{current}</option>}
        {LANGUAGES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
      </select>
    </label>
  )
}

function LiveMeeting({ id }: { id: number }) {
  const navigate = useNavigate()
  const [m, setM] = useState<MeetingView | null>(null)
  const [segments, setSegments] = useState<SegmentView[]>([])
  const [notes, setNotes] = useState<NoteView[]>([])
  const [speakers, setSpeakers] = useState<SpeakerView[]>([])
  const [people, setPeople] = useState<Person[]>([])
  const [naming, setNaming] = useState<SpeakerView | null>(null)
  const [namingLine, setNamingLine] = useState<{ id: number; text: string } | null>(null)
  const [summary, setSummary] = useState<MeetingSummary | null>(null)
  const [shots, setShots] = useState<AttachmentView[]>([])
  const [editingNames, setEditingNames] = useState(false)
  const [status, setStatus] = useState<LiveStatus | null>(null)
  const [stopping, setStopping] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [cited, setCited] = useState<Set<number>>(new Set())
  const [q, setQ] = useState('')
  const [discussed, setDiscussed] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  const currentPart = useRef(1)
  const partBase = useRef(0)
  const [timeMode, setTimeMode] = useTimeMode()
  const rows = useRef<Map<number, HTMLDivElement>>(new Map())
  const follow = useRef(true)
  const { items, setItems, busy, run } = useAsk('meeting', String(id))
  const health = useHealth(30_000)
  const askWith = health?.insights?.features.ask ?? 'off'
  const features = health?.insights?.features
  // During a meeting the "during" provider (cloud only); afterwards the "after" one, which may be local.
  const duringWith = features?.live_summary ?? 'off'
  const afterWith = features?.meeting_summary ?? 'off'
  const estimateSummary = useCallback(() => api.meetingSummaryEstimate(id), [id])

  const reload = useCallback(async () => {
    const mv = await api.meeting(id)
    setM(mv)
    currentPart.current = mv.parts.length ? mv.parts[mv.parts.length - 1].part : 1
    partBase.current = mv.parts.slice(0, -1).reduce((t, p) => t + p.duration_s, 0)
    setSummary(mv.summary)
    // A resumed meeting has several parts: join their transcripts, notes and speakers.
    const convs = await Promise.all(mv.parts.map(p => api.conversation(p.conversation_id)))
    setSegments(convs.flatMap((c, i) => c.segments.map(x => ({ ...x, part: mv.parts[i].part }))))
    setNotes(convs.flatMap(c => c.notes))
    setShots(convs.flatMap(c => c.attachments ?? []).sort((a, b) => a.elapsedS - b.elapsedS))
    const latest = convs[convs.length - 1]
    const seen = new Set(latest.speakers.map(x => x.name))
    setSpeakers([...latest.speakers, ...convs.slice(0, -1).flatMap(c => c.speakers).filter(x => !seen.has(x.name) && seen.add(x.name))])
  }, [id])

  useEffect(() => { api.people().then(p => setPeople(p.people)).catch(() => {}) }, [naming])
  const reloadQuietly = useCallback(() => { reload().catch(e => setError(String(e))) }, [reload])
  const drop = useScreenshotDrop(id, reloadQuietly)

  useEffect(() => {
    reload().catch(e => setError(String(e)))
    api.qaHistory('meeting', String(id)).then(h => setItems(h.map(x => ({ question: x.question, answer: x.answer, citations: JSON.parse(x.citations || '[]'), pending: false })))).catch(() => {})
  }, [id, reload, setItems])

  // Live events (B-2, B-3).
  useEffect(() => {
    if (!m?.live) return
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/meetings/${id}`)
    ws.onmessage = ev => {
      const e = JSON.parse(ev.data)
      if (e.type === 'discarded') { navigate('/meeting'); return }
      if (e.type === 'segment') setSegments(prev => [...prev, { ...e.segment, part: currentPart.current, elapsedS: partBase.current + e.segment.startS }])
      else if (e.type === 'speakers') {
        // The live part's speakers, plus earlier parts' speakers not heard again yet.
        const live: SpeakerView[] = e.speakers
        setSpeakers(prev => [...live, ...prev.filter(p => !live.some(n => n.clusterId === p.clusterId || n.name === p.name))])
      }
      else if (e.type === 'relabel') {
        // Regrouping moved some lines to another speaker: update them in place.
        const moved = new Map<number, number>(e.changes.map((c: { segmentId: number; clusterId: number }) => [c.segmentId, c.clusterId]))
        setSegments(prev => prev.map(x => (moved.has(x.id) ? { ...x, clusterId: moved.get(x.id)! } : x)))
      }
      else if (e.type === 'status') setStatus(e)
      else if (e.type === 'summary') setSummary(e.summary)
      else if (e.type === 'stopped') { setStatus(null); reload() }
      else if (e.type === 'warning') setError(e.message)
    }
    return () => ws.close()
  }, [m?.live, id, reload])

  // After Stop, poll until the post-meeting pass is done.
  useEffect(() => {
    if (!m || m.live || m.status === 'done' || m.status === 'failed') return
    const t = setInterval(() => reload().catch(() => {}), 5000)
    return () => clearInterval(t)
  }, [m, reload])

  useEffect(() => {
    if (follow.current && listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [segments])

  function onCite(c: Citation | { startS: number }) {
    const target = nearest(segments, c.startS)
    if (!target) return
    follow.current = false
    setCited(new Set([target.id]))
    rows.current.get(target.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  async function stop() {
    setStopping(true)
    try {
      const r = await api.stopMeeting(id) as { discarded?: boolean }
      if (r.discarded) { navigate('/meeting'); return }
      await reload()
    } catch (e) { setError(String(e)) }
    setStopping(false)
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (q.trim() && !busy) { run(q.trim()); setQ('') }
  }

  if (!m) return <span className={ui.muted}>{error ?? 'Loading…'}</span>
  const speakerById = new Map(speakers.map(sp => [sp.clusterId, sp]))
  const elapsed = status?.elapsedS ?? (m.ended_at ? (new Date(m.ended_at).getTime() - new Date(m.started_at).getTime()) / 1000 : 0)
  const modeLabel = m.audio_mode === 'mic_system' ? 'Mic + System' : m.audio_mode === 'mic' ? 'Mic only' : 'System only'
  const hasAgenda = !!m.pre_context?.trim()

  // Screenshots go before the first line that starts after them.
  const shotsBefore = new Map<number, AttachmentView[]>()
  const shotsAfter: AttachmentView[] = []
  let at = 0
  for (const shot of shots) {
    while (at < segments.length && segments[at].elapsedS < shot.elapsedS) at++
    if (at < segments.length) shotsBefore.set(at, [...(shotsBefore.get(at) ?? []), shot])
    else shotsAfter.push(shot)
  }
  const shotLine = (shot: AttachmentView) => (
    <ScreenshotLine key={`shot-${shot.id}`} shot={shot} editable onChanged={reloadQuietly}
      time={timeMode === 'clock' ? shot.clock : fmtElapsed(shot.elapsedS)} />
  )

  return (
    <div className={drop.dragging ? s.liveWrapDrop : s.liveWrap} {...drop.dropProps}>
      {drop.picker}
      {drop.dragging && <div className={s.dropHint} aria-hidden="true">Drop screenshots to add them to the transcript</div>}
      <header className={s.liveHead}>
        <div className={s.liveTitle}>
          <span className={s.titleText}>{m.title}</span>
          <span className={ui.muted}>{modeLabel}{hasAgenda ? ' · agenda attached' : ''}</span>
        </div>
        <div className={s.liveChips}>
          {m.live
            ? <span className={s.recChip}><span className={s.recDot} /><span className="mono">{clockOf(elapsed)}</span></span>
            : <span className={s.neutralChip}>{m.status === 'done' ? 'Final transcript' : m.status === 'failed' ? 'Failed' : finishingLabel(m.finishing)}</span>}
          {m.live && status?.device && <DeviceSwitch id={m.id} mode={m.audio_mode} current={status.device}
            onSwitched={device => { setStatus(st => st && { ...st, device, warning: null }); setError(null) }} onError={setError} />}
          {m.live && status?.language && <LanguageSwitch id={m.id} current={status.language}
            onSwitched={language => setStatus(st => st && { ...st, language })} onError={setError} />}
          {m.live && status && <span className={status.lagS > 10 ? s.badChip : s.neutralChip} title="How old the newest line is">Lag {fmtLag(status.lagS)}</span>}
          <button type="button" className={ui.btnSmall} onClick={() => setEditingNames(v => !v)}
            title="Names and terms Whisper should spell right">Names &amp; terms</button>
          <button type="button" className={ui.btnSmall} onClick={drop.pick} disabled={drop.busy > 0}
            title="Or paste (⌘V) or drop images anywhere on this page">{drop.busy ? 'Adding…' : 'Add screenshot'}</button>
          {m.live
            ? <button className={ui.dark} onClick={stop} disabled={stopping}><span className={s.stopSq} />{stopping ? 'Stopping…' : 'Stop'}</button>
            : m.status === 'done' && <>
              <Link className={ui.btn} to={`/day/${m.parts[m.parts.length - 1]?.local_date ?? localDate(m.started_at)}/${m.conversation_id}`}>Open in daily notes</Link>
              <Link className={ui.live} to={`/meeting?resume=${m.id}`}><span className={s.whiteDot} />Resume</Link>
            </>}
        </div>
      </header>
      {((m.live && status?.warning) || error) && <div className={ui.error}>{(m.live && status?.warning) || error}</div>}
      {m.live && status?.behind && <div className={ui.notice} role="status">{status.behind}</div>}
      {drop.error && <div className={ui.error}>{drop.error}</div>}
      {editingNames && <VocabularyEditor id={m.id} initial={m.vocabulary ?? ''} live={m.live} onDone={() => { setEditingNames(false); reloadQuietly() }} />}
      {!m.live && m.status === 'processing' && m.finishing?.startsWith('catching up') && (
        <div className={ui.notice} role="status">Transcription was behind when you stopped. The rest is being transcribed from the saved audio ({m.finishing.replace('catching up: ', '')}); lines appear here when it is done. Nothing is lost.</div>
      )}

      <div className={s.liveBody}>
        <section aria-label="Transcript" className={s.transcript}>
          <div className={s.panelHead}>
            <h2 className={s.h2}>{m.live ? 'Live transcript' : 'Transcript'}</h2>
            <TimeToggle mode={timeMode} onChange={setTimeMode} />
          </div>
          {speakers.length > 0 && (
            <div className={s.speakerBar}>
              <span className={ui.muted}>Speakers</span>
              {[...speakers].sort((a, b) => b.speechS - a.speechS).map(sp => {
                const col = speakerColor(sp.key)
                const unnamed = sp.personId === null && !sp.isMe
                return unnamed
                  ? <button key={sp.clusterId} className={s.nameBtn} onClick={() => { setNaming(sp); setNamingLine(null) }}>{sp.name} · Name</button>
                  : <button key={sp.clusterId} className={ui.chip} style={{ background: col.bg, color: col.fg, border: 0, cursor: 'pointer' }}
                    onClick={() => { setNaming(sp); setNamingLine(null) }} title={sp.recognised ? 'Recognised by voice: click to confirm or correct' : 'Rename or reassign'}>{sp.name}{sp.recognised && <span className={ui.byVoice}> · by voice</span>}</button>
              })}
              {m.live && <span className={ui.muted}>Labels settle as people talk; click to name.</span>}
            </div>
          )}
          {naming && !namingLine && (
            <div className={s.namingWrap}>
              <NameSpeaker speaker={naming} people={people} onClose={() => setNaming(null)}
                onSaved={() => { setNaming(null); if (!m.live) reload().catch(() => {}) }} />
            </div>
          )}
          <div className={s.lines} ref={listRef} onScroll={e => {
            const el = e.currentTarget
            follow.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
          }}>
            {segments.length === 0 && <div className={ui.muted}>{m.live ? 'Listening… text appears a few seconds after each sentence.' : 'No transcript.'}</div>}
            {segments.map((seg, i) => {
              // Names come from the speaker list, so naming or regrouping relabels every line at once.
              const sp = seg.clusterId !== null ? speakerById.get(seg.clusterId) : undefined
              const part = (seg as SegmentView & { part?: number }).part ?? 1
              const prevPart = i > 0 ? (segments[i - 1] as SegmentView & { part?: number }).part ?? 1 : part
              const divider = i > 0 && part !== prevPart ? m.parts.find(p => p.part === part) : undefined
              return (
                <div key={seg.id}>
                {shotsBefore.get(i)?.map(shotLine)}
                {divider && <div className={s.partDivider}>{resumedLabel(m.parts, divider.part)}</div>}
                <div ref={el => { if (el) rows.current.set(seg.id, el) }} className={cited.has(seg.id) ? s.lineCited : s.line}>
                  <span className={s.clock} title={timeMode === 'clock' ? `${fmtElapsed(seg.elapsedS)} from the start` : `at ${seg.clock}`}>
                    {timeMode === 'clock' ? seg.clock : fmtElapsed(seg.elapsedS)}
                  </span>
                  {sp
                    ? <button className={s.whoBtn} style={{ color: speakerColor(sp.key).fg }} title="Who said this line?"
                      onClick={() => { setNaming(sp); setNamingLine({ id: seg.id, text: seg.text }) }}>{sp.name}</button>
                    : <span className={s.who} style={{ color: speakerColor(seg.key).fg }}>{seg.speaker}</span>}
                  <span className={s.text}>{seg.text}</span>
                </div>
                {naming && namingLine?.id === seg.id && (
                  <div className={s.lineNaming}>
                    <NameSpeaker key={seg.id} speaker={naming} line={namingLine} people={people} onClose={() => { setNaming(null); setNamingLine(null) }}
                      onSaved={() => { setNaming(null); setNamingLine(null); if (!m.live) reload().catch(() => {}) }} />
                  </div>
                )}
                </div>
              )
            })}
            {shotsAfter.map(shotLine)}
          </div>
          <span className={s.dropNote}>Paste (⌘V) or drop a screenshot anywhere on this page: it is placed in the transcript when it was taken.</span>
          {m.live && status && (
            <div className={s.resources}>
              <span title="Time to transcribe a minute of speech, over the last minute: below 1× keeps up">Transcription speed <b className="mono">{(status.asrRtfAvg ?? status.asrRtf).toFixed(2)}× real time</b></span>
              {status.mlMemoryMb != null && <span title="Speech engine memory, including GPU buffers">Speech engine <b className="mono">{gb(status.mlMemoryMb)}</b></span>}
              <span>Audio on disk <b className="mono">✓</b></span>
              {status.transcriptFile && <span title={status.transcriptFile}>Transcript file <b className="mono">data/live/current.md</b></span>}
            </div>
          )}
        </section>

        <aside className={s.side} aria-label="Meeting notes and optional insights">
          {(summary || duringWith !== 'off' || afterWith !== 'off') && <section className={ui.card}>
            <div className={s.panelHead}>
              <h2 className={s.h2}>Summary</h2>
              {summary?.updatedAt && <span className={ui.muted} title={summary.by}>
                {new Date(summary.updatedAt).toLocaleTimeString()}{summary.lines ? ` · ${summary.lines} lines` : ''}
              </span>}
            </div>
            <SummarizeButton provider={m.live ? (duringWith !== 'off' ? duringWith : afterWith) : afterWith} estimate={estimateSummary}
              again={!!summary} onRun={() => api.summarizeMeeting(id).then(setSummary)}
              held={m.live && duringWith === 'off' && afterWith === 'local' ? 'The summary uses the local model: click Summarize after Stop, so transcription keeps the GPU.' : null} />
            {summary && (
              <>
                <ul className={s.bullets}>{summary.summary.map((b, i) => <li key={i}>{b}</li>)}</ul>
                {summary.topics.length > 0 && <div className={s.tags}>{summary.topics.map(t => <span key={t} className={ui.chip} style={{ background: 'var(--border-soft)', color: 'var(--ink-2)' }}>{t}</span>)}</div>}
                {summary.agenda.length > 0 && (
                  <>
                    <h3 className={ui.sectionLabel}>Agenda</h3>
                    {summary.agenda.map((a, i) => (
                      <div key={i} className={s.agendaRow}>
                        <span className={ui.chip} style={a.state === 'covered' ? { background: 'var(--ok-soft)', color: 'var(--ok)' } : a.state === 'partly' ? { background: 'var(--warn-soft)', color: 'var(--warn)' } : { background: 'var(--border-soft)', color: 'var(--ink-2)' }}>
                          {a.state === 'covered' ? 'Covered' : a.state === 'partly' ? 'Partly' : 'Open'}
                        </span>
                        <span>{a.item}</span>
                      </div>
                    ))}
                  </>
                )}
                {summary.decisions.length > 0 && (
                  <>
                    <h3 className={ui.sectionLabel}>Decisions</h3>
                    {summary.decisions.map((d, i) => (
                      <div key={i} className={s.decision}>
                        <span>{d.text}</span>
                        {d.citations.map((c, j) => <button key={j} type="button" className={ui.cite} onClick={() => onCite(c)}>{c.clock}</button>)}
                      </div>
                    ))}
                  </>
                )}
              </>
            )}
          </section>}

          <section className={ui.card}>
            <h2 className={s.h2}>Your notes</h2>
            {notes.map(n => <NoteItem key={n.id} note={n} onChanged={() => reload().catch(() => {})} onJump={at => onCite({ startS: at })} />)}
            <NoteEditor placeholder={m.live ? 'Note this moment…' : 'Add a note…'} onSave={async t => {
              // While live, a note is pinned to the latest line, the moment the user is reacting to.
              const anchor = m.live && segments.length ? segments[segments.length - 1].startS : null
              await api.addNote(m.conversation_id, t, anchor)
              const conv = await api.conversation(m.conversation_id)
              setNotes(conv.notes)
            }} />
          </section>

          {askWith !== 'off' && <section className={ui.card}>
            <h2 className={s.h2}>Ask</h2>
            {askWith === 'local' && m.live && <span className={ui.muted}>Ask uses the local model, which waits until you stop so transcription keeps the GPU.</span>}
            <form className={s.discussed} onSubmit={e => { e.preventDefault(); if (discussed.trim() && !busy) { run(discussed.trim(), 'was_discussed', `Was ${discussed.trim()} discussed?`); setDiscussed('') } }}>
              <label htmlFor="discussed" className="sr-only">Was X discussed?</label>
              <span className={ui.muted}>Was</span>
              <input id="discussed" className={ui.input} value={discussed} onChange={e => setDiscussed(e.target.value)} placeholder="the refund flow" />
              <span className={ui.muted}>discussed?</span>
              <button className={ui.btnSmall} disabled={busy || !discussed.trim()}>Check</button>
            </form>
            <div className={s.quick}>
              <button className={ui.pill} disabled={busy} onClick={() => run('', 'suggest', 'Suggest a question I could ask')}>Suggest a question</button>
              <button className={ui.pill} disabled={busy} onClick={() => run('', 'last5', 'Summarize the last 5 minutes')}>Last 5 minutes</button>
              <button className={ui.pill} disabled={busy} onClick={() => run('', 'decisions', 'What decisions / action items so far?')}>Decisions so far</button>
              {hasAgenda && <button className={ui.pill} disabled={busy} onClick={() => run('', 'open_agenda', "What's still open from the agenda?")}>Open agenda items</button>}
            </div>
            {items.length > 0 && <div className={s.answers}>{items.map((qa, i) => <Answer key={i} qa={qa} onCite={onCite} />)}</div>}
            <form className={s.askForm} onSubmit={submit}>
              <label htmlFor="ask-live" className="sr-only">Ask about this meeting</label>
              <input id="ask-live" className={ui.input} value={q} onChange={e => setQ(e.target.value)} placeholder="Ask about this meeting…" />
              <button className={ui.primary} disabled={busy || !q.trim()}>Ask</button>
            </form>
          </section>}
        </aside>
      </div>
    </div>
  )
}
