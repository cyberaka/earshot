import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, speakerColor, type Person, type Unknown } from '../api'
import ui from '../components/ui.module.css'
import { ConfirmButton } from './Today'
import s from './People.module.css'

export default function People() {
  const [people, setPeople] = useState<Person[]>([])
  const [unknowns, setUnknowns] = useState<Unknown[]>([])
  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const load = useCallback(() => api.people().then(p => { setPeople(p.people); setUnknowns(p.unknowns) }).catch(e => setError(String(e))), [])
  useEffect(() => { load() }, [load])

  const me = people.find(p => p.is_me)
  const others = people.filter(p => !p.is_me)

  return (
    <>
      <h1 className={s.title}>People &amp; voiceprints</h1>
      {error && <div className={ui.error}>{error}</div>}
      {msg && <div className={ui.notice}>{msg}</div>}
      <div className={s.top}>
        <MeCard me={me} onSaved={m => { setMsg(m); setTimeout(load, 1500) }} />
        <section className={`${ui.card} ${s.unknowns}`}>
          <div className={s.headRow}><h2 className={s.h2}>Name these speakers</h2><span className={ui.muted}>Unnamed voices are forgotten after 90 days</span></div>
          {unknowns.length === 0 && <span className={ui.muted}>No unnamed speakers.</span>}
          {unknowns.map(u => <UnknownRow key={u.cluster_id} u={u} people={others} onSaved={load} />)}
        </section>
      </div>
      <section className={ui.card}>
        <div className={s.headRow}>
          <h2 className={s.h2}>Library</h2>
          <button className={ui.btn} onClick={() => api.rematch().then(() => setMsg('Re-matching past conversations in the background.'))}>Re-match past conversations</button>
        </div>
        {others.length === 0 ? <span className={ui.muted}>Nobody named yet. Name a speaker in any conversation.</span> : (
          <div className={ui.tableWrap}>
            <table className={ui.table} style={{ minWidth: 720 }}>
              <thead><tr><th>Name</th><th>Aliases</th><th>Voiceprints</th><th>Conversations</th><th>Last seen</th><th><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>{others.map(p => <PersonRow key={p.id} p={p} people={others} onChanged={load} />)}</tbody>
            </table>
          </div>
        )}
        <p className={ui.muted} style={{ margin: 0 }}>Deleting a person removes their voiceprints; their lines show as unnamed speakers everywhere, including past notes.</p>
      </section>
    </>
  )
}

function MeCard({ me, onSaved }: { me?: Person; onSaved: (m: string) => void }) {
  const [rec, setRec] = useState<MediaRecorder | null>(null)
  const [secs, setSecs] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const chunks = useRef<Blob[]>([])
  useEffect(() => { if (rec) { const t = setInterval(() => setSecs(x => x + 1), 1000); return () => clearInterval(t) } }, [rec])

  async function startRec() {
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const r = new MediaRecorder(stream)
      chunks.current = []
      r.ondataavailable = e => chunks.current.push(e.data)
      r.onstop = async () => {
        stream.getTracks().forEach(t => t.stop())
        try { await api.enrollClip(new Blob(chunks.current, { type: r.mimeType }), 'enroll.webm', 'laptop_mic'); onSaved('Voice sample saved. Past conversations are being re-matched.') }
        catch (e) { setError(String(e)) }
      }
      r.start()
      setSecs(0)
      setRec(r)
    } catch (e) { setError(`Microphone not available: ${e}`) }
  }

  async function uploadClip(f: File) {
    try { await api.enrollClip(f, f.name, 'upload'); onSaved('Voice sample saved. Past conversations are being re-matched.') } catch (e) { setError(String(e)) }
  }

  return (
    <section className={`${ui.card} ${s.meCard}`}>
      <div className={s.meHead}>
        <span className={s.avatar}>Me</span>
        <div><h2 className={s.h2}>You</h2><span className={ui.muted}>{me && me.voiceprints > 0 ? `${me.voiceprints} voice sample${me.voiceprints === 1 ? '' : 's'} · ${me.voiceprint_sources}` : 'Not enrolled yet: nothing is marked ambient until you are.'}</span></div>
      </div>
      <p className={ui.muted} style={{ margin: 0 }}>Best: open a processed day and use “Mark lines that are me”, which learns your voice through the recorder's own mic. A laptop-mic sample helps live meetings.</p>
      <div className={s.actions}>
        {rec
          ? <button className={ui.live} onClick={() => { rec.stop(); setRec(null) }}>Stop · {secs}s {secs < 30 ? '(aim for 30–60 s)' : ''}</button>
          : <button className={ui.btn} onClick={startRec}>Record with laptop mic</button>}
        <label className={ui.btn}>Upload a clip<input type="file" accept="audio/*" className="sr-only" onChange={e => e.target.files?.[0] && uploadClip(e.target.files[0])} /></label>
      </div>
      {error && <div className={ui.error}>{error}</div>}
    </section>
  )
}

function UnknownRow({ u, people, onSaved }: { u: Unknown; people: Person[]; onSaved: () => void }) {
  const [name, setName] = useState('')
  const [add, setAdd] = useState(true)
  const audio = useRef<HTMLAudioElement>(null)
  const sample: [number, number] | null = u.sample ? JSON.parse(u.sample) : null
  const when = new Date(u.conversation_started_at).toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit', timeZone: u.time_zone })
  async function save(body: { personId?: number; name?: string; me?: boolean }) {
    await api.assignCluster(u.cluster_id, { ...body, addToLibrary: add })
    onSaved()
  }
  return (
    <div className={s.unknown}>
      <button className={s.play} aria-label="Play a sample" disabled={!sample} onClick={() => {
        if (!sample || !audio.current) return
        audio.current.src = api.audioUrl(u.recording_id, sample[0], Math.min(sample[1], sample[0] + 8))
        audio.current.play().catch(() => {})
      }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4l13 8-13 8z" /></svg>
      </button>
      <audio ref={audio} hidden />
      <div className={s.unknownText}>
        <Link to={`/day/${u.local_date}/${u.conversation_id}`}>{u.title ?? 'Conversation'}</Link>
        <span className={ui.muted}>{when} · {Math.round(u.speech_s)} s of speech</span>
      </div>
      <form className={s.nameForm} onSubmit={e => { e.preventDefault(); if (name.trim()) save({ name: name.trim() }) }}>
        <label className="sr-only" htmlFor={`n${u.cluster_id}`}>Name</label>
        <input id={`n${u.cluster_id}`} list="people-names" className={ui.input} style={{ width: 150, height: 40 }} placeholder="Type a name" value={name}
          onChange={e => setName(e.target.value)} />
        <button className={ui.btnSmall} disabled={!name.trim()}>Save</button>
        <button type="button" className={ui.btnSmall} onClick={() => save({ me: true })}>Me</button>
        <datalist id="people-names">{people.map(p => <option key={p.id} value={p.name} />)}</datalist>
      </form>
      <label className={s.check}><input type="checkbox" checked={add} onChange={e => setAdd(e.target.checked)} /> Add to library</label>
    </div>
  )
}

function PersonRow({ p, people, onChanged }: { p: Person; people: Person[]; onChanged: () => void }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(p.name)
  const [aliases, setAliases] = useState(p.aliases.join(', '))
  const [mergeInto, setMergeInto] = useState<string>('')
  const col = speakerColor(`p${p.id}`)
  return (
    <tr>
      <td>
        {editing
          ? <input className={ui.input} style={{ height: 36 }} value={name} onChange={e => setName(e.target.value)} aria-label="Name" />
          : <span className={s.personName}><span className={s.initial} style={{ background: col.bg, color: col.fg }}>{p.name.slice(0, 1).toUpperCase()}</span>{p.name}</span>}
      </td>
      <td>{editing ? <input className={ui.input} style={{ height: 36 }} value={aliases} onChange={e => setAliases(e.target.value)} aria-label="Aliases, comma separated" /> : (p.aliases.join(', ') || '—')}</td>
      <td>{p.voiceprint_sources ?? '—'}</td>
      <td className="mono">{p.conversations}</td>
      <td>{p.last_seen ?? '—'}</td>
      <td className={s.rowActions}>
        {editing
          ? <><button className={ui.btnSmall} onClick={() => api.updatePerson(p.id, name, aliases.split(',').map(a => a.trim()).filter(Boolean)).then(() => { setEditing(false); onChanged() })}>Save</button><button className={ui.btnSmall} onClick={() => setEditing(false)}>Cancel</button></>
          : <button className={ui.btnSmall} onClick={() => setEditing(true)}>Edit</button>}
        <select className={ui.input} style={{ height: 36 }} value={mergeInto} aria-label={`Merge ${p.name} into`} onChange={e => setMergeInto(e.target.value)}>
          <option value="">Merge into…</option>
          {people.filter(x => x.id !== p.id).map(x => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
        {mergeInto && <button className={ui.btnSmall} onClick={() => api.mergePerson(p.id, Number(mergeInto)).then(onChanged)}>Merge</button>}
        <ConfirmButton label="Delete" confirm={`Delete ${p.name}`} onConfirm={() => api.deletePerson(p.id).then(onChanged)} />
      </td>
    </tr>
  )
}
