import { useState, type FormEvent } from 'react'
import { ask, type Citation } from '../api'
import ui from './ui.module.css'
import s from './AskBox.module.css'

export interface QA { question: string; answer: string; citations: Citation[]; pending: boolean; error?: string }

/** Streams a grounded answer; citations are clickable timestamps (PRD §6.5, A-9). */
export function useAsk(scopeType: string, scopeRef: string) {
  const [items, setItems] = useState<QA[]>([])
  const busy = items.some(i => i.pending)

  async function run(question: string, action?: string, label?: string) {
    const idx = items.length
    setItems(prev => [...prev, { question: label ?? question, answer: '', citations: [], pending: true }])
    const patch = (f: (q: QA) => QA) => setItems(prev => prev.map((q, i) => (i === idx ? f(q) : q)))
    try {
      await ask({ scopeType, scopeRef, question, action: action ?? null }, {
        onToken: t => patch(q => ({ ...q, answer: q.answer + t })),
        onCitations: c => patch(q => ({ ...q, citations: c })),
        onDone: () => patch(q => ({ ...q, pending: false })),
        onError: m => patch(q => ({ ...q, pending: false, error: m })),
      })
    } catch (e) {
      patch(q => ({ ...q, pending: false, error: String(e) }))
    }
    patch(q => ({ ...q, pending: false }))
  }

  return { items, setItems, busy, run }
}

export function Answer({ qa, onCite }: { qa: QA; onCite: (c: Citation) => void }) {
  return (
    <div className={s.thread}>
      <div className={s.q}>{qa.question}</div>
      <div className={s.a}>
        {qa.error ? <span className={s.err}>{qa.error}</span>
          : <span className={s.text}>{qa.answer || (qa.pending ? 'Thinking…' : '')}</span>}
        {qa.citations.length > 0 && (
          <span className={s.cites}>
            {qa.citations.map(c => (
              <button key={c.n} type="button" className={ui.cite} onClick={() => onCite(c)} title={`${c.speaker} at ${c.clock}`}>
                [{c.n}] {c.clock}
              </button>
            ))}
          </span>
        )}
      </div>
    </div>
  )
}

export default function AskBox({ scopeType, scopeRef, placeholder, onCite }: {
  scopeType: string; scopeRef: string; placeholder: string; onCite: (c: Citation) => void
}) {
  const { items, busy, run } = useAsk(scopeType, scopeRef)
  const [q, setQ] = useState('')
  function submit(e: FormEvent) {
    e.preventDefault()
    if (!q.trim() || busy) return
    run(q.trim())
    setQ('')
  }
  return (
    <div className={s.box}>
      <form className={s.form} onSubmit={submit}>
        <label htmlFor={`ask-${scopeType}`} className="sr-only">Ask</label>
        <input id={`ask-${scopeType}`} className={s.input} value={q} onChange={e => setQ(e.target.value)} placeholder={placeholder} />
        <button className={ui.primary} disabled={busy || !q.trim()}>Ask</button>
      </form>
      {items.length > 0 && <div className={s.list}>{items.map((qa, i) => <Answer key={i} qa={qa} onCite={onCite} />)}</div>}
    </div>
  )
}
