// Typed client for the Spring Boot API. All calls are same-origin.

export interface Tag { label: string; key: string }

export interface ConversationRow {
  id: number; recordingId: number; meetingId: number | null; duplicateOfMeetingId: number | null
  startClock: string; endClock: string; durationS: number; title: string; speakers: Tag[]
  ambient: boolean; stage: string; status: string
}

export interface DayView {
  date: string; conversations: ConversationRow[]; ambientCount: number; meetingCount: number; exportPath: string
}

/** Transcription languages offered everywhere a meeting or import is started. */
export const LANGUAGES: [string, string][] = [['en+hi', 'English + Hindi'], ['en', 'English'], ['hi', 'Hindi'], ['auto', 'Auto-detect']]

export interface SpeakerView {
  clusterId: number; name: string; personId: number | null; isMe: boolean; key: string; channel: string
  speechS: number; suggestion: string | null; suggestionPersonId: number | null
  recognised: boolean; matchScore: number | null
}

export interface SegmentView {
  id: number; startS: number; endS: number; clock: string; speaker: string; key: string
  clusterId: number | null; channel: string; text: string; live: boolean; elapsedS: number
}

export interface NoteView { id: number; text: string; anchorS: number | null; clock: string | null; createdAt: string }

export interface NoteHit { noteId: number; conversationId: number; text: string; date: string; clock: string | null; conversationTitle: string | null }

export interface ActionItem { id: number; text: string; owner: string | null; done: boolean }

export interface ConversationDetail {
  id: number; recordingId: number; meetingId: number | null; source: string; date: string
  startClock: string; endClock: string; durationS: number; title: string; summary: string[]; topics: string[]
  actionItems: ActionItem[]; speakers: SpeakerView[]; segments: SegmentView[]; notes: NoteView[]; attachments: AttachmentView[]; ambient: boolean
  stage: string; status: string; audioMode: string | null; hasAudio: boolean
}

/** A screenshot at a moment of the transcript (R-1). placedBy: when it was taken, attached, or at the end. */
export interface AttachmentView {
  id: number; anchorS: number; clock: string; elapsedS: number; caption: string | null; url: string
  originalName: string | null; placedBy: 'taken' | 'attached' | 'end'; takenAt: string | null
}

export interface Citation { n: number; recordingId: number; conversationId: number; startS: number; endS: number; clock: string; speaker: string }

export interface SearchHit {
  segmentId: number; conversationId: number; recordingId: number; startS: number; endS: number; text: string
  speaker: string; date: string; clock: string; conversationTitle: string | null; score: number
}

export type Provider = 'off' | 'local' | 'openai' | 'anthropic'
export type InsightFeature = 'live_summary' | 'meeting_summary' | 'ask' | 'semantic_search'

/** Optional AI (Settings → Insights): which provider each feature uses, and what leaves the Mac. */
export interface InsightsStatus {
  features: Record<InsightFeature, Provider>
  providers: { local: { used: boolean; up: boolean }; openai: { used: boolean; keySet: boolean }; anthropic: { used: boolean; keySet: boolean } }
  sendsTextOff: string[]
  queue: number
}

export interface Health {
  db: string; ml: string; capture: string
  insights: InsightsStatus
  mlMemory?: { rss_mb: number; gpu_active_mb: number; gpu_cache_mb: number; gpu_peak_mb: number }
  memory: { totalBytes: number; usedBytes: number }
  disk: { freeBytes: number; lowDisk: boolean }
  activeMeetingId: number | null; jobsPending: boolean
}

export const PROVIDER_LABEL: Record<Provider, string> = { off: 'Off', local: 'Local model', openai: 'OpenAI', anthropic: 'Anthropic' }

export interface Person {
  id: number; name: string; aliases: string[]; is_me: boolean; voiceprints: number; voiceprint_sources: string | null
  conversations: number; last_seen: string | null; first_seen: string | null
}

export interface Unknown {
  cluster_id: number; speech_s: number; conversation_id: number; title: string | null; local_date: string
  recording_id: number; conversation_started_at: string; time_zone: string; sample: string | null
}

export interface ImportedFile { recordingId: number; name: string; status: string; startedAt: string | null; startedAtSource: string | null }

export interface Job {
  id: number; type: string; status: string; stage: string | null; attempts: number; error: string | null
  updated_at: string; recording_id: number | null; conversation_id: number | null; meeting_id: number | null
  original_name: string | null; title: string | null; conversation_started_at: string | null
  conversation_stage: string | null; is_ambient: boolean | null; time_zone: string | null; local_date: string | null
}

export interface MeetingSummary {
  summary: string[]; topics: string[]
  decisions: { text: string; owner?: string; citations: { startS: number; endS: number; clock: string }[] }[]
  action_items: { text: string; owner?: string; citations: { startS: number; endS: number; clock: string }[] }[]
  agenda: { item: string; state: string }[]
  updatedAt: string; by?: string; lines?: number
}

/** What one click on Summarize would send: lines and a rough token count. */
export interface SummaryEstimate { lines: number; tokens: number }
export interface IndexStatus { provider: Provider; model?: string; missing: number; tokens?: number; embedded?: number }

export interface MeetingView {
  id: number; title: string; audio_mode: string; started_at: string; ended_at: string | null; status: string
  summary: MeetingSummary | null; pre_context: string | null; conversation_id: number; recording_id: number; live: boolean
  finishing?: string | null; transcriptFile?: string; vocabulary?: string | null
  parts: { part: number; conversation_id: number; recording_id: number; started_at: string; local_date: string; duration_s: number }[]
}

export interface Devices {
  inputs: { index: number; name: string; channels: number; sample_rate: number }[]
  default_input: string | null
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`
    try { const b = await res.json(); msg = b.message || b.detail || b.error || msg } catch { /* not JSON */ }
    throw new Error(msg)
  }
  return res.json() as Promise<T>
}

const get = <T,>(url: string) => fetch(url).then(r => json<T>(r))
const send = <T,>(method: string, url: string, body?: unknown) =>
  fetch(url, { method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    .then(r => json<T>(r))

export const api = {
  health: () => get<Health>('/api/health'),
  days: () => get<{ local_date: string; conversations: number; ambient: number }[]>('/api/days'),
  day: (date: string) => get<DayView>(`/api/days/${date}`),
  conversation: (id: number) => get<ConversationDetail>(`/api/conversations/${id}`),
  setActionDone: (id: number, done: boolean) => send('PATCH', `/api/action-items/${id}`, { done }),
  transcribeAmbient: (id: number) => send('POST', `/api/conversations/${id}/transcribe`),
  deleteConversation: (id: number) => send('DELETE', `/api/conversations/${id}`),
  deleteDay: (date: string) => send('DELETE', `/api/days/${date}`),
  audioUrl: (recordingId: number, start: number, end: number) => `/api/audio/${recordingId}?start=${start.toFixed(2)}&end=${end.toFixed(2)}`,

  addNote: (conversationId: number, text: string, anchorS: number | null) =>
    send<{ id: number }>('POST', `/api/conversations/${conversationId}/notes`, { text, anchorS }),
  editNote: (id: number, text: string) => send('PATCH', `/api/notes/${id}`, { text }),
  deleteNote: (id: number) => send('DELETE', `/api/notes/${id}`),

  search: (q: string, meaning = false) =>
    get<{ transcript: SearchHit[]; notes: NoteHit[] }>(`/api/search?q=${encodeURIComponent(q)}${meaning ? '&meaning=true' : ''}`),

  people: () => get<{ people: Person[]; unknowns: Unknown[] }>('/api/people'),
  assignCluster: (id: number, body: { personId?: number | null; name?: string; me?: boolean; addToLibrary: boolean }) =>
    send('PATCH', `/api/speaker-clusters/${id}`, { personId: null, name: null, me: false, ...body }),
  updatePerson: (id: number, name: string, aliases: string[]) => send('PATCH', `/api/people/${id}`, { name, aliases }),
  mergePerson: (id: number, intoId: number) => send('POST', `/api/people/${id}/merge`, { intoId }),
  deletePerson: (id: number) => send('DELETE', `/api/people/${id}`),
  rematch: () => send('POST', '/api/people/rematch'),
  enrollSegments: (segmentIds: number[]) => send('POST', '/api/enroll/segments', { segmentIds }),
  enrollClip: (blob: Blob, filename: string, source: 'laptop_mic' | 'upload') => {
    const fd = new FormData()
    fd.append('file', blob, filename)
    fd.append('source', source)
    return fetch('/api/enroll', { method: 'POST', body: fd }).then(r => json(r))
  },

  importFiles: (files: File[], timeZone: string, clockOffsetS: number, language: string) => {
    const fd = new FormData()
    files.forEach(f => { fd.append('files', f); fd.append('lastModified', String(f.lastModified)) })
    fd.append('timeZone', timeZone)
    fd.append('clockOffsetS', String(clockOffsetS))
    fd.append('language', language)
    return fetch('/api/import', { method: 'POST', body: fd }).then(r => json<{ importId: number; files: ImportedFile[] }>(r))
  },
  importFilesOf: (id: number) => get<Record<string, unknown>[]>(`/api/imports/${id}`),
  adjustImport: (id: number, timeZone: string, clockOffsetS: number) =>
    send<Record<string, unknown>[]>('PATCH', `/api/imports/${id}`, { timeZone, clockOffsetS }),
  processImport: (id: number) => send('POST', `/api/imports/${id}/process`),
  jobs: () => get<Job[]>('/api/jobs?limit=80'),

  settings: () => get<Record<string, unknown>>('/api/settings'),
  saveSettings: (changes: Record<string, unknown>) => send<Record<string, unknown>>('PUT', '/api/settings', changes),

  preflight: () => get<{ devices: Devices; activeMeetingId: number | '' }>('/api/meetings/preflight'),
  meetings: () => get<{ id: number; title: string; audio_mode: string; started_at: string; ended_at: string | null; status: string; parts: number; duration_s: number | null }[]>('/api/meetings'),
  startMeeting: (body: Record<string, unknown>) => send<{ meetingId: number }>('POST', '/api/meetings', body),
  stopMeeting: (id: number) => send('POST', `/api/meetings/${id}/stop`),
  resumeMeeting: (id: number, body: Record<string, unknown>) => send<{ meetingId: number }>('POST', `/api/meetings/${id}/resume`, body),
  switchMeetingLanguage: (id: number, language: string) => send<{ language: string | null }>('POST', `/api/meetings/${id}/language`, { language }),
  assignLine: (segmentId: number, body: { personId?: number | null; name?: string; me?: boolean; addToLibrary: boolean }) =>
    send<{ clusterId: number }>('PATCH', `/api/segments/${segmentId}/speaker`, { personId: null, name: null, me: false, ...body }),
  switchMeetingDevice: (id: number, device: string) => send<{ device: string | null }>('POST', `/api/meetings/${id}/device`, { device }),
  meeting: (id: number) => get<MeetingView>(`/api/meetings/${id}`),
  meetingContext: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return fetch('/api/meetings/context', { method: 'POST', body: fd }).then(r => json<{ text: string }>(r))
  },
  meetingSummaryEstimate: (id: number) => get<SummaryEstimate>(`/api/meetings/${id}/summary`),
  summarizeMeeting: (id: number) => send<MeetingSummary>('POST', `/api/meetings/${id}/summary`),
  conversationSummaryEstimate: (id: number) => get<SummaryEstimate>(`/api/conversations/${id}/summary`),
  summarizeConversation: (id: number) => send<{ ok: boolean }>('POST', `/api/conversations/${id}/summary`),
  indexStatus: () => get<IndexStatus>('/api/search/index'),
  buildIndex: () => send<{ embedded: number }>('POST', '/api/search/index'),
  switchMeetingVocabulary: (id: number, vocabulary: string) => send<{ vocabulary: string }>('POST', `/api/meetings/${id}/vocabulary`, { vocabulary }),
  addAttachment: (meetingId: number, file: File, caption?: string) => {
    const fd = new FormData()
    fd.append('file', file, file.name || 'screenshot.png')
    // When the file was last modified: for a dropped screenshot, about when it was taken.
    fd.append('lastModified', String(file.lastModified))
    if (caption) fd.append('caption', caption)
    return fetch(`/api/meetings/${meetingId}/attachments`, { method: 'POST', body: fd })
      .then(r => json<{ id: number; conversationId: number; placedBy: string }>(r))
  },
  captionAttachment: (id: number, caption: string) => send('PATCH', `/api/attachments/${id}`, { caption }),
  deleteAttachment: (id: number) => send('DELETE', `/api/attachments/${id}`),
  qaHistory: (scopeType: string, scopeRef: string) =>
    get<{ id: number; question: string; answer: string; citations: string }[]>(`/api/qa?scopeType=${scopeType}&scopeRef=${encodeURIComponent(scopeRef)}`),
}

export interface AskHandlers {
  onToken: (t: string) => void
  onCitations: (c: Citation[]) => void
  onDone: () => void
  onError: (msg: string) => void
}

/** POST /api/ask and read its server-sent events as they stream. */
export async function ask(body: { scopeType: string; scopeRef: string; question: string; action?: string | null }, h: AskHandlers) {
  const res = await fetch('/api/ask', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(body) })
  if (!res.ok || !res.body) { h.onError(`${res.status} ${res.statusText}`); return }
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let i
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i)
      buf = buf.slice(i + 2)
      let event = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
      }
      if (!data.length) continue
      const payload = JSON.parse(data.join('\n'))
      if (event === 'token') h.onToken(payload.t)
      else if (event === 'citations') h.onCitations(payload)
      else if (event === 'done') h.onDone()
      else if (event === 'error') h.onError(payload.message)
    }
  }
}

export function todayLocal(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function fmtDuration(s: number): string {
  const m = Math.round(s / 60)
  return m < 60 ? `${Math.max(1, m)} min` : `${Math.floor(m / 60)} h ${m % 60} min`
}

const PALETTE = ['#0F7369', '#6B3FB5', '#A84B0C', '#8A5A00', '#2F6B2F', '#9B2C6B', '#3D5A99']
const SOFT = ['#E2F2EF', '#EEE8F8', '#FBE9DF', '#F6EEDA', '#E6F1E6', '#F6E4EE', '#E5EAF5']

/** Stable speaker colours: blue for Me, orange for Others, a palette per known person. */
export function speakerColor(key: string): { fg: string; bg: string } {
  if (key === 'me') return { fg: 'var(--me)', bg: 'var(--accent-soft)' }
  if (key === 'others') return { fg: 'var(--others)', bg: 'var(--others-soft)' }
  if (key.startsWith('p')) {
    const n = Number(key.slice(1)) % PALETTE.length
    return { fg: PALETTE[n], bg: SOFT[n] }
  }
  return { fg: 'var(--muted)', bg: 'var(--border-soft)' }
}

/** The segment starting closest to a cited offset. */
export function nearest<T extends { startS: number }>(segments: T[], at: number): T | undefined {
  let best: T | undefined
  for (const s of segments) if (!best || Math.abs(s.startS - at) < Math.abs(best.startS - at)) best = s
  return best
}

/** Elapsed time as m:ss, or h:mm:ss from an hour on. */
export function fmtElapsed(sec: number): string {
  const t = Math.max(0, Math.floor(sec))
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), x = t % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(x).padStart(2, '0')}` : `${m}:${String(x).padStart(2, '0')}`
}
