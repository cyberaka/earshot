import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react'
import { api, type AttachmentView } from '../api'
import ui from './ui.module.css'
import s from './Screenshots.module.css'

const PLACED: Record<AttachmentView['placedBy'], string> = {
  taken: 'placed when it was taken',
  attached: 'placed when it was attached',
  end: 'attached after the meeting: placed at the end',
}

/** A screenshot among the transcript lines (R-1): the image, an optional caption, and its time. */
export function ScreenshotLine({ shot, time, editable, onChanged }: {
  shot: AttachmentView; time: string; editable: boolean; onChanged: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [caption, setCaption] = useState(shot.caption ?? '')
  const [armed, setArmed] = useState(false)
  async function save() {
    await api.captionAttachment(shot.id, caption)
    setEditing(false)
    onChanged()
  }
  return (
    <div className={s.shot}>
      <span className={s.clock} title={PLACED[shot.placedBy]}>{time}</span>
      <div className={s.body}>
        <a href={shot.url} target="_blank" rel="noreferrer" title={shot.originalName ?? 'Screenshot'}>
          <img className={s.thumb} src={shot.url} alt={shot.caption ?? 'Screenshot'} loading="lazy" />
        </a>
        {editing ? (
          <form className={s.captionForm} onSubmit={e => { e.preventDefault(); save() }}>
            <label htmlFor={`cap-${shot.id}`} className="sr-only">Caption</label>
            <input id={`cap-${shot.id}`} className={ui.input} value={caption} autoFocus maxLength={300}
              onChange={e => setCaption(e.target.value)} placeholder="What is on this slide?" />
            <button className={ui.btnSmall}>Save</button>
            <button type="button" className={s.link} onClick={() => { setEditing(false); setCaption(shot.caption ?? '') }}>Cancel</button>
          </form>
        ) : (
          <div className={s.meta}>
            <span className={s.caption}>📎 {shot.caption ?? 'Screenshot'}</span>
            {editable && <>
              <button type="button" className={s.link} onClick={() => setEditing(true)}>{shot.caption ? 'Edit caption' : 'Add caption'}</button>
              {armed
                ? <><button type="button" className={s.danger} onClick={() => api.deleteAttachment(shot.id).then(onChanged)}>Delete screenshot</button>
                  <button type="button" className={s.link} onClick={() => setArmed(false)}>Keep</button></>
                : <button type="button" className={s.link} onClick={() => setArmed(true)}>Delete</button>}
            </>}
          </div>
        )}
      </div>
    </div>
  )
}

/**
 * Paste (Cmd+V) or drop images anywhere on the meeting page, or pick files: each becomes a
 * screenshot in the transcript. A pasted image is placed now; a dropped screenshot file at the
 * time it was taken (from its name or modified time) when that falls inside the meeting.
 */
export function useScreenshotDrop(meetingId: number, onAdded: () => void) {
  const [dragging, setDragging] = useState(false)
  const [busy, setBusy] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const upload = useCallback(async (files: File[]) => {
    const images = files.filter(f => f.type.startsWith('image/'))
    if (!images.length) { if (files.length) setError('Only images can be attached (PNG, JPEG, GIF or WebP).'); return }
    setError(null)
    setBusy(n => n + images.length)
    for (const f of images) {
      try { await api.addAttachment(meetingId, f) } catch (e) { setError(String(e)) }
      setBusy(n => n - 1)
    }
    onAdded()
  }, [meetingId, onAdded])

  useEffect(() => {
    function onPaste(e: ClipboardEvent) {
      const files = [...(e.clipboardData?.files ?? [])].filter(f => f.type.startsWith('image/'))
      if (files.length) { e.preventDefault(); upload(files) }
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
  }, [upload])

  const dropProps = {
    onDragOver: (e: DragEvent) => { if ([...e.dataTransfer.types].includes('Files')) { e.preventDefault(); setDragging(true) } },
    onDragLeave: (e: DragEvent) => { if (e.currentTarget === e.target) setDragging(false) },
    onDrop: (e: DragEvent) => { if (e.dataTransfer.files.length) { e.preventDefault(); setDragging(false); upload([...e.dataTransfer.files]) } },
  }
  const picker = (
    <input ref={input} type="file" accept="image/png,image/jpeg,image/gif,image/webp" multiple hidden
      onChange={e => { upload([...(e.target.files ?? [])]); e.target.value = '' }} />
  )
  return { dragging, busy, error, setError, dropProps, picker, pick: () => input.current?.click() }
}
