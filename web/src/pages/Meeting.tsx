import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import SummarizeButton from '../components/Summarize'
import { api, LANGUAGES, callReady, channelLabels, deviceRoles, groupTurns, turnBands, fmtElapsed, nearest, speakerColor, type AttachmentView, type Citation, type Devices, type MeetingSummary, type MeetingView, type NoteView, type Person, type SegmentView, type SpeakerView } from '../api'
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
      // An online call needs mic + call audio: preselect an Aggregate Device that has both right now (Core Audio
      // says what it is made of), a headset one before the built-in mic. An aggregate whose headset is unplugged
      // (only BlackHole left) is never the pick.
      const ready = callReady(p.devices.inputs)
      const pick = ready.find(d => !/macbook|built-in/i.test(d.parts!.find(x => !/blackhole/i.test(x.name))!.name)) ?? ready[0]
        ?? p.devices.inputs.find(d => d.channels >= 2 && !/blackhole/i.test(d.name) && !d.parts)
      if (pick) pickDevice(pick.name, p.devices)
    }).catch(e => setError(`Capture process unreachable: ${e}`))
    api.meetings().then(setPast).catch(() => {})
  }, [])

  /** Selects a device and points the mic and call channels at the right sub-devices (no channel numbers to guess). */
  function pickDevice(name: string, devs: Devices | null = devices) {
    setDevice(name)
    const d = devs?.inputs.find(x => x.name === (name || devs.default_input))
    if (!d) return
    const r = deviceRoles(d)
    setMicChannel(r.mic ?? 0)
    setSystemChannel(r.call ?? (d.channels > 1 && r.mic !== null ? 1 : 0))
  }

  const selected = devices?.inputs.find(d => d.name === (device || devices.default_input))
  const labels = selected ? channelLabels(selected) : []
  const ready = devices ? callReady(devices.inputs) : []
  const readyNames = ready.map(d => `“${d.name}” (${d.parts!.map(p => p.name).join(' + ')})`).join(' or ')
  const roles = selected ? deviceRoles(selected) : { mic: null, call: null }
  // Checked here so Start never opens a device that cannot work (capture refuses it too).
  const channelProblem = !replay && devices && selected && (mode === 'mic_system'
    ? (/blackhole/i.test(selected.name) ? 'blackholeOnly'
      : selected.parts?.length && roles.mic === null ? 'noMic'
        : selected.channels < 2 ? 'twoChannels' : Math.max(micChannel, systemChannel) >= selected.channels ? 'range' : null)
    : (mode === 'mic' ? micChannel : systemChannel) >= selected.channels ? 'range' : null)
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
      else { body.device = device || null; body.micChannel = micChannel; body.systemChannel = systemChannel }
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
                <select className={ui.input} value={device} onChange={e => pickDevice(e.target.value)}>
                  <option value="">System default{devices?.default_input ? ` (${devices.default_input})` : ''}</option>
                  {devices?.inputs.map(d => <option key={d.index} value={d.name}>{d.name} ({d.channels} ch)</option>)}
                </select>
              </label>
              {/* Channels by what they are, not by number (owner, 2026-10-24). */}
              {selected && selected.channels > 1 && mode !== 'system' && (
                <label className={ui.field}>Your mic
                  <select className={ui.input} value={micChannel} onChange={e => setMicChannel(Number(e.target.value))}>
                    {labels.map((l, i) => <option key={i} value={i}>{l}</option>)}
                  </select>
                </label>
              )}
              {selected && selected.channels > 1 && mode !== 'mic' && (
                <label className={ui.field}>Call audio
                  <select className={ui.input} value={systemChannel} onChange={e => setSystemChannel(Number(e.target.value))}>
                    {labels.map((l, i) => <option key={i} value={i}>{l}</option>)}
                  </select>
                </label>
              )}
            </div>
          )}
          {channelProblem === 'twoChannels' && (
            <div className={ui.error}>
              “{selected!.name}” is one microphone; an online call needs your mic and the call's audio together.{' '}
              {ready.length
                ? <>Pick {readyNames}.</>
                : 'No such device on this Mac yet: install BlackHole and create an Aggregate Device (mic + BlackHole) as in the setup guide.'}
              {' '}To record only this mic, choose In-person or Listen only.
            </div>
          )}
          {channelProblem === 'noMic' && (
            <div className={ui.error}>
              “{selected!.name}” has no microphone right now, only {selected!.parts!.map(p => p.name).join(' + ')}: is its headset unplugged?{' '}
              {ready.length ? <>Pick {readyNames}.</> : 'Plug the headset in, or use In-person.'}
            </div>
          )}
          {!channelProblem && mode === 'mic_system' && selected?.parts && roles.mic !== null && roles.call !== null
            && (micChannel === systemChannel || labels[micChannel]?.match(/blackhole/i) || !labels[systemChannel]?.match(/blackhole/i)) && (
            <div className={ui.notice}>Check the channels: your mic is usually “{labels[roles.mic]}” and the call audio “{labels[roles.call]}”.</div>
          )}
          {channelProblem === 'blackholeOnly' && (
            <div className={ui.error}>BlackHole carries only the call's audio, not your mic. Create an Aggregate Device (your mic + BlackHole 2ch) in Audio MIDI Setup and pick that.</div>
          )}
          {channelProblem === 'range' && <div className={ui.error}>“{selected!.name}” has {selected!.channels} channel{selected!.channels === 1 ? '' : 's'}: channel numbers start at 0.</div>}
          <label className={s.check}><input type="checkbox" checked={replay} onChange={e => setReplay(e.target.checked)} /> Replay audio files instead of a device (testing)</label>
          {replay && (
            <div className={s.row}>
              {mode !== 'system' && <ReplayFile label="Mic file" value={micFile} onChange={setMicFile} onError={setError} />}
              {mode !== 'mic' && <ReplayFile label={mode === 'system' ? 'Audio or video file' : 'System (call) file'} value={systemFile} onChange={setSystemFile} onError={setError} />}
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
  if (stage === 'transcribe') return 'Re-transcribing…'
  return 'Finishing…'
}

/**
 * A file for "Replay audio files": drop it here or pick it, and it is uploaded to Earshot on this Mac
 * (browsers never give a page a file's path); or paste a path (Finder: ⌥⌘C copies one).
 */
function ReplayFile({ label, value, onChange, onError }: { label: string; value: string; onChange: (p: string) => void; onError: (e: string) => void }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [over, setOver] = useState(false)
  const pick = useRef<HTMLInputElement>(null)
  async function take(f: File | undefined) {
    if (!f) return
    setBusy(f.name)
    try { onChange((await api.replayFile(f)).path) } catch (e) { onError(e instanceof Error ? e.message : String(e)) }
    setBusy(null)
  }
  return (
    <label className={ui.field} style={{ flex: '1 1 100%', minWidth: 0 }} onDragOver={e => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
      onDrop={e => { e.preventDefault(); setOver(false); take(e.dataTransfer.files[0]) }}>
      {label}
      <span style={{ display: 'flex', gap: 8 }}>
        <input className={ui.input} style={{ flex: 1, minWidth: 0, ...(over ? { outline: '2px dashed var(--accent)' } : {}) }} value={busy ? `Uploading ${busy}…` : value}
          disabled={!!busy} onChange={e => onChange(e.target.value)} placeholder="Drop a file here, or paste its path" />
        <button type="button" className={ui.btnSmall} disabled={!!busy} onClick={() => pick.current?.click()}>Choose…</button>
      </span>
      <input ref={pick} type="file" accept="audio/*,video/*,.flac,.m4a,.mp4" hidden onChange={e => { take(e.target.files?.[0]); e.target.value = '' }} />
      <span className={ui.muted}>WAV, FLAC, MP3, M4A or a Zoom MP4; it is copied into Earshot's data folder.</span>
    </label>
  )
}

/** The live transcript file's full path, to copy into the agent that reads it (Earshot's main output). */
function TranscriptFile({ path, live }: { path: string; live: boolean }) {
  const [copied, setCopied] = useState(false)
  async function copy() {
    try { await navigator.clipboard.writeText(path); setCopied(true); setTimeout(() => setCopied(false), 2000) } catch { /* select the text instead */ }
  }
  return (
    <div className={s.fileBar}>
      <span>{live ? 'Live transcript file' : 'Transcript file'}</span>
      <span className={s.filePath}>{path}</span>
      <button type="button" className={ui.btnSmall} onClick={copy} aria-live="polite">{copied ? 'Copied' : 'Copy path'}</button>
      {live && <span className={ui.muted}>Updated as people speak; <span className="mono">current.md</span> in the same folder always holds the meeting being recorded.</span>}
    </div>
  )
}

/**
 * A full pass from the saved audio, only when the user clicks (owner rule, 2026-10-24). Two steps,
 * because it replaces the transcript: lines moved by hand are redone, names carry over by voice.
 */
function Retranscribe({ id, minutes, otherLive, onQueued, onError }: {
  id: number; minutes: number; otherLive: boolean; onQueued: () => void; onError: (e: string) => void
}) {
  const [armed, setArmed] = useState(false)
  const [busy, setBusy] = useState(false)
  if (!armed) return <button type="button" className={ui.btnSmall} onClick={() => setArmed(true)}
    title="A fuller pass over the saved audio: diarization and transcription of whole files, with more context than live">Re-transcribe</button>
  return (
    <div className={ui.notice} role="dialog" aria-label="Re-transcribe this meeting"
      style={{ flexBasis: '100%', display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'flex-start' }}>
      <div>
        <b>Re-transcribe from the saved audio?</b> A fuller pass: speakers and words over whole files, with more context than live.
        It uses the CPU and GPU for roughly {Math.max(1, Math.round(minutes / 2))} min.{' '}
        {otherLive
          ? <b style={{ color: 'var(--warn)' }}>A meeting is live now: this starts at once and shares the GPU with it, which may slow its live transcript.</b>
          : 'If you start a meeting, it stops at once and carries on after that meeting.'}{' '}
        Your current transcript stays until the new one is ready, then is replaced: speaker names carry over by voice, lines you moved by hand are redone.
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" className={ui.primary} disabled={busy} onClick={async () => {
          setBusy(true)
          try { await api.retranscribeMeeting(id); setArmed(false); onQueued() } catch (e) { onError(e instanceof Error ? e.message : String(e)) }
          setBusy(false)
        }}>{busy ? 'Queuing…' : 'Re-transcribe now'}</button>
        <button type="button" className={ui.btnSmall} onClick={() => setArmed(false)}>Cancel</button>
      </div>
    </div>
  )
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
  // An online call needs mic + call audio on one device: only aggregates that have both right now qualify.
  const names = (devices?.inputs ?? []).filter(d => mode !== 'mic_system'
    || (d.parts ? callReady([d]).length > 0 : d.channels >= 2 && !/blackhole/i.test(d.name))).map(d => d.name)
  if (!names.includes(current)) names.unshift(current)

  async function change(device: string) {
    setBusy(true)
    // The mic and call channels follow what the new device is made of (e.g. Aggregate Mac: mic 0, BlackHole 1).
    const d = devices?.inputs.find(x => x.name === device)
    const r = d ? deviceRoles(d) : { mic: null, call: null }
    const call = r.call ?? (d && d.channels > 1 && r.mic !== null ? 1 : 0)
    try { onSwitched((await api.switchMeetingDevice(id, device, r.mic ?? 0, call)).device ?? device) } catch (e) { onError(String(e)) }
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
  const [namingLine, setNamingLine] = useState<{ id: number; ids: number[]; text: string } | null>(null)
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
  const sectionRef = useRef<HTMLElement>(null)
  const wrapRef = useRef<HTMLDivElement>(null)
  const currentPart = useRef(1)
  const partBase = useRef(0)
  const [timeMode, setTimeMode] = useTimeMode()
  const rows = useRef<Map<number, HTMLDivElement>>(new Map())
  // Auto-scroll (owner, 2026-10-24): on by default; only the toggle (or jumping to a citation) turns it off.
  const [autoScroll, setAutoScroll] = useState(true)
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
    if (autoScroll && listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [segments, autoScroll])

  // The transcript fills the window below the header, so the newest line is always in view without
  // scrolling the page (owner, 2026-10-24). Re-fitted when anything above or below it changes size.
  useLayoutEffect(() => {
    const list = listRef.current, section = sectionRef.current, wrap = wrapRef.current
    if (!list || !section || !wrap) return
    const fit = () => {
      if (window.innerWidth <= 760) { list.style.maxHeight = ''; return } // phones: the page scrolls instead
      const top = list.getBoundingClientRect().top + window.scrollY
      const below = section.getBoundingClientRect().bottom - list.getBoundingClientRect().bottom
      const h = Math.max(240, Math.floor(window.innerHeight - top - below - 24))
      if (list.style.maxHeight !== `${h}px`) list.style.maxHeight = `${h}px`
    }
    fit()
    const ro = new ResizeObserver(fit)
    ro.observe(wrap)
    window.addEventListener('resize', fit)
    return () => { ro.disconnect(); window.removeEventListener('resize', fit) }
  }, [m?.id, m?.live])

  function onCite(c: Citation | { startS: number }) {
    const target = nearest(segments, c.startS)
    if (!target) return
    setAutoScroll(false)
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
    <div ref={wrapRef} className={drop.dragging ? s.liveWrapDrop : s.liveWrap} {...drop.dropProps}>
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
              <Retranscribe id={m.id} minutes={m.parts.reduce((t, p) => t + p.duration_s, 0) / 60} otherLive={health?.activeMeetingId != null}
                onQueued={reloadQuietly} onError={setError} />
            </>}
        </div>
      </header>
      {m.transcriptFile && <TranscriptFile path={m.transcriptFile} live={m.live} />}
      {((m.live && status?.warning) || error) && <div className={ui.error}>{(m.live && status?.warning) || error}</div>}
      {m.live && status?.behind && <div className={ui.notice} role="status">{status.behind}</div>}
      {drop.error && <div className={ui.error}>{drop.error}</div>}
      {editingNames && <VocabularyEditor id={m.id} initial={m.vocabulary ?? ''} live={m.live} onDone={() => { setEditingNames(false); reloadQuietly() }} />}
      {!m.live && m.status === 'processing' && m.finishing === 'transcribe' && (
        <div className={ui.notice} role="status">Re-transcribing from the saved audio. This transcript stays until the new one is ready.
          It stops for any meeting you start and carries on after it.</div>
      )}
      {!m.live && m.status === 'processing' && m.finishing?.startsWith('catching up') && (
        <div className={ui.notice} role="status">Transcription was behind when you stopped. The rest is being transcribed from the saved audio ({m.finishing.replace('catching up: ', '')}); lines appear here when it is done. Nothing is lost.</div>
      )}

      <div className={s.liveBody}>
        <section ref={sectionRef} aria-label="Transcript" className={s.transcript}>
          <div className={s.panelHead}>
            <h2 className={s.h2}>{m.live ? 'Live transcript' : 'Transcript'}</h2>
            <span className={s.headTools}>
              {m.live && <button type="button" className={autoScroll ? s.autoOn : s.autoOff} aria-pressed={autoScroll}
                onClick={() => setAutoScroll(v => !v)}
                title={autoScroll ? 'Keeps the newest line in view. Turn off to scroll back freely.' : 'Turn on to follow the newest line.'}>
                Auto-scroll {autoScroll ? 'on' : 'off'}</button>}
              <TimeToggle mode={timeMode} onChange={setTimeMode} />
            </span>
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
          <div className={s.lines} ref={listRef}>
            {segments.length === 0 && <div className={ui.muted}>{m.live ? 'Listening… text appears a few seconds after each sentence.' : 'No transcript.'}</div>}
            {(() => {
              const partOf = (x: SegmentView) => (x as SegmentView & { part?: number }).part ?? 1
              const index = new Map(segments.map((x, i) => [x.id, i]))
              const groups = groupTurns(segments, x => `${partOf(x)}:${x.clusterId ?? x.key}`, (_x, i) => shotsBefore.has(i))
              const bands = turnBands(groups, g => g[0].clusterId ?? g[0].key)
              return groups.map((g, gi) => {
              const seg = g[0]
              const i = index.get(seg.id)!
              const ids = g.map(x => x.id)
              const text = g.map(x => x.text).join(' ')
              // Names come from the speaker list, so naming or regrouping relabels every line at once.
              const sp = seg.clusterId !== null ? speakerById.get(seg.clusterId) : undefined
              const part = partOf(seg)
              const prevPart = i > 0 ? partOf(segments[i - 1]) : part
              const divider = i > 0 && part !== prevPart ? m.parts.find(p => p.part === part) : undefined
              return (
                <div key={seg.id}>
                {shotsBefore.get(i)?.map(shotLine)}
                {divider && <div className={s.partDivider}>{resumedLabel(m.parts, divider.part)}</div>}
                <div ref={el => { if (el) ids.forEach(id => rows.current.set(id, el)) }} className={ids.some(id => cited.has(id)) ? s.lineCited : bands[gi] ? s.lineBand : s.line}>
                  <span className={s.clock} title={timeMode === 'clock' ? `${fmtElapsed(seg.elapsedS)} from the start` : `at ${seg.clock}`}>
                    {timeMode === 'clock' ? seg.clock : fmtElapsed(seg.elapsedS)}
                  </span>
                  {sp
                    ? <button className={s.whoBtn} style={{ color: speakerColor(sp.key).fg }} title={`${sp.name}: who said this?`}
                      onClick={() => { setNaming(sp); setNamingLine({ id: seg.id, ids, text }) }}>{sp.name}</button>
                    : <span className={s.who} style={{ color: speakerColor(seg.key).fg }} title={seg.speaker}>{seg.speaker}</span>}
                  <span className={s.text}>{text}</span>
                </div>
                {naming && namingLine?.id === seg.id && (
                  <div className={s.lineNaming}>
                    <NameSpeaker key={seg.id} speaker={naming} line={namingLine} people={people} onClose={() => { setNaming(null); setNamingLine(null) }}
                      onSaved={() => { setNaming(null); setNamingLine(null); if (!m.live) reload().catch(() => {}) }} />
                  </div>
                )}
                </div>
              )
            }) })()}
            {shotsAfter.map(shotLine)}
          </div>
          <span className={s.dropNote}>Paste (⌘V) or drop a screenshot anywhere on this page: it is placed in the transcript when it was taken.</span>
          {m.live && status && (
            <div className={s.resources}>
              <span title="Time to transcribe a minute of speech, over the last minute: below 1× keeps up">Transcription speed <b className="mono">{(status.asrRtfAvg ?? status.asrRtf).toFixed(2)}× real time</b></span>
              {status.mlMemoryMb != null && <span title="Speech engine memory, including GPU buffers">Speech engine <b className="mono">{gb(status.mlMemoryMb)}</b></span>}
              <span>Audio on disk <b className="mono">✓</b></span>
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
            <SummarizeButton provider={m.live && duringWith !== 'off' ? duringWith : afterWith} estimate={estimateSummary}
              again={!!summary} onRun={() => api.summarizeMeeting(id).then(setSummary)} liveNow={health?.activeMeetingId != null} />
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
            {askWith === 'local' && health?.activeMeetingId != null && <span className={ui.muted} style={{ color: 'var(--warn)' }}>A meeting is live: the local model will share the GPU with transcription and may slow the live transcript.</span>}
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
