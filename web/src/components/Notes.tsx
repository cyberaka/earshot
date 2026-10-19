import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { flushSync } from 'react-dom'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, type NoteView } from '../api'
import ui from './ui.module.css'
import s from './Notes.module.css'

// Notes are Markdown. Raw HTML is never rendered (react-markdown's default), and only these
// elements survive; anything else (tables, images, headings) is shown as its plain content.
const ALLOWED = ['p', 'strong', 'em', 'del', 'ul', 'ol', 'li', 'a', 'code', 'pre', 'blockquote', 'br']

/** A note rendered as Markdown; links open in a new tab and send no referrer. */
export function NoteText({ text }: { text: string }) {
  return (
    <div className={s.md}>
      <Markdown remarkPlugins={[remarkGfm]} allowedElements={ALLOWED} unwrapDisallowed
        components={{ a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> }}>
        {text}
      </Markdown>
    </div>
  )
}

type Edit = { value: string; start: number; end: number }

/** Wraps the selection (or a placeholder) in before/after markers. */
function wrap(e: Edit, before: string, after: string, placeholder: string): Edit {
  const sel = e.value.slice(e.start, e.end) || placeholder
  const value = e.value.slice(0, e.start) + before + sel + after + e.value.slice(e.end)
  return { value, start: e.start + before.length, end: e.start + before.length + sel.length }
}

/** Prefixes every selected line (or the current one) with a marker, e.g. "- " or "> ". */
function prefixLines(e: Edit, marker: (i: number) => string): Edit {
  const lineStart = e.value.lastIndexOf('\n', e.start - 1) + 1
  const lineEndIdx = e.value.indexOf('\n', e.end)
  const lineEnd = lineEndIdx === -1 ? e.value.length : lineEndIdx
  const block = e.value.slice(lineStart, lineEnd).split('\n').map((l, i) => marker(i) + l).join('\n')
  const value = e.value.slice(0, lineStart) + block + e.value.slice(lineEnd)
  return { value, start: lineStart, end: lineStart + block.length }
}

const TOOLS: { label: string; title: string; key?: string; apply: (e: Edit) => Edit }[] = [
  { label: 'B', title: 'Bold (⌘B)', key: 'b', apply: e => wrap(e, '**', '**', 'bold') },
  { label: 'I', title: 'Italic (⌘I)', key: 'i', apply: e => wrap(e, '*', '*', 'italic') },
  { label: '• List', title: 'Bulleted list', apply: e => prefixLines(e, () => '- ') },
  { label: '1. List', title: 'Numbered list', apply: e => prefixLines(e, i => `${i + 1}. `) },
  { label: 'Link', title: 'Link (⌘K)', key: 'k', apply: e => {
    // Selected text becomes the link text; the URL placeholder is selected for typing over.
    const text = e.value.slice(e.start, e.end) || 'link text'
    const value = e.value.slice(0, e.start) + `[${text}](https://)` + e.value.slice(e.end)
    const urlStart = e.start + text.length + 3
    return { value, start: urlStart, end: urlStart + 'https://'.length }
  } },
  { label: '</>', title: 'Inline code', apply: e => wrap(e, '`', '`', 'code') },
  { label: '❝ Quote', title: 'Quote', apply: e => prefixLines(e, () => '> ') },
]

export function NoteEditor({ initial = '', placeholder, onSave, onCancel, autoFocus }: {
  initial?: string; placeholder: string; onSave: (text: string) => Promise<unknown>; onCancel?: () => void; autoFocus?: boolean
}) {
  const [text, setText] = useState(initial)
  const [preview, setPreview] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const area = useRef<HTMLTextAreaElement>(null)
  const id = useRef(`note-${Math.random().toString(36).slice(2)}`).current

  function apply(tool: (e: Edit) => Edit) {
    const el = area.current
    if (!el) return
    const next = tool({ value: text, start: el.selectionStart, end: el.selectionEnd })
    // Commit the new text now, then select: a deferred selection could land after the user's next
    // keystrokes and make them overwrite the text just formatted.
    flushSync(() => setText(next.value))
    el.focus()
    el.setSelectionRange(next.start, next.end)
  }

  async function submit(e?: FormEvent) {
    e?.preventDefault()
    if (!text.trim()) return
    setBusy(true)
    try { await onSave(text.trim()); setText(''); setPreview(false); setError(null) } catch (err) { setError(String(err)) }
    setBusy(false)
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    const mod = e.metaKey || e.ctrlKey
    if (mod && e.key === 'Enter') { e.preventDefault(); submit(); return }
    if (e.key === 'Escape' && onCancel) { onCancel(); return }
    const tool = mod && TOOLS.find(t => t.key === e.key.toLowerCase())
    if (tool) { e.preventDefault(); apply(tool.apply) }
  }

  return (
    <form className={s.editor} onSubmit={submit}>
      <div className={s.bar} role="toolbar" aria-label="Formatting">
        <div className={s.tabs}>
          <button type="button" className={preview ? s.tab : s.tabOn} aria-pressed={!preview} onClick={() => setPreview(false)}>Write</button>
          <button type="button" className={preview ? s.tabOn : s.tab} aria-pressed={preview} onClick={() => setPreview(true)}>Preview</button>
        </div>
        {!preview && TOOLS.map(t => (
          <button key={t.label} type="button" className={s.tool} title={t.title} aria-label={t.title} onClick={() => apply(t.apply)}>
            {t.label === 'B' ? <b>B</b> : t.label === 'I' ? <i>I</i> : t.label}
          </button>
        ))}
      </div>
      {preview
        ? <div className={s.preview}>{text.trim() ? <NoteText text={text} /> : <span className={ui.muted}>Nothing to preview.</span>}</div>
        : <>
          <label className="sr-only" htmlFor={id}>{placeholder}</label>
          <textarea id={id} ref={area} className={s.area} rows={3} value={text} placeholder={placeholder} autoFocus={autoFocus}
            onChange={e => setText(e.target.value)} onKeyDown={onKey} />
        </>}
      <div className={s.editorRow}>
        <button className={ui.btnSmall} disabled={busy || !text.trim()}>{initial ? 'Save' : 'Add note'}</button>
        {onCancel && <button type="button" className={ui.btnSmall} onClick={onCancel}>Cancel</button>}
        <span className={ui.muted}>Markdown · ⌘↵ to save</span>
      </div>
      {error && <div className={ui.error}>{error}</div>}
    </form>
  )
}

/** One note: text, optional time it is pinned to, edit and delete. */
export function NoteItem({ note, onChanged, onJump }: { note: NoteView; onChanged: () => void; onJump?: (anchorS: number) => void }) {
  const [editing, setEditing] = useState(false)
  const [armed, setArmed] = useState(false)
  if (editing) {
    return <NoteEditor initial={note.text} placeholder="Edit note" autoFocus onCancel={() => setEditing(false)}
      onSave={t => api.editNote(note.id, t).then(() => { setEditing(false); onChanged() })} />
  }
  return (
    <div className={s.note}>
      <span className={s.mark} aria-hidden="true">✎</span>
      <div className={s.body}>
        {note.clock && note.anchorS !== null && (
          onJump ? <button type="button" className={ui.cite} onClick={() => onJump(note.anchorS!)}>{note.clock}</button>
            : <span className="mono">{note.clock}</span>
        )}
        <NoteText text={note.text} />
      </div>
      <div className={s.actions}>
        <button type="button" className={s.link} onClick={() => setEditing(true)}>Edit</button>
        {armed
          ? <><button type="button" className={s.danger} onClick={() => api.deleteNote(note.id).then(onChanged)}>Delete note</button>
            <button type="button" className={s.link} onClick={() => setArmed(false)}>Keep</button></>
          : <button type="button" className={s.link} onClick={() => setArmed(true)}>Delete</button>}
      </div>
    </div>
  )
}

/** All notes of a conversation, plus a box to add an unpinned one. */
export default function NotesSection({ conversationId, notes, onChanged, onJump }: {
  conversationId: number; notes: NoteView[]; onChanged: () => void; onJump?: (anchorS: number) => void
}) {
  return (
    <section className={s.section}>
      <h3 className={ui.sectionLabel}>Your notes</h3>
      {notes.map(n => <NoteItem key={n.id} note={n} onChanged={onChanged} onJump={onJump} />)}
      <NoteEditor placeholder="Add a note to this conversation…" onSave={t => api.addNote(conversationId, t, null).then(onChanged)} />
    </section>
  )
}
