import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { api, PROVIDER_LABEL, type Health, type Provider } from '../api'
import s from './Shell.module.css'

export function Logo({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <path d="M3 12h2M7 8v8M11 5v14M15 8v8M19 11v2" />
    </svg>
  )
}

export function useHealth(intervalMs = 10_000) {
  const [health, setHealth] = useState<Health | null>(null)
  useEffect(() => {
    let alive = true
    const load = () => api.health().then(h => alive && setHealth(h)).catch(() => alive && setHealth(null))
    load()
    const t = setInterval(load, intervalMs)
    return () => { alive = false; clearInterval(t) }
  }, [intervalMs])
  return health
}

/** Optional AI at a glance: nothing when it is all off; which provider when transcript text leaves the Mac. */
function InsightsBadge({ insights }: { insights: Health['insights'] }) {
  if (!insights) return null
  const { providers, sendsTextOff } = insights
  if (providers.local.used && !providers.local.up) return <span className={s.down} title="Settings → Insights uses the local model, which is not reachable">Local AI offline</span>
  const missingKey = (providers.openai.used && !providers.openai.keySet) || (providers.anthropic.used && !providers.anthropic.keySet)
  if (missingKey) return <span className={s.down} title="Set OPENAI_API_KEY / ANTHROPIC_API_KEY in .env and restart">AI key missing</span>
  if (sendsTextOff.length) {
    const who = [...new Set(sendsTextOff.map(x => x.split('→')[1].trim()))].map(p => PROVIDER_LABEL[p as Provider] ?? p).join(', ')
    return <span className={s.neutral} title={`Transcript text is sent for: ${sendsTextOff.join('; ')}`}>Insights: {who}</span>
  }
  return null
}

export default function Shell({ children }: { children: ReactNode }) {
  const health = useHealth()
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const link = ({ isActive }: { isActive: boolean }) => (isActive ? s.active : s.navLink)
  const allUp = health && health.db === 'up' && health.ml === 'up' && health.capture === 'up'

  function submit(e: FormEvent) {
    e.preventDefault()
    if (q.trim()) navigate(`/search?q=${encodeURIComponent(q.trim())}`)
  }

  return (
    <>
      <header className={s.header}>
        <NavLink to="/" className={s.brand}><Logo />Earshot</NavLink>
        <form className={s.search} onSubmit={submit} role="search">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
          <label htmlFor="global-search" className="sr-only">Search all days</label>
          <input id="global-search" type="search" value={q} onChange={e => setQ(e.target.value)} placeholder="Search all days: words, names, topics" />
        </form>
        <div className={s.status}>
          <span className={allUp ? s.ok : s.down}><span className={s.dot} />{allUp ? 'Transcription · all local' : health ? 'Some services down' : 'Service unreachable'}</span>
          {health && <InsightsBadge insights={health.insights} />}
          {health?.disk.lowDisk && <span className={s.down}>Low disk</span>}
        </div>
      </header>
      <div className={s.body}>
        <nav aria-label="Main" className={s.nav}>
          <NavLink to="/" end className={link}>Today</NavLink>
          <NavLink to="/meeting" className={link}>Live meeting</NavLink>
          <NavLink to="/search" className={link}>Search</NavLink>
          <NavLink to="/import" className={link}>Import</NavLink>
          <NavLink to="/people" className={link}>People</NavLink>
          <NavLink to="/settings" className={link}>Settings</NavLink>
          {health?.activeMeetingId
            ? <NavLink to={`/meeting/${health.activeMeetingId}`} className={s.startMeeting}><span className={s.whiteDot} />Meeting live</NavLink>
            : <NavLink to="/meeting" className={s.startMeeting}><span className={s.whiteDot} />Start meeting</NavLink>}
        </nav>
        <main className={s.main}>{children}</main>
      </div>
    </>
  )
}
