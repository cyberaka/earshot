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

// Outline icons for the sidebar, shown alone when it is collapsed.
const ICONS: Record<string, string> = {
  today: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  meeting: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5',
  import: 'M12 15V4M7 9l5-5 5 5M4 20h16',
  people: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21v-1a6 6 0 0 1 12 0v1M16 3.5a4 4 0 0 1 0 7M22 21v-1a6 6 0 0 0-4-5.6',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M14 5v4M8 15v4',
}

function Icon({ name }: { name: string }) {
  return (
    <svg className={s.icon} width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={ICONS[name]} />
    </svg>
  )
}

const COLLAPSED_KEY = 'earshot.sidebarCollapsed'

function storedCollapsed() {
  try { return localStorage.getItem(COLLAPSED_KEY) === '1' } catch { return false }
}

export default function Shell({ children }: { children: ReactNode }) {
  const health = useHealth()
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [collapsed, setCollapsed] = useState(storedCollapsed)
  function toggle() {
    setCollapsed(c => {
      try { localStorage.setItem(COLLAPSED_KEY, c ? '0' : '1') } catch { /* private window: not remembered */ }
      return !c
    })
  }
  const link = ({ isActive }: { isActive: boolean }) => (isActive ? s.active : s.navLink)
  const allUp = health && health.db === 'up' && health.ml === 'up' && health.capture === 'up'

  function submit(e: FormEvent) {
    e.preventDefault()
    if (q.trim()) navigate(`/search?q=${encodeURIComponent(q.trim())}`)
  }

  return (
    <>
      <header className={s.header}>
        <button type="button" className={s.toggle} onClick={toggle} aria-expanded={!collapsed} aria-controls="main-nav"
          title={collapsed ? 'Show the sidebar' : 'Collapse the sidebar'}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 5h16v14H4zM9 5v14" />
          </svg>
          <span className="sr-only">{collapsed ? 'Show the sidebar' : 'Collapse the sidebar'}</span>
        </button>
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
        <nav id="main-nav" aria-label="Main" className={collapsed ? s.navCollapsed : s.nav}>
          {([['/', 'today', 'Today'], ['/meeting', 'meeting', 'Live meeting'], ['/search', 'search', 'Search'], ['/import', 'import', 'Import'],
            ['/people', 'people', 'People'], ['/settings', 'settings', 'Settings']] as const).map(([to, icon, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={link} title={collapsed ? label : undefined} aria-label={collapsed ? label : undefined}>
              <Icon name={icon} /><span className={s.label}>{label}</span>
            </NavLink>
          ))}
          {health?.activeMeetingId
            ? <NavLink to={`/meeting/${health.activeMeetingId}`} className={s.startMeeting} title={collapsed ? 'Meeting live' : undefined}><span className={s.whiteDot} /><span className={s.label}>Meeting live</span></NavLink>
            : <NavLink to="/meeting" className={s.startMeeting} title={collapsed ? 'Start meeting' : undefined}><span className={s.whiteDot} /><span className={s.label}>Start meeting</span></NavLink>}
        </nav>
        <main className={s.main}>{children}</main>
      </div>
    </>
  )
}
