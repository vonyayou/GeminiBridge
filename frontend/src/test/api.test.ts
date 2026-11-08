import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, streamResponse } from '../lib/api'
import type { StreamEvent } from '../lib/types'

afterEach(() => vi.unstubAllGlobals())
describe('API client', () => {
  it('returns provider authentication errors without a local login flow', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: { message: 'Reconnect Gemini', code: 'gemini_reauthentication_required' } }), { status: 401 })))
    await expect(api('/api/auth/start')).rejects.toMatchObject({ status: 401, code: 'gemini_reauthentication_required', message: 'Reconnect Gemini' })
  })
  it('decodes split UTF-8 and CRLF SSE events', async () => {
    const payload = 'event: response.created\r\ndata: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1"}}\r\n\r\nevent: response.output_text.delta\r\ndata: {"type":"response.output_text.delta","sequence_number":1,"delta":"Привет 👋"}\r\n\r\ndata: {"type":"response.completed","sequence_number":2,"response":{"status":"completed"}}\r\n\r\n'
    const bytes = new TextEncoder().encode(payload)
    const body = new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i += 3) controller.enqueue(bytes.slice(i, i + 3)); controller.close() } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })))
    const events: StreamEvent[] = []
    await streamResponse({}, new AbortController().signal, event => events.push(event))
    expect(events.map(event => event.type)).toEqual(['response.created', 'response.output_text.delta', 'response.completed'])
    expect(events[1].delta).toBe('Привет 👋')
  })
  it('ignores heartbeat comments while awaiting output', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(':keep-alive\n\ndata: {"type":"response.created","sequence_number":0}\n\n:keep-alive\n\ndata: {"type":"response.completed","sequence_number":1}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })))
    const events: StreamEvent[] = []
    await streamResponse({}, new AbortController().signal, event => events.push(event))
    expect(events.map(event => event.type)).toEqual(['response.created', 'response.completed'])
  })
  it('reports a stream that ended without completion', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('data: {"type":"response.created","sequence_number":0}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })))
    await expect(streamResponse({}, new AbortController().signal, () => {})).rejects.toThrow('before the response completed')
  })
  it('reports provider failures in the stream', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('data: {"type":"response.failed","sequence_number":0,"response":{"error":{"message":"Provider unavailable"}}}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })))
    await expect(streamResponse({}, new AbortController().signal, () => {})).rejects.toThrow('Provider unavailable')
  })
  it('rejects events with missing sequence numbers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('data: {"type":"response.completed","sequence_number":2}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })))
    await expect(streamResponse({}, new AbortController().signal, () => {})).rejects.toThrow('invalid event sequence')
  })
})
