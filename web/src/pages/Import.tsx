import { useCallback, useEffect, useState, type DragEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, type Health, type Job } from '../api'
import { useHealth } from '../components/Shell'
import ui from '../components/ui.module.css'
import s from './Import.module.css'

const STAGES = ['diarized', 'identified', 'transcribed', 'enriched', 'indexed', 'exported']
const STAGE_LABEL: Record<string, string> = { diarized: 'Diarize', identified: 'Identify', transcribed: 'Transcribe', enriched: 'Enrich', indexed: 'Index', exported: 'Export' }
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
  const [language, setLanguage] = useState('en')
  const [pending, setPending] = useState<Pending | null>(null)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [jobs, setJobs] = useState<Job[]>([])
  const [drag, setDrag] = useState(false)

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
              <option value="en">English</option><option value="hi">Hindi</option><option value="auto">Auto</option>
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
          <h2 className={s.h2}>Processing</h2>
          <span className={ui.muted} style={{ marginLeft: 'auto' }}>One ML job at a time · pauses automatically during meetings</span>
        </div>
        {convJobs.length === 0 ? <span className={ui.muted}>Nothing processed yet.</span> : (
          <div className={ui.tableWrap}>
            <div className={s.grid}>
              {convJobs.map(j => (
                <div key={j.id} className={s.jobRow}>
                  <span className={s.jobLabel}>
                    <b className="mono">{j.conversation_started_at ? new Date(j.conversation_started_at).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: j.time_zone ?? undefined }) : ''}</b>
                    {' '}{j.title ? <Link to={`/day/${j.local_date}/${j.conversation_id}`}>{j.title}</Link> : j.type === 'post_meeting' ? 'Meeting' : 'Conversation'}
                    {j.status === 'failed' && <span className={s.failed} title={j.error ?? ''}> · failed</span>}
                    {j.status === 'queued' && j.error && <span className={ui.muted} title={j.error}> · waiting</span>}
                  </span>
                  <div className={s.stages}>
                    {STAGES.map(st => {
                      const state = stageState(j, st)
                      return <span key={st} className={s[state]}>{STAGE_LABEL[st]}</span>
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
        <div className={s.legendRow}>
          <span><i className={s.done} />Done</span><span><i className={s.running} />Running</span><span><i className={s.waiting} />Waiting</span><span><i className={s.skipped} />Skipped (ambient)</span>
        </div>
      </section>
    </>
  )
}

function latestPerConversation(jobs: Job[]) {
  const seen = new Set<number>()
  return jobs.filter(j => (j.type === 'conversation' || j.type === 'post_meeting') && j.conversation_id !== null)
    .filter(j => { if (seen.has(j.conversation_id!)) return false; seen.add(j.conversation_id!); return true })
    .slice(0, 25)
}

function stageState(j: Job, st: string): 'done' | 'running' | 'waiting' | 'skipped' {
  const cs = j.conversation_stage ?? 'segmented'
  const order = ['segmented', 'live', ...STAGES]
  const doneIdx = order.indexOf(cs === 'live' ? 'segmented' : cs)
  const idx = order.indexOf(st)
  if (j.is_ambient && idx > order.indexOf('identified')) return 'skipped'
  if (idx <= doneIdx) return 'done'
  if (j.status === 'running' && idx === doneIdx + 1) return 'running'
  return 'waiting'
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
