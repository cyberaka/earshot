import { useEffect, useState } from 'react'
import { api, PROVIDER_LABEL, type Correction, type Health, type InsightFeature, type Provider } from '../api'
import ui from '../components/ui.module.css'
import s from './Settings.module.css'

const GROUPS: { title: string; keys: string[] }[] = [
  { title: 'Transcription', keys: ['language', 'vocabulary', 'no_speech_prob', 'avg_logprob', 'compression_ratio'] },
  { title: 'Live meetings', keys: ['live_max_segment_seconds', 'live_lag_warn_seconds'] },
  { title: 'Conversations', keys: ['conversation_gap_seconds', 'max_conversation_minutes', 'min_conversation_seconds'] },
  { title: 'Speakers', keys: ['live_speaker_threshold', 'me_threshold', 'me_ambient_threshold', 'person_threshold', 'me_min_speech_seconds', 'diarization_threshold'] },
  { title: 'Import', keys: ['recorder_filename_pattern'] },
  { title: 'Retention', keys: ['raw_audio_retention_days', 'ambient_audio_retention_days', 'unknown_voiceprint_retention_days', 'backup_retention_days', 'min_free_disk_gb'] },
]

/**
 * The optional AI features, and which providers each can use (the service enforces the same rules).
 * Choosing a provider only shows the button: nothing runs until the user clicks it (owner rule, 2026-10-24).
 */
const FEATURES: { key: InsightFeature; label: string; hint: string; providers: Provider[] }[] = [
  { key: 'live_summary', label: 'Summary during a meeting', hint: 'A Summarize button on the live meeting: summary, topics and decisions so far. The local model shares the GPU with live transcription: fine on a 32 GB Mac, may slow the live transcript on 16 GB.', providers: ['off', 'local', 'openai', 'anthropic'] },
  { key: 'meeting_summary', label: 'Summary after a meeting', hint: 'A Summarize button on finished meetings and daily-notes conversations: summary and action items.', providers: ['off', 'local', 'openai', 'anthropic'] },
  { key: 'ask', label: 'Ask', hint: 'One answer per question you type, about a meeting, a day or a date range. During a meeting, a local model shares the GPU with transcription.', providers: ['off', 'local', 'openai', 'anthropic'] },
  { key: 'semantic_search', label: 'Search by meaning', hint: 'A "by meaning" tick box on Search, and a button that prepares new lines for it. Keyword search always works without it.', providers: ['off', 'local', 'openai'] },
]
const INSIGHT_KEYS = ['insights_model_local', 'insights_model_openai', 'insights_model_anthropic', 'insights_embedding_local', 'insights_embedding_openai',
  'qa_prompt_max_tokens']
// Settings from the removed automatic summary loop: hidden if an old value is still stored.
const RETIRED = ['summary_interval_seconds', 'summary_every_segments']

/** Runtime settings (NF-7): stored in the setting table, defaults from application.yaml. */
export default function Settings() {
  const [values, setValues] = useState<Record<string, unknown> | null>(null)
  const [health, setHealth] = useState<Health | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.settings().then(setValues).catch(e => setError(String(e)))
    api.health().then(setHealth).catch(() => {})
  }, [])

  async function save() {
    if (!values) return
    const changes: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(draft)) changes[k] = typeof values[k] === 'number' ? Number(v) : v
    try {
      setValues(await api.saveSettings(changes))
      setDraft({})
      setError(null)
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
      api.health().then(setHealth).catch(() => {})
    } catch (e) { setError(String(e)) }
  }

  if (!values) return <span className={ui.muted}>{error ?? 'Loading…'}</span>
  const known = new Set([...GROUPS.flatMap(g => g.keys), ...INSIGHT_KEYS, ...RETIRED, ...FEATURES.map(f => `insights_${f.key}`)])
  const rest = Object.keys(values).filter(k => !known.has(k))
  const groups = rest.length ? [...GROUPS, { title: 'Other', keys: rest }] : GROUPS
  const value = (k: string) => draft[k] ?? String(values[k] ?? '')
  const chosen = FEATURES.map(f => value(`insights_${f.key}`) as Provider)
  const keySet = { openai: health?.insights.providers.openai.keySet, anthropic: health?.insights.providers.anthropic.keySet }
  const field = (k: string) => (
    <label key={k} className={ui.field}>
      {k.replaceAll('_', ' ')}
      <input className={`${ui.input} ${typeof values[k] === 'number' ? 'mono' : ''}`} value={value(k)}
        inputMode={typeof values[k] === 'number' ? 'decimal' : undefined} onChange={e => setDraft({ ...draft, [k]: e.target.value })} />
    </label>
  )

  return (
    <>
      <div className={s.head}>
        <h1 className={s.title}>Settings</h1>
        <button className={ui.primary} disabled={Object.keys(draft).length === 0} onClick={save}>{saved ? 'Saved' : 'Save changes'}</button>
      </div>
      {error && <div className={ui.error}>{error}</div>}

      <section className={ui.card}>
        <h2 className={s.h2}>Insights (optional AI)</h2>
        <p className={ui.muted} style={{ margin: 0 }}>
          Earshot's job is the transcript: who said what, when, on this Mac. These extras are off unless you choose a provider,
          and even then <b>nothing runs until you click its button</b>: no timers, nothing after a meeting or import.
          Each meeting's transcript is also written to <span className="mono">data/live/</span> for any agent you use.
        </p>
        <div className={s.features}>
          {FEATURES.map((f, i) => {
            const p = chosen[i]
            return (
              <div key={f.key} className={s.feature}>
                <label className={ui.field}>
                  {f.label}
                  <select className={ui.input} value={p} onChange={e => setDraft({ ...draft, [`insights_${f.key}`]: e.target.value })}>
                    {f.providers.map(v => <option key={v} value={v}>{PROVIDER_LABEL[v]}</option>)}
                  </select>
                </label>
                <span className={ui.muted}>{f.hint}</span>
                {(p === 'openai' || p === 'anthropic') && (
                  <span className={s.privacy}>When you click, sends transcript text (never audio) to {PROVIDER_LABEL[p]}, at a cost per click.</span>
                )}
                {(p === 'openai' || p === 'anthropic') && keySet[p] === false && (
                  <span className={ui.error}>No {p === 'openai' ? 'OPENAI_API_KEY' : 'ANTHROPIC_API_KEY'} set: add it to <span className="mono">.env</span> and restart Earshot.</span>
                )}
              </div>
            )
          })}
        </div>
        <details>
          <summary className={ui.muted}>Models and timings</summary>
          <div className={s.grid}>{INSIGHT_KEYS.filter(k => k in values).map(field)}</div>
          <p className={ui.muted}>
            API keys are read from the environment or <span className="mono">.env</span> (<span className="mono">OPENAI_API_KEY</span>,{' '}
            <span className="mono">ANTHROPIC_API_KEY</span>) when Earshot starts; they are never stored here.
            OpenAI {keySet.openai ? 'key is set' : 'key is not set'} · Anthropic {keySet.anthropic ? 'key is set' : 'key is not set'}.
          </p>
        </details>
      </section>

      <Corrections />

      <div className={s.grid}>
        {groups.map(g => (
          <section key={g.title} className={ui.card}>
            <h2 className={s.h2}>{g.title}</h2>
            {g.keys.filter(k => k in values).map(field)}
          </section>
        ))}
      </div>
      <p className={ui.muted}>Speaker thresholds apply to new conversations; use “Re-match past conversations” on the People page to apply them to old ones.</p>
    </>
  )
}

/**
 * Words Earshot keeps getting wrong (owner request R-4): fixed in every new transcript, and given to
 * Whisper as names and terms. Usually added from a corrected line; also typed here.
 */
function Corrections() {
  const [list, setList] = useState<Correction[] | null>(null)
  const [wrong, setWrong] = useState('')
  const [fixed, setFixed] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api.corrections().then(setList).catch(e => setError(String(e)))
  useEffect(() => { load() }, [])

  async function add() {
    try { await api.addCorrection(wrong.trim(), fixed.trim()); setWrong(''); setFixed(''); setError(null); load() } catch (e) { setError(String(e)) }
  }
  return (
    <section className={ui.card}>
      <h2 className={s.h2}>Corrections</h2>
      <p className={ui.muted} style={{ margin: 0 }}>
        Words Earshot keeps getting wrong. Each is fixed in every new transcript and given to Whisper as a name or term.
        Correct a line on a day's transcript to add one, or type it here.
      </p>
      {list && list.length > 0 && (
        <div className={ui.tableWrap}>
          <table className={ui.table}>
            <thead><tr><th>Heard as</th><th>Should be</th><th /></tr></thead>
            <tbody>
              {list.map(c => (
                <tr key={c.id}>
                  <td>{c.wrong}</td>
                  <td><b>{c.fixed}</b></td>
                  <td className={s.rowTools}>
                    <button className={ui.btnSmall} onClick={() => api.applyCorrection(c.id).then(r => setNote(`Fixed “${c.wrong}” in ${r.lines} line${r.lines === 1 ? '' : 's'}.`)).catch(e => setError(String(e)))}>Fix existing transcripts</button>
                    <button className={ui.btnSmall} onClick={() => api.deleteCorrection(c.id).then(load)}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list && list.length === 0 && <span className={ui.muted}>None yet.</span>}
      <form className={s.addRow} onSubmit={e => { e.preventDefault(); if (wrong.trim() && fixed.trim()) add() }}>
        <label className={ui.field}>Heard as<input className={ui.input} value={wrong} onChange={e => setWrong(e.target.value)} placeholder="Taaf engineer" /></label>
        <label className={ui.field}>Should be<input className={ui.input} value={fixed} onChange={e => setFixed(e.target.value)} placeholder="staff engineer" /></label>
        <button className={ui.btnSmall} disabled={!wrong.trim() || !fixed.trim()}>Add</button>
      </form>
      {note && <span className={ui.muted}>{note}</span>}
      {error && <div className={ui.error}>{error}</div>}
    </section>
  )
}
