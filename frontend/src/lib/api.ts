import type { StreamEvent } from './types'

export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) { super(message) }
}
async function error(response: Response): Promise<never> {
  let body: { error?: { message?: string; code?: string } } = {}
  try { body = await response.json() } catch { /* empty response */ }
  throw new ApiError(body.error?.message ?? `Request failed (${response.status}).`, response.status, body.error?.code)
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } })
  if (!response.ok) return error(response)
  return response.json() as Promise<T>
}
export function post<T>(path: string, body?: unknown) { return api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }) }

export async function streamResponse(body: unknown, signal: AbortSignal, onEvent: (event: StreamEvent) => void) {
  const response = await fetch('/api/playground/responses', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' }, body: JSON.stringify(body), signal })
  if (!response.ok) return error(response)
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('The server did not return an event stream.')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  let previousSequence = -1
  const consume = (block: string) => {
    const lines = block.split(/\r?\n/)
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
    if (!data) return
    const event = JSON.parse(data) as StreamEvent
    if (event.sequence_number !== previousSequence + 1) throw new Error('The server returned an invalid event sequence.')
    previousSequence = event.sequence_number
    if (event.type === 'response.failed') throw new Error(event.response?.error?.message ?? 'Generation failed.')
    if (event.type === 'response.completed') completed = true
    onEvent(event)
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let match: RegExpExecArray | null
      while ((match = /\r?\n\r?\n/.exec(buffer)) !== null) {
        consume(buffer.slice(0, match.index))
        buffer = buffer.slice(match.index + match[0].length)
      }
      if (done) break
    }
    if (buffer.trim()) consume(buffer)
    if (!completed && !signal.aborted) throw new Error('The connection closed before the response completed.')
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
