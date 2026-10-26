import { useCallback, useEffect, useRef, useState } from 'react'
import { api, fmtDuration, fmtElapsed, groupTurns, speakerColor, type TranscriptLine, type Transcripts, type TranscriptVersion } from '../api'
import ui from './ui.module.css'
import s from './TranscriptCompare.module.css'

/**
 * A whole conversation sent to OpenAI, kept beside Earshot's transcript (owner request, 2026-10-26):
 * "the control has to be in the hand of the user". The cost is shown before the click; the two
 * transcripts are compared side by side and the user picks the one the conversation uses. Neither is
 * deleted, and switching back restores Earshot's lines with their edits and speakers.
 */
export default function TranscriptCompare({ conversationId, hasAudio, play, onChanged }: {
  conversationId: number; hasAudio: boolean; play: (start: number, end: number) => void; onChanged: () => void
}) {
  const [data, setData] = useState<Transcripts | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [comparing, setComparing] = useState(false)
  const [rightId, setRightId] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => api.transcripts(conversationId).then(setData).catch(e => setError(String(e))), [conversationId])
  useEffect(() => { setData(null); setConfirming(false); setComparing(false); setRightId(null); setError(null); load() }, [load])

  const running = data?.versions.find(v => v.status === 'running')
  // While OpenAI is working, look again every few seconds; a whole conversation takes minutes.
  useEffect(() => {
    if (!running) return
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [running, load])

  // Its transcript just arrived: open the comparison on it.
  const prevRunning = useRef<number | null>(null)
  useEffect(() => {
    const was = prevRunning.current
    prevRunning.current = running?.id ?? null
    const done = data?.versions.find(v => v.id === was)
    if (was !== null && !running && done?.status === 'done') { setComparing(true); setRightId(done.id) }
  }, [running, data])

  if (!data) return error ? <div className={ui.error}>{error}</div> : null
  const earshot = data.versions.find(v => v.engine === 'earshot')!
  const openai = data.versions.filter(v => v.engine === 'openai')
  const done = openai.filter(v => v.status === 'done')
  const failed = openai.length > 0 && openai[openai.length - 1].status === 'failed' ? openai[openai.length - 1] : null
  const right = done.find(v => v.id === rightId) ?? done.find(v => v.inUse) ?? done[done.length - 1]
  const inUse = data.versions.find(v => v.inUse)

  async function send() {
    setBusy(true); setError(null)
    try { await api.transcribeWithOpenai(conversationId); setConfirming(false); await load() } catch (e) { setError(String(e)) }
    setBusy(false)
  }
  async function use(v: TranscriptVersion) {
    setBusy(true); setError(null)
    try { await api.useTranscript(conversationId, v.id); await load(); onChanged() } catch (e) { setError(String(e)) }
    setBusy(false)
  }

  const plan = data.plan
  return (
    <div className={s.wrap}>
      <div className={s.bar}>
        {inUse && inUse.engine !== 'earshot' && <span className={s.using}>Using OpenAI's transcript ({dated(inUse)}). Earshot's is kept.</span>}
        {running
          ? <span className={ui.muted}>OpenAI is transcribing the whole conversation{running.pieces > 1 ? ` in ${running.pieces} pieces` : ''}… this takes a few minutes.</span>
          : !confirming && <button className={ui.btnSmall} disabled={!hasAudio || busy} onClick={() => { setError(null); setConfirming(true); load() }}>Transcribe with OpenAI…</button>}
        {done.length > 0 && <button className={ui.btnSmall} onClick={() => setComparing(!comparing)}>{comparing ? 'Hide comparison' : 'Compare transcripts'}</button>}
      </div>
      {confirming && (plan.available
        ? <div className={s.confirm}>
          <p>Sends the whole conversation ({fmtDuration(plan.audioS)} of audio) to OpenAI {plan.model}, about ${plan.usd.toFixed(2)} at its
            per-minute price. OpenAI writes what it hears in its own script, with its own speakers.</p>
          {plan.pieces > 1 && <p className={s.warn}>That is longer than OpenAI takes at once, so it goes in {plan.pieces} pieces of about
            20 minutes, cut at pauses. Its speaker letters start again in each piece: “Speaker A (part 2)” may be the same person as “Speaker A”.</p>}
          <p className={ui.muted}>Its transcript is kept beside Earshot's. Nothing changes until you choose to use it.</p>
          <div className={s.actions}>
            <button className={ui.primary} disabled={busy} onClick={send}>{busy ? 'Sending…' : 'Send to OpenAI'}</button>
            <button className={ui.btnSmall} disabled={busy} onClick={() => setConfirming(false)}>Cancel</button>
          </div>
        </div>
        : <div className={ui.muted}>Set OPENAI_API_KEY in .env and restart Earshot to use OpenAI. <button className={ui.btnSmall} onClick={() => setConfirming(false)}>OK</button></div>)}
      {failed && !running && <div className={ui.error}>OpenAI's last try failed: {failed.error}</div>}
      {error && <div className={ui.error}>{error}</div>}
      {comparing && right && (
        <Compare left={earshot} right={right} choices={done} onPick={id => setRightId(id)} startS={data.startS} hasAudio={hasAudio}
          play={play} busy={busy} onUse={use} />
      )}
    </div>
  )
}

function dated(v: TranscriptVersion) {
  const when = v.createdAt ? new Date(v.createdAt).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''
  return [v.model, when].filter(Boolean).join(', ')
}

function words(lines: TranscriptLine[]) {
  return lines.reduce((n, l) => n + l.text.split(/\s+/).filter(Boolean).length, 0)
}

/** Two columns that scroll together by time: each paragraph lines up roughly with what was said then on the other side. */
function Compare({ left, right, choices, onPick, startS, hasAudio, play, busy, onUse }: {
  left: TranscriptVersion; right: TranscriptVersion; choices: TranscriptVersion[]; onPick: (id: number) => void; startS: number
  hasAudio: boolean; play: (start: number, end: number) => void; busy: boolean; onUse: (v: TranscriptVersion) => void
}) {
  const cols = [useRef<HTMLDivElement>(null), useRef<HTMLDivElement>(null)]
  // The column scrolled by code, not by the user: its next scroll event is ignored.
  const following = useRef<number | null>(null)

  function follow(from: number) {
    if (following.current === from) { following.current = null; return }
    const src = cols[from].current, dst = cols[1 - from].current
    if (!src || !dst) return
    const top = [...src.querySelectorAll<HTMLElement>('[data-t]')].find(el => el.offsetTop + el.offsetHeight > src.scrollTop)
    if (!top) return
    const t = Number(top.dataset.t)
    const paras = [...dst.querySelectorAll<HTMLElement>('[data-t]')]
    const target = paras.filter(el => Number(el.dataset.t) <= t).pop() ?? paras[0]
    if (!target) return
    following.current = 1 - from
    dst.scrollTop = target.offsetTop
  }

  return (
    <div className={s.compare}>
      {[left, right].map((v, i) => (
        <section key={i} className={s.column}>
          <header className={v.inUse ? s.headInUse : s.head}>
            <div className={s.headTop}>
              {i === 0 ? <b>Earshot</b>
                : choices.length > 1
                  ? <select className={s.pick} value={v.id ?? ''} onChange={e => onPick(Number(e.target.value))} aria-label="Which OpenAI transcript">
                    {choices.map(c => <option key={c.id} value={c.id!}>OpenAI · {dated(c)}</option>)}
                  </select>
                  : <b>OpenAI · {dated(v)}</b>}
              {v.inUse
                ? <span className={s.inUse}>In use</span>
                : <button className={ui.btnSmall} disabled={busy} onClick={() => onUse(v)}>Use this version</button>}
            </div>
            <div className={ui.muted}>
              {v.lines.length} lines · {words(v.lines).toLocaleString()} words · {new Set(v.lines.map(l => l.speaker)).size} speakers
              {v.engine === 'openai' && v.usd !== null && <> · about ${v.usd.toFixed(2)}</>}
              {v.engine === 'openai' && v.tookS !== null && <> · took {fmtDuration(v.tookS)}</>}
              {v.engine === 'openai' && v.usage?.output_tokens !== undefined &&
                <span title="Tokens OpenAI counted for this call: OpenAI's usage page shows the exact charge"> · {((v.usage.audio_tokens ?? 0) + (v.usage.text_tokens ?? 0)).toLocaleString()} tokens in, {v.usage.output_tokens.toLocaleString()} out</span>}
            </div>
          </header>
          <div ref={cols[i]} className={s.body} onScroll={() => follow(i)}>
            {groupTurns(v.lines, l => l.speaker).map(g => {
              const first = g[0], last = g[g.length - 1]
              const col = speakerColor(first.speaker)
              return (
                <div key={`${first.startS}-${first.speaker}`} data-t={first.startS} className={s.para}>
                  <button className={s.play} aria-label={`Play from ${fmtElapsed(first.startS - startS)}`} disabled={!hasAudio}
                    onClick={() => play(first.startS, last.endS + 0.3)}>
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4l13 8-13 8z" /></svg>
                  </button>
                  <div className={s.paraText}>
                    <div className={s.meta}><span className={s.clock}>{fmtElapsed(first.startS - startS)}</span>
                      <span className={s.who} style={{ color: col.fg }}>{first.speaker}</span></div>
                    <div className={s.said}>{g.map(l => l.text).join(' ')}</div>
                  </div>
                </div>
              )
            })}
            {v.lines.length === 0 && <div className={ui.muted}>No lines.</div>}
          </div>
        </section>
      ))}
    </div>
  )
}
