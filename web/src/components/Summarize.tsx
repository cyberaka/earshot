import { useCallback, useEffect, useState } from 'react'
import { PROVIDER_LABEL, type Provider, type SummaryEstimate } from '../api'
import ui from './ui.module.css'

function fmtTokens(n: number) {
  return n >= 1000 ? `${Math.round(n / 1000)}k` : String(n)
}

/**
 * The only way a summary is made (owner rule, 2026-10-24): the user clicks, and the button says
 * beforehand what one click sends and to whom. No loop, nothing automatic.
 */
export default function SummarizeButton({ provider, estimate, onRun, again, held }: {
  provider: Provider
  estimate: () => Promise<SummaryEstimate>
  onRun: () => Promise<unknown>
  again: boolean
  /** Why it can't run right now (the local model during a live meeting). */
  held?: string | null
}) {
  const [est, setEst] = useState<SummaryEstimate | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const refresh = useCallback(() => { estimate().then(setEst).catch(() => setEst(null)) }, [estimate])
  useEffect(refresh, [refresh])

  if (provider === 'off') return null
  if (held) return <span className={ui.muted}>{held}</span>
  const cloud = provider === 'openai' || provider === 'anthropic'
  const size = est ? `~${fmtTokens(est.tokens)} tokens` : null

  async function click() {
    setBusy(true)
    setError(null)
    try { await onRun(); refresh() } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    setBusy(false)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }} onMouseEnter={refresh}>
      <button type="button" className={again ? ui.btnSmall : ui.primary} disabled={busy || est?.lines === 0} onClick={click}>
        {busy ? 'Summarizing…' : `${again ? 'Summarize again' : 'Summarize'}${cloud && size ? ` · ${size} to ${PROVIDER_LABEL[provider]}` : ''}`}
      </button>
      <span className={ui.muted}>
        {est?.lines === 0 ? 'Nothing to summarize yet.'
          : cloud ? `One request with the whole transcript${est ? ` (${est.lines} lines)` : ''}. It costs money; nothing is sent until you click.`
            : 'Runs on the local model: free, but it uses the GPU for a minute or two.'}
      </span>
      {error && <span className={ui.error}>{error}</span>}
    </div>
  )
}
