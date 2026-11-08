import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import Playground from '../pages/Playground'
import HistoryPage from '../pages/History'
import Models from '../pages/Models'
import { CopyButton } from '../components/common'
import { useChats } from '../lib/store'
import type { ReactNode } from 'react'

const model = { id: 'gemini-3.1-pro', displayName: '3.1 Pro', provider: 'gemini-web', available: true, mock: false, capabilities: ['text', 'streaming'], streaming: true, checkedAt: '2026-11-08T00:00:00Z' }
const row = { id: 'req_1', timestamp: '2026-11-08T00:00:00Z', requestedModel: 'gemini-3.1-pro', resolvedModel: 'gemini-3.1-pro', httpStatus: 200, durationMs: 120, streaming: true, client: 'playground' }
function mount(element: ReactNode, route = '/') { const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}>{element}</MemoryRouter></QueryClientProvider>) }
function json(data: unknown) { return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } }) }
beforeEach(() => { useChats.setState({ chats: [], activeId: null }); Element.prototype.scrollIntoView = vi.fn() })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear() })

describe('Playground', () => {
  it('migrates saved model IDs and response labels to readable aliases', async () => {
    useChats.setState({ activeId: 'saved', chats: [{ id: 'saved', title: 'Saved chat', model: 'gemini-web-opaque', instructions: '', updatedAt: 1, messages: [{ id: 'reply', role: 'assistant', model: 'gemini-web-opaque', content: 'Previous reply' }] }] })
    vi.stubGlobal('fetch', vi.fn(async () => json([{ ...model, id: 'gemini-3.1-pro', displayName: '3.1 Pro', provider: 'gemini-web', mock: false, aliases: ['gemini-web-opaque'] }])))
    mount(<Playground />)
    await waitFor(() => expect(screen.getByLabelText('Model')).toHaveValue('gemini-3.1-pro'))
    expect(useChats.getState().chats[0].model).toBe('gemini-3.1-pro')
    expect(useChats.getState().chats[0].messages[0].model).toBe('gemini-3.1-pro')
    expect(screen.getByText('Previous reply')).toBeInTheDocument()
  })
  it('receives complete JSON responses when Streaming is disabled', async () => {
    const fetcher = vi.fn(async (path: string) => path === '/api/models' ? json([model]) : json({ id: 'resp_1', model: 'gemini-3.1-pro', status: 'completed', output: [{ content: [{ text: 'Complete response without streaming' }] }] }))
    vi.stubGlobal('fetch', fetcher)
    mount(<Playground />); const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Message')).toBeEnabled())
    await user.click(screen.getByLabelText('Streaming'))
    await user.type(screen.getByLabelText('Message'), 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))
    expect(await screen.findByText('Complete response without streaming')).toBeInTheDocument()
    const call = vi.mocked(fetch).mock.calls.find(call => call[0] === '/api/playground/responses')!
    expect(JSON.parse(call[1]!.body as string).stream).toBe(false)
    expect((call[1]!.headers as Record<string, string>)['X-Bridge-Request-Id']).toMatch(/^pg_[0-9a-f]{32}$/)
    expect(useChats.getState().chats[0].messages[1].model).toBe('gemini-3.1-pro')
  })
  it('keeps the selected model when creating another conversation', async () => {
    const second = { ...model, id: 'other-model', displayName: 'Other Model' }
    vi.stubGlobal('fetch', vi.fn(async () => json([model, second])))
    mount(<Playground />); const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Model')).toBeEnabled())
    await user.selectOptions(screen.getByLabelText('Model'), 'other-model')
    await user.click(screen.getByRole('button', { name: 'New chat' }))
    expect(screen.getByLabelText('Model')).toHaveValue('other-model')
    expect(useChats.getState().chats[0].model).toBe('other-model')
  })
  it('streams output, retains conversation, and sends full context on the next turn', async () => {
    const bodies: Record<string, unknown>[] = []
    const fetcher = vi.fn(async (path: string, options?: RequestInit) => {
      if (path === '/api/models') return json([model])
      if (path === '/api/playground/responses') {
        bodies.push(JSON.parse(options!.body as string))
        const events = [{ type: 'response.created', response: { id: 'resp_1' } }, { type: 'response.output_text.delta', delta: '**Test reply**' }, { type: 'response.completed', response: { id: 'resp_1', status: 'completed', output: [{ content: [{ text: '**Test reply**' }] }] } }]
        return new Response(events.map((event, index) => `data: ${JSON.stringify({ ...event, sequence_number: index })}\n\n`).join(''), { headers: { 'Content-Type': 'text/event-stream' } })
      }
      return json({ cancelled: true })
    })
    vi.stubGlobal('fetch', fetcher)
    mount(<Playground />)
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Message')).toBeEnabled())
    await user.type(screen.getByLabelText('Message'), 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))
    expect(await screen.findByText('Test reply')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send message' })).toBeInTheDocument())
    await user.type(screen.getByLabelText('Message'), 'Second turn')
    await user.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(bodies).toHaveLength(2))
    expect(bodies[1].input).toEqual([{ role: 'user', content: 'Hello' }, { role: 'assistant', content: '**Test reply**' }, { role: 'user', content: 'Second turn' }])
    expect(useChats.getState().chats[0].messages.length).toBe(4)
  })
  it('can stop generation and preserves partial output', async () => {
    let pendingController: ReadableStreamDefaultController<Uint8Array> | undefined
    vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
      if (path === '/api/models') return json([model])
      if (path.includes('/cancel')) { pendingController?.close(); return json({ cancelled: true }) }
      const stream = new ReadableStream<Uint8Array>({ start(controller) { pendingController = controller; controller.enqueue(new TextEncoder().encode('data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1"}}\n\ndata: {"type":"response.output_text.delta","sequence_number":1,"delta":"Partial output"}\n\n')); options?.signal?.addEventListener('abort', () => { try { controller.error(new DOMException('Aborted', 'AbortError')) } catch { /* closed */ } }) } })
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
    }))
    mount(<Playground />)
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Message')).toBeEnabled())
    await user.type(screen.getByLabelText('Message'), 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))
    expect(await screen.findByText('Partial output')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Stop generation' }))
    expect(await screen.findByText('Stopped')).toBeInTheDocument()
    expect(screen.getByText('Partial output')).toBeInTheDocument()
  })
  it('shows request errors without losing user input', async () => {
    vi.stubGlobal('fetch', vi.fn(async path => path === '/api/models' ? json([model]) : new Response(JSON.stringify({ error: { message: 'Gateway busy' } }), { status: 429 })))
    mount(<Playground />)
    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByLabelText('Message')).toBeEnabled())
    await user.type(screen.getByLabelText('Message'), 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))
    expect(await screen.findByText('Gateway busy')).toBeInTheDocument()
    expect(screen.getByText('Hello', { selector: 'p' })).toBeInTheDocument()
  })
})
describe('Request history', () => {
  it('loads details and filters and paginates server-side', async () => {
    const fetcher = vi.fn(async (path: string) => {
      if (path === '/api/models') return json([model])
      if (path === '/api/history/req_1') return json({ ...row, requestHeaders: '{"Authorization":"[REDACTED]"}' })
      return json({ content: [row], totalElements: 16, totalPages: 2, page: 0, size: 15 })
    })
    vi.stubGlobal('fetch', fetcher)
    mount(<HistoryPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'View request req_1' }))
    expect(await screen.findByText('Request headers')).toBeInTheDocument()
    expect(await screen.findByText(/\[REDACTED\]/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close dialog' }))
    await user.selectOptions(screen.getByLabelText('Filter by status'), 'error')
    await waitFor(() => expect(fetcher.mock.calls.some(([path]) => path.includes('status=error'))).toBe(true))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([path]) => path.includes('page=1'))).toBe(true))
  })
})
describe('Models and shared controls', () => {
  it('shows model capabilities in details', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([model])))
    mount(<Models />)
    const user = userEvent.setup()
    expect(await screen.findByText('gemini-3.1-pro')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Details' }))
    expect(await screen.findByText('Capabilities')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open Playground/ })).toHaveAttribute('href', '/playground?model=gemini-3.1-pro')
  })
  it('copies the supplied value', async () => {
    const user = userEvent.setup()
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    mount(<CopyButton text="http://127.0.0.1:8080/v1" label="Copy Base URL" />)
    await user.click(screen.getByRole('button', { name: 'Copy Base URL' }))
    expect(write).toHaveBeenCalledWith('http://127.0.0.1:8080/v1')
    expect(screen.getByText('Copied')).toBeInTheDocument()
    write.mockRestore()
  })
})
