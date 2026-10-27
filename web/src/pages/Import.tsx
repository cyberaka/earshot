import { Fragment, useCallback, useEffect, useState, type DragEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, LANGUAGES, type Health, type Job } from '../api'
import { useHealth } from '../components/Shell'
import ui from '../components/ui.module.css'
import s from './Import.module.css'

// The steps that do work; "enriched" and "indexed" are no-ops since insights became click-only (D-10).
const STEPS = ['diarized', 'identified', 'transcribed', 'exported']
const DOING: Record<string, string> = { diarized: 'Separating speakers', identified: 'Recognising voices', transcribed: 'Transcribing', enriched: 'Saving', indexed: 'Saving', exported: 'Saving' }
const ZONES = ['Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'America/Los_Angeles', 'UTC']

function gb(bytes: number) { return `${(bytes / 1024 ** 3).toFixed(0)} GB` }

function fmtOffset(sec: number) {
  const sign = sec < 0 ? '−' : '+'
  const a = Math.abs(sec)
  return `${sign}${String(Math.floor(a / 3600)).padStart(2, '0')}:${String(Math.floor((a % 3600) / 60)).padStart(2, '0')}:${String(a % 60).padStart(2, '0')}`
}

function parseOffset(v: string): number | null {
  const m = v.trim().match(/^([+−-])?(\d{1,2}):(\d{2})(?::(\d{2}))?$/)
  if (!m) return null
  const n = Number(m[2]) * 3600 + Number(m[3]) * 60 + Number(m[4] ?? 0)
  return m[1] === '-' || m[1] === '−' ? -n : n
}

interface Pending { importId: number; rows: Record<string, unknown>[]; duplicates: string[] }

export default function Import() {
  const health = useHealth(15_000)
  const [zone, setZone] = useState(Intl.DateTimeFormat().resolvedOptions().timeZone)
  const [offset, setOffset] = useState('+00:00:00')
  const [language, setLanguage] = useState('en+hi')
  useEffect(() => { api.settings().then(v => { if (typeof v.language === 'string') setLanguage(v.language) }).catch(() => {}) }, [])
  const [pending, setPending] = useState<Pending | null>(null)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [jobs, setJobs] = useState<Job[]>([])
  const [drag, setDrag] = useState(false)
  // Dates whose list the user opened or closed; the newest date starts open, older ones closed.
  const [toggled, setToggled] = useState<Set<string>>(new Set())

  const loadJobs = useCallback(() => api.jobs().then(setJobs).catch(() => {}), [])
  useEffect(() => { loadJobs(); const t = setInterval(loadJobs, 3000); return () => clearInterval(t) }, [loadJobs])

  async function upload(files: File[]) {
    if (!files.length) return
    setUploading(true)
    setError(null)
    try {
      const r = await api.importFiles(files, zone, parseOffset(offset) ?? 0, language)
      const rows = await api.importFilesOf(r.importId)
      setPending({ importId: r.importId, rows, duplicates: r.files.filter(f => f.status === 'duplicate').map(f => f.name) })
    } catch (e) { setError(String(e)) }
    setUploading(false)
  }

  async function adjust(z: string, o: string) {
    const sec = parseOffset(o)
    if (!pending || sec === null) return
    const rows = await api.adjustImport(pending.importId, z, sec)
    setPending({ ...pending, rows })
  }

  async function process() {
    if (!pending) return
    await api.processImport(pending.importId)
    setPending(null)
    loadJobs()
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDrag(false)
    upload([...e.dataTransfer.files])
  }

  const convJobs = latestPerConversation(jobs)
  const byDate = groupByDate(convJobs)

  return (
    <>
      <h1 className={s.title}>Import recordings</h1>
      <div className={s.top}>
        <label className={drag ? s.dropActive : s.drop} onDragOver={e => { e.preventDefault(); setDrag(true) }} onDragLeave={() => setDrag(false)} onDrop={onDrop}>
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 16V4M7 9l5-5 5 5M4 20h16" /></svg>
          <b>{uploading ? 'Uploading…' : 'Drop recorder files here, or choose files'}</b>
          <span className={ui.muted}>WAV, MP3, M4A or FLAC · files dropped in <span className="mono">data/inbox/</span> are picked up automatically</span>
          <input type="file" multiple accept=".wav,.mp3,.m4a,.flac,audio/*" className="sr-only" onChange={e => upload([...(e.target.files ?? [])])} />
        </label>
        <Storage health={health} />
      </div>
      {error && <div className={ui.error}>{error}</div>}

      <section className={ui.card}>
        <div className={s.settingsRow}>
          <div className={s.grow}>
            <h2 className={s.h2}>{pending ? `New import · ${pending.rows.length} file${pending.rows.length === 1 ? '' : 's'}` : 'Import settings'}</h2>
            <span className={ui.muted}>Check the start times: recorder clocks drift.</span>
          </div>
          <label className={ui.field}>Time zone
            <select className={ui.input} value={zone} onChange={e => { setZone(e.target.value); adjust(e.target.value, offset) }}>
              {[...new Set([zone, ...ZONES])].map(z => <option key={z}>{z}</option>)}
            </select>
          </label>
          <label className={ui.field}>Clock offset
            <input className={`${ui.input} mono`} style={{ width: 130 }} value={offset} onChange={e => setOffset(e.target.value)} onBlur={() => adjust(zone, offset)} />
          </label>
          <label className={ui.field}>Language
            <select className={ui.input} value={language} onChange={e => setLanguage(e.target.value)}>
              {LANGUAGES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
            </select>
          </label>
          <button className={ui.primary} disabled={!pending || pending.rows.length === 0} onClick={process}>Process</button>
        </div>
        {pending && pending.duplicates.length > 0 && <div className={ui.notice}>Already imported, skipped: {pending.duplicates.join(', ')}</div>}
        {pending && pending.rows.length > 0 && (
          <div className={ui.tableWrap}>
            <table className={ui.table} style={{ minWidth: 560 }}>
              <thead><tr><th>File</th><th>Start (after offset)</th><th>Start time from</th><th>Status</th></tr></thead>
              <tbody>
                {pending.rows.map(r => (
                  <tr key={String(r.id)}>
                    <td className="mono">{String(r.original_name)}</td>
                    <td className="mono">{new Date(r.started_at as string).toLocaleString(undefined, { timeZone: String(r.time_zone) })}</td>
                    <td>{r.started_at_source === 'filename' ? 'Filename' : r.started_at_source === 'mtime' ? 'Modified time − duration' : 'Import time (check!)'}</td>
                    <td>{String(r.status)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className={ui.card}>
        <div className={s.settingsRow}>
          <h2 className={s.h2}>Imported conversations</h2>
          <span className={ui.muted} style={{ marginLeft: 'auto' }}>One file is processed at a time, and it waits while a meeting is live.</span>
        </div>
        {convJobs.length === 0 ? <span className={ui.muted}>Nothing imported yet.</span> : (
          <div className={ui.tableWrap}>
            <table className={`${ui.table} ${s.jobs}`}>
              <thead><tr><th>When</th><th className={s.num}>#</th><th>Conversation</th><th className={s.num}>Length</th><th className={s.num}>Speakers</th><th className={s.num}>Lines</th><th>Status</th></tr></thead>
              <tbody>
                {byDate.map(([date, rows], di) => {
                  const open = (di === 0) !== toggled.has(date)
                  return (
                    <Fragment key={date}>
                      <tr className={s.dateRow}>
                        <td colSpan={7}>
                          <button className={s.dateToggle} aria-expanded={open}
                            onClick={() => { const n = new Set(toggled); if (n.has(date)) n.delete(date); else n.add(date); setToggled(n) }}>
                            <span aria-hidden="true">{open ? '▾' : '▸'}</span> {fmtDate(date)}
                            <span className={ui.muted}> · {rows.length} conversation{rows.length === 1 ? '' : 's'}</span>
                          </button>
                        </td>
                      </tr>
                      {open && rows.map(j => (
                        <tr key={j.id}>
                          <td className={s.when}>{j.conversation_started_at ? new Date(j.conversation_started_at).toLocaleString(undefined, { hour: 'numeric', minute: '2-digit', timeZone: j.time_zone ?? undefined }) : ''}</td>
                          <td className={`${s.num} mono`}>{j.conversation_id}</td>
                          <td>
                            <Link to={`/day/${j.local_date}/${j.conversation_id}`}>{j.title && !j.is_ambient ? j.title : who(j)}</Link>
                            {j.original_name && <div className={ui.muted}>{j.original_name}</div>}
                          </td>
                          <td className={s.num}>{j.duration_s ? length(j.duration_s) : ''}</td>
                          <td className={s.num}>{j.is_ambient || !j.speakers ? '' : j.speakers}</td>
                          <td className={s.num}>{j.is_ambient || !j.lines ? '' : j.lines}</td>
                          <td><Status j={j} onTranscribe={() => api.transcribeAmbient(j.conversation_id!).then(loadJobs)} /></td>
                        </tr>
                      ))}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

/** Imported recordings only: meetings show their own progress on the meeting page. */
function latestPerConversation(jobs: Job[]) {
  const seen = new Set<number>()
  return jobs.filter(j => j.type === 'conversation' && j.source_type === 'recorder' && j.conversation_id !== null)
    .filter(j => { if (seen.has(j.conversation_id!)) return false; seen.add(j.conversation_id!); return true })
    .sort((a, b) => (b.conversation_started_at ?? '').localeCompare(a.conversation_started_at ?? ''))
    .slice(0, 60)
}

/** Conversations grouped by their local date, newest date first, keeping the newest-first order inside. */
function groupByDate(jobs: Job[]): [string, Job[]][] {
  const out = new Map<string, Job[]>()
  for (const j of jobs) {
    const d = j.local_date ?? ''
    out.set(d, [...(out.get(d) ?? []), j])
  }
  return [...out.entries()]
}

function fmtDate(date: string) {
  if (!date) return 'No date'
  return new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
}

/** Untitled conversations are named by who spoke: "Untitled conversation" told the owner nothing. */
function who(j: Job) {
  if (j.is_ambient) return 'Background speech'
  const unnamed = j.unnamed ? `${j.unnamed} unnamed` : ''
  if (j.people) return unnamed ? `${j.people} + ${unnamed}` : j.people
  return unnamed ? `${unnamed} speaker${j.unnamed === 1 ? '' : 's'}` : 'Untitled conversation'
}

function length(sec: number) {
  const m = Math.round(sec / 60)
  return m < 1 ? `${Math.round(sec)} s` : m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`
}

function Status({ j, onTranscribe }: { j: Job; onTranscribe: () => void }) {
  const stage = j.conversation_stage ?? 'segmented'
  if (j.status === 'failed') return <span className={s.failed} title={j.error ?? ''}>Failed</span>
  if (j.is_ambient && j.status === 'done') {
    return (
      <span className={s.status}>
        <span className={ui.muted}>Skipped: background speech</span>
        <button className={ui.btnSmall} onClick={onTranscribe}>Transcribe</button>
      </span>
    )
  }
  if (j.status === 'done') return <span className={s.ok}>Done</span>
  const step = Math.max(0, STEPS.indexOf(stage) + 1)
  const next = STEPS[Math.min(step, STEPS.length - 1)]
  if (j.status === 'running') {
    return (
      <span className={s.progress}>
        <span>{DOING[next]}…</span>
        <span className={s.bar}><i style={{ width: `${(step / STEPS.length) * 100}%` }} /></span>
      </span>
    )
  }
  return <span className={ui.muted} title={j.error ?? ''}>{j.error ? 'Waiting for the meeting to end' : 'Waiting'}</span>
}

function Storage({ health }: { health: Health | null }) {
  if (!health) return <section className={`${ui.card} ${s.storage}`}><h2 className={s.h2}>Storage</h2><span className={ui.muted}>…</span></section>
  const free = health.disk.freeBytes
  return (
    <section className={`${ui.card} ${s.storage}`}>
      <h2 className={s.h2}>Storage</h2>
      <span className={health.disk.lowDisk ? s.failed : undefined}>Free disk · {gb(free)}{health.disk.lowDisk ? ' (low)' : ''}</span>
      <span className={ui.muted}>Raw recorder audio is kept 30 days by default; working copies are kept for playback. Change it in Settings.</span>
    </section>
  )
}

export { fmtOffset }
