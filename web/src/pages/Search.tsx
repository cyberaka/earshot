import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, todayLocal, type NoteHit, type SearchHit } from '../api'
import { NoteText } from '../components/Notes'
import AskBox from '../components/AskBox'
import ui from '../components/ui.module.css'
import s from './Search.module.css'

function weeksAgo(n: number) {
  const d = new Date()
  d.setDate(d.getDate() - n * 7)
  return d.toISOString().slice(0, 10)
}

/** Hybrid search across all days (A-7) and range Q&A (A-9). */
export default function Search() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const [input, setInput] = useState(q)
  const [from, setFrom] = useState(weeksAgo(4))
  const [to, setTo] = useState(todayLocal())
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [noteHits, setNoteHits] = useState<NoteHit[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { setInput(q) }, [q])
  useEffect(() => {
    if (!q) { setHits(null); setNoteHits([]); return }
    api.search(q).then(r => { setHits(r.transcript); setNoteHits(r.notes) }).catch(e => setError(String(e)))
  }, [q])

  function submit(e: FormEvent) {
    e.preventDefault()
    setParams(input.trim() ? { q: input.trim() } : {})
  }

  return (
    <>
      <h1 className={s.title}>Search &amp; ask</h1>
      <form className={s.searchForm} onSubmit={submit} role="search">
        <label htmlFor="search-q" className="sr-only">Search</label>
        <input id="search-q" type="search" className={ui.input} value={input} onChange={e => setInput(e.target.value)} placeholder="Words, names or topics — misspellings are fine" />
        <button className={ui.primary}>Search</button>
      </form>
      {error && <div className={ui.error}>{error}</div>}
      {noteHits.length > 0 && (
        <section className={ui.card}>
          <h2 className={s.h2}>Your notes</h2>
          {noteHits.map(n => (
            <div key={n.noteId} className={s.hit}>
              <Link to={`/day/${n.date}/${n.conversationId}`} className={s.hitMeta}><span className="mono">{n.date}{n.clock ? ` ${n.clock}` : ''}</span> · {n.conversationTitle ?? 'Conversation'}</Link>
              <NoteText text={n.text} />
            </div>
          ))}
        </section>
      )}
      {hits && (
        <section className={ui.card}>
          <h2 className={s.h2}>{hits.length} transcript result{hits.length === 1 ? '' : 's'} for “{q}”</h2>
          {hits.length === 0 && <span className={ui.muted}>Nothing matched.</span>}
          {hits.map(h => (
            <Link key={h.segmentId} to={`/day/${h.date}/${h.conversationId}`} className={s.hit}>
              <span className={s.hitMeta}><span className="mono">{h.date} {h.clock}</span> · {h.conversationTitle ?? 'Conversation'}</span>
              <span><b>{h.speaker}:</b> {h.text}</span>
            </Link>
          ))}
        </section>
      )}
      <section className={ui.card}>
        <div className={s.rangeRow}>
          <h2 className={s.h2}>Ask across days</h2>
          <label className={ui.field}>From<input type="date" className={ui.input} value={from} onChange={e => setFrom(e.target.value)} /></label>
          <label className={ui.field}>To<input type="date" className={ui.input} value={to} onChange={e => setTo(e.target.value)} /></label>
        </div>
        <AskBox key={`${from}..${to}`} scopeType="range" scopeRef={`${from}..${to}`} placeholder="e.g. What did Ravi and I agree about the migration?"
          onCite={() => {}} />
        <span className={ui.muted}>Answers cite the transcript; open a citation's day from the results above or the Today view.</span>
      </section>
    </>
  )
}
