import { useEffect, useState } from 'react'
import { api } from '../api'
import ui from '../components/ui.module.css'
import s from './Settings.module.css'

const GROUPS: { title: string; keys: string[] }[] = [
  { title: 'Models', keys: ['llm_batch_model', 'llm_live_model', 'embedding_model', 'language'] },
  { title: 'Conversations', keys: ['conversation_gap_seconds', 'max_conversation_minutes', 'min_conversation_seconds'] },
  { title: 'Speakers', keys: ['me_threshold', 'me_ambient_threshold', 'person_threshold', 'me_min_speech_seconds', 'diarization_threshold'] },
  { title: 'Live meetings', keys: ['summary_interval_seconds', 'summary_every_segments', 'qa_prompt_max_tokens', 'live_max_segment_seconds', 'live_lag_skip_summary_seconds'] },
  { title: 'Transcription filters', keys: ['no_speech_prob', 'avg_logprob', 'compression_ratio'] },
  { title: 'Import', keys: ['recorder_filename_pattern'] },
  { title: 'Retention', keys: ['raw_audio_retention_days', 'ambient_audio_retention_days', 'unknown_voiceprint_retention_days', 'backup_retention_days', 'min_free_disk_gb'] },
]

/** Runtime settings (NF-7): stored in the setting table, defaults from application.yaml. */
export default function Settings() {
  const [values, setValues] = useState<Record<string, unknown> | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { api.settings().then(setValues).catch(e => setError(String(e))) }, [])

  async function save() {
    if (!values) return
    const changes: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(draft)) changes[k] = typeof values[k] === 'number' ? Number(v) : v
    try {
      setValues(await api.saveSettings(changes))
      setDraft({})
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    } catch (e) { setError(String(e)) }
  }

  if (!values) return <span className={ui.muted}>{error ?? 'Loading…'}</span>
  const known = new Set(GROUPS.flatMap(g => g.keys))
  const rest = Object.keys(values).filter(k => !known.has(k))
  const groups = rest.length ? [...GROUPS, { title: 'Other', keys: rest }] : GROUPS

  return (
    <>
      <div className={s.head}>
        <h1 className={s.title}>Settings</h1>
        <button className={ui.primary} disabled={Object.keys(draft).length === 0} onClick={save}>{saved ? 'Saved' : 'Save changes'}</button>
      </div>
      {error && <div className={ui.error}>{error}</div>}
      <div className={s.grid}>
        {groups.map(g => (
          <section key={g.title} className={ui.card}>
            <h2 className={s.h2}>{g.title}</h2>
            {g.keys.filter(k => k in values).map(k => (
              <label key={k} className={ui.field}>
                {k.replaceAll('_', ' ')}
                <input className={`${ui.input} ${typeof values[k] === 'number' ? 'mono' : ''}`} value={draft[k] ?? String(values[k])}
                  inputMode={typeof values[k] === 'number' ? 'decimal' : undefined} onChange={e => setDraft({ ...draft, [k]: e.target.value })} />
              </label>
            ))}
          </section>
        ))}
      </div>
      <p className={ui.muted}>Speaker thresholds apply to new conversations; use “Re-match past conversations” on the People page to apply them to old ones.</p>
    </>
  )
}
