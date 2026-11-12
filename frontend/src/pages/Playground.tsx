import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowUp, Plus, Settings2, Square, Trash2 } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { api, post, streamResponse } from '../lib/api'
import type { Model, ResponseObject } from '../lib/types'
import { useChats, useUi } from '../lib/store'
import { Badge, CopyButton, ErrorState, Loading } from '../components/common'
import { Button } from '../components/ui/button'
import { Dialog } from '../components/ui/dialog'
import { duration } from '../lib/utils'

export default function Playground() {
  const models = useQuery({ queryKey: ['models'], queryFn: () => api<Model[]>('/api/models') })
  const client = useQueryClient()
  const { chats, activeId, createChat, selectChat, updateChat, deleteChat, addMessage, updateMessage } = useChats()
  const [params, setParams] = useSearchParams()
  const [draft, setDraft] = useState('')
  const [generating, setGenerating] = useState(false)
  const [streaming, setStreaming] = useState(true)
  const [elapsedMs, setElapsedMs] = useState(0)
  const startedAt = useRef(0)
  const [options, setOptions] = useState(false)
  const [clear, setClear] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const abort = useRef<AbortController | null>(null)
  const responseId = useRef<string | null>(null)
  const end = useRef<HTMLDivElement>(null)
  const chat = chats.find(c => c.id === activeId)
  const selectedModel = models.data?.find(m => m.id === chat?.model)
  const supportsThinking = selectedModel?.capabilities.includes('thinking') ?? false
  const requestedModel = params.get('model')
  useEffect(() => {
    if (!models.data) return
    if (requestedModel) { createChat(requestedModel); setParams({}, { replace: true }); return }
    if (!useChats.getState().chats.some(c => c.id === useChats.getState().activeId) && models.data.length) createChat(models.data[0].id)
  }, [models.data, requestedModel, createChat, setParams])
  useEffect(() => {
    if (!models.data) return
    for (const saved of useChats.getState().chats) {
      const model = models.data.find(model => model.aliases?.includes(saved.model))
      if (model && model.id !== saved.model) updateChat(saved.id, { model: model.id })
      for (const message of saved.messages) {
        const source = models.data.find(model => message.model && model.aliases?.includes(message.model))
        if (source && source.id !== message.model) updateMessage(saved.id, message.id, { model: source.id })
      }
    }
  }, [models.data, updateChat, updateMessage])
  useEffect(() => { end.current?.scrollIntoView({ behavior: generating ? 'instant' : 'smooth', block: 'end' }) }, [chat?.messages, generating])
  useEffect(() => {
    if (!generating) return
    const timer = setInterval(() => setElapsedMs(performance.now() - startedAt.current), 500)
    return () => clearInterval(timer)
  }, [generating])
  useEffect(() => () => { abort.current?.abort(); if (responseId.current) void post(`/api/playground/requests/${responseId.current}/cancel`).catch(() => {}) }, [])
  const stop = () => { if (responseId.current) void post(`/api/playground/requests/${responseId.current}/cancel`).catch(() => {}); abort.current?.abort() }
  const send = async () => {
    if (!chat || !draft.trim() || generating || !selectedModel?.available) return
    const content = draft.trim()
    if (chat.messages.length >= 198) { setError(new Error('This conversation has reached its message limit. Start a new conversation.')); return }
    const controller = new AbortController()
    abort.current = controller; responseId.current = streaming ? null : `pg_${crypto.randomUUID().replaceAll('-', '')}`
    const messageId = crypto.randomUUID()
    const input = [...chat.messages.filter(m => !m.error && !m.stopped && m.content).map(m => ({ role: m.role, content: m.content })), { role: 'user', content }]
    addMessage(chat.id, { id: crypto.randomUUID(), role: 'user', content })
    addMessage(chat.id, { id: messageId, role: 'assistant', model: chat.model, thinking: supportsThinking && chat.thinking, content: '' })
    const started = performance.now()
    startedAt.current = started
    setElapsedMs(0); setDraft(''); setError(null); setGenerating(true)
    let text = ''
    let reasoning = ''
    const finish = (response: ResponseObject) => updateMessage(chat.id, messageId, { content: response.output.flatMap(i => i.content ?? []).map(p => p.text).join(''), reasoning: response.output.flatMap(i => i.summary ?? []).map(p => p.text).join(''), usage: response.usage, durationMs: performance.now() - started })
    const body = { model: chat.model, instructions: chat.instructions || undefined, input, stream: streaming, store: false, thinking: supportsThinking ? chat.thinking ?? false : undefined }
    try {
      if (!streaming) {
        finish(await api<ResponseObject>('/api/playground/responses', { method: 'POST', body: JSON.stringify(body), signal: controller.signal, headers: { 'X-Bridge-Request-Id': responseId.current! } }))
      } else await streamResponse(body, controller.signal, event => {
        if (event.type === 'response.created') responseId.current = event.response!.id
        if (event.type === 'response.output_text.delta') { text += event.delta ?? ''; updateMessage(chat.id, messageId, { content: text }) }
        if (event.type === 'response.reasoning_summary_text.delta') { reasoning += event.delta ?? ''; updateMessage(chat.id, messageId, { reasoning }) }
        if (event.type === 'response.completed') finish(event.response!)
      })
    } catch (e) {
      if (controller.signal.aborted) updateMessage(chat.id, messageId, { stopped: true, durationMs: performance.now() - started })
      else {
        const interrupted = e instanceof TypeError && /input stream|fetch|network/i.test(e.message)
        updateMessage(chat.id, messageId, { error: interrupted ? 'The connection to GeminiBridge was interrupted. Disable Streaming and resend, or check Request History for the failure.' : e instanceof Error ? e.message : 'Generation failed.', durationMs: performance.now() - started })
      }
    } finally {
      if (controller.signal.aborted) updateMessage(chat.id, messageId, { stopped: true, durationMs: performance.now() - started })
      setGenerating(false); abort.current = null; responseId.current = null; void client.invalidateQueries({ queryKey: ['dashboard'] }); void client.invalidateQueries({ queryKey: ['history'] })
    }
  }
  if (models.isPending) return <Loading label="Loading models" />
  if (models.isError) return <ErrorState error={models.error} retry={() => void models.refetch()} />
  return <div className="playground-layout">
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--border)] pb-4"><h1 className="text-xl font-semibold tracking-tight">Playground</h1><div className="flex items-center gap-2"><Button variant="secondary" size="sm" onClick={() => { const id = createChat(chat?.model ?? models.data[0]?.id ?? ''); if (chat?.thinking) updateChat(id, { thinking: true }); setDraft(''); setError(null) }} disabled={generating || !models.data.length}><Plus size={14} />New chat</Button><Button variant="ghost" size="icon" aria-label="Clear conversation" disabled={generating || !chat} onClick={() => setClear(true)}><Trash2 size={15} /></Button></div></div>
    <div className="mt-4 flex flex-wrap items-center gap-3"><label><span className="sr-only">Model</span><select aria-label="Model" className="input min-w-48 font-mono text-xs" value={chat?.model ?? ''} disabled={generating || !chat} onChange={e => updateChat(chat!.id, { model: e.target.value })}><option value="" disabled>Select a model</option>{models.data.map(m => <option key={m.id} value={m.id}>{m.displayName}</option>)}{chat?.model && !selectedModel && <option value={chat.model}>Unavailable model</option>}</select></label><Button variant="ghost" size="sm" onClick={() => setOptions(true)} disabled={!chat}><Settings2 size={14} />System instructions{chat?.instructions && <span className="h-1 w-1 rounded-full bg-[var(--accent)]" />}</Button><label className="ml-auto max-w-full"><span className="sr-only">Saved conversations</span><select className="input max-w-52 text-xs" value={activeId ?? ''} disabled={generating || !chats.length} onChange={e => { selectChat(e.target.value); setDraft(''); setError(null) }}><option value="" disabled>Saved conversations</option>{chats.map(c => <option key={c.id} value={c.id}>{c.title}</option>)}</select></label></div>
    {!!chat?.messages.length && !selectedModel?.available && <div className="notice mt-4">{models.data.length ? 'This model is unavailable. Select an available model to send a request.' : 'Connect Gemini Web in Settings to load your models.'}</div>}
    <div className="chat-scroll">{!chat?.messages.length ? <div className="chat-empty"><h2 className="text-lg font-medium">{selectedModel?.available ? 'Start a conversation' : 'Connect Gemini Web'}</h2>{!selectedModel?.available && <Button variant="secondary" size="sm" className="mt-4" asChild><Link to="/settings">Open Settings</Link></Button>}</div> : <div className="mx-auto w-full max-w-3xl space-y-6 px-1 py-7">{chat.messages.map(message => <article key={message.id} className={`chat-message ${message.role}`}><div className="min-w-0 flex-1"><div className="mb-2 flex items-center gap-2 text-xs font-medium">{message.role === 'user' ? 'You' : models.data.find(model => model.id === message.model)?.displayName ?? 'Assistant'}{message.thinking && <Badge>Thinking</Badge>}</div>{message.reasoning && <details className="mb-3 rounded-lg border border-[var(--border)] px-3 py-2"><summary className="cursor-pointer text-xs text-[var(--muted)]">Thinking</summary><div className="markdown mt-3 text-[var(--muted)]"><ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{message.reasoning}</ReactMarkdown></div></details>}{message.content ? <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{message.content}</ReactMarkdown></div> : !message.error && !message.stopped && <div role="status" aria-label="Generating response" className="typing"><span /><span /><span /></div>}{message.error && <div className="mt-3"><ErrorState error={new Error(message.error)} /></div>}{message.role === 'assistant' && (message.durationMs != null || message.stopped) && <div className="mt-3 flex flex-wrap items-center gap-3 text-[10px] text-[var(--subtle)]"><span>{duration(message.durationMs)}</span>{message.usage && <span>{message.usage.input_tokens} input · {message.usage.output_tokens} output tokens</span>}{message.stopped && <Badge tone="amber">Stopped</Badge>}{message.content && <CopyButton text={message.content} label="Copy response" size="icon" />}</div>}</div></article>)}<div ref={end} /></div>}</div>
    <div className="chat-composer mx-auto w-full max-w-3xl">{error != null && <div className="mb-3"><ErrorState error={error} /></div>}<form className="composer-box" onSubmit={e => { e.preventDefault(); void send() }}><textarea className="min-h-16 w-full resize-none bg-transparent px-4 pb-2 pt-4 text-sm outline-none placeholder:text-[var(--subtle)]" rows={2} placeholder="Send a message..." aria-label="Message" value={draft} onChange={e => setDraft(e.target.value)} maxLength={32000} disabled={generating || !selectedModel?.available} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send() } }} /><div className="flex items-center justify-between px-3 pb-3"><span className="text-[10px] text-[var(--subtle)]">{generating ? `${streaming ? 'Waiting for model output' : 'Waiting for complete response'} · ${duration(elapsedMs)}` : 'Enter to send · Shift + Enter for a new line'}</span>{generating ? <Button size="icon" variant="secondary" type="button" onClick={stop} aria-label="Stop generation"><Square size={14} fill="currentColor" /></Button> : <Button size="icon" aria-label="Send message" disabled={!draft.trim() || !selectedModel?.available || !chat}><ArrowUp size={17} /></Button>}</div></form><div className="flex justify-end gap-4 px-1 py-3 text-[10px] text-[var(--subtle)]">{supportsThinking && <label className="flex items-center gap-2" title="Request extended thinking from Gemini. Standard mode may still use reasoning."><span>Thinking</span><input aria-label="Thinking" type="checkbox" className="toggle" checked={chat?.thinking ?? false} disabled={generating || !chat} onChange={e => chat && updateChat(chat.id, { thinking: e.target.checked })} /></label>}<label className="flex items-center gap-2"><span>Streaming</span><input aria-label="Streaming" type="checkbox" className="toggle" checked={streaming} disabled={generating} onChange={e => setStreaming(e.target.checked)} /></label></div></div>
    <Dialog open={options} onOpenChange={setOptions} title="System instructions" description="Sent with each request."><textarea className="input min-h-40 w-full resize-y font-mono text-xs leading-6" aria-label="System instructions" placeholder="Describe how the model should respond..." value={chat?.instructions ?? ''} disabled={generating} maxLength={16000} onChange={e => chat && updateChat(chat.id, { instructions: e.target.value })} /><div className="mt-4 flex justify-end"><Button onClick={() => setOptions(false)}>Done</Button></div></Dialog>
    <Dialog open={clear} onOpenChange={setClear} title="Delete this conversation?" description="Removes the saved chat from this browser. API request history is separate."><div className="flex justify-end gap-2"><Button variant="secondary" onClick={() => setClear(false)}>Cancel</Button><Button variant="destructive" onClick={() => { if (chat) deleteChat(chat.id); setClear(false); setDraft(''); useUi.getState().notify('Conversation deleted.') }}>Delete conversation</Button></div></Dialog>
  </div>
}
