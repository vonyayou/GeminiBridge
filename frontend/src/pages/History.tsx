import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { ChevronLeft, ChevronRight, Search, Trash2 } from 'lucide-react'
import { api } from '../lib/api'
import type { HistoryDetail, HistoryPage as Page, Model } from '../lib/types'
import { Badge, CopyButton, Empty, ErrorState, HistoryTable, Loading, PageHeader, Panel } from '../components/common'
import { Button } from '../components/ui/button'
import { Dialog } from '../components/ui/dialog'
import { date, duration, statusLabel } from '../lib/utils'
import { useUi } from '../lib/store'

function JsonBlock({ title, value }: { title: string; value?: string }) {
  let formatted = value
  try { if (value) formatted = JSON.stringify(JSON.parse(value), null, 2) } catch { /* raw content */ }
  return <div className="mt-5"><div className="mb-2 flex items-center justify-between"><h3 className="text-xs font-medium">{title}</h3>{formatted && <CopyButton text={formatted} label={`Copy ${title.toLowerCase()}`} />}</div>{formatted ? <pre className="json-block">{formatted}</pre> : <div className="rounded-md border border-dashed border-[var(--border)] p-4 text-xs text-[var(--muted)]">Content was not saved for this request.</div>}</div>
}
function RequestDetails({ id }: { id: string }) {
  const query = useQuery({ queryKey: ['history-detail', id], queryFn: () => api<HistoryDetail>(`/api/history/${encodeURIComponent(id)}`) })
  if (query.isPending) return <Loading label="Loading request details" />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  const row = query.data
  return <><div className="mb-5 flex items-center justify-between"><Badge tone={row.httpStatus < 300 ? 'green' : row.httpStatus === 499 ? 'amber' : 'red'}>{statusLabel(row.httpStatus)} · HTTP {row.httpStatus}</Badge><span className="text-xs text-[var(--muted)]">{date(row.timestamp)}</span></div><dl className="detail-grid"><dt>Request ID</dt><dd className="break-all font-mono text-[11px]">{row.id}</dd><dt>Requested model</dt><dd>{row.requestedModel ?? 'Unknown'}</dd><dt>Resolved model</dt><dd>{row.resolvedModel ?? 'Not resolved'}</dd><dt>Duration</dt><dd>{duration(row.durationMs)}</dd><dt>Usage</dt><dd>{row.inputTokens == null ? 'Unavailable' : `${row.inputTokens} input · ${row.outputTokens ?? 'unknown'} output`}</dd><dt>Streaming</dt><dd>{row.streaming ? 'Yes' : 'No'}</dd><dt>Client</dt><dd>{row.client}</dd></dl>{row.errorMessage && <div className="mt-5"><ErrorState error={new Error(row.errorMessage)} /></div>}<JsonBlock title="Request headers" value={row.requestHeaders} /><JsonBlock title="Request body" value={row.requestBody} /><JsonBlock title="Response body" value={row.responseBody} /><p className="mt-4 text-[10px] leading-5 text-[var(--subtle)]">Authorization headers and cookies are redacted. Body storage must be enabled before a request is made.</p></>
}
export default function HistoryPage() {
  const [params, setParams] = useSearchParams()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [debounced, setDebounced] = useState('')
  const [model, setModel] = useState('')
  const [status, setStatus] = useState('all')
  const [direction, setDirection] = useState('desc')
  const [clearOpen, setClearOpen] = useState(false)
  const client = useQueryClient()
  const id = params.get('request')
  useEffect(() => { const timer = setTimeout(() => { setDebounced(search); setPage(0) }, 250); return () => clearTimeout(timer) }, [search])
  const queryString = new URLSearchParams({ page: String(page), size: '15', search: debounced, model, status, direction })
  const query = useQuery({ queryKey: ['history', page, debounced, model, status, direction], queryFn: () => api<Page>(`/api/history?${queryString}`), refetchInterval: 10_000 })
  const models = useQuery({ queryKey: ['models'], queryFn: () => api<Model[]>('/api/models') })
  const clear = useMutation({ mutationFn: () => api('/api/history', { method: 'DELETE' }), onSuccess: () => { setClearOpen(false); setPage(0); setParams({}); void client.invalidateQueries({ queryKey: ['history'] }); void client.invalidateQueries({ queryKey: ['dashboard'] }); client.removeQueries({ queryKey: ['history-detail'] }); useUi.getState().notify('Request history cleared.') } })
  return <><PageHeader title="Request history" actions={<Button variant="secondary" size="sm" onClick={() => setClearOpen(true)} disabled={!query.data?.totalElements}><Trash2 size={13} />Clear history</Button>} />
    <Panel><div className="flex flex-wrap items-center gap-3 border-b border-[var(--border)] p-4"><label className="relative min-w-40 flex-1"><Search size={14} className="absolute left-3 top-3 text-[var(--muted)]" /><input className="input w-full pl-9" placeholder="Search request ID, model, or client..." aria-label="Search request history" value={search} maxLength={256} onChange={e => setSearch(e.target.value)} /></label><label><span className="sr-only">Filter by model</span><input className="input w-40 text-xs" list="history-models" placeholder="All models" aria-label="Filter by model" value={model} maxLength={128} onChange={e => { setModel(e.target.value); setPage(0) }} /><datalist id="history-models">{models.data?.map(m => <option key={m.id} value={m.id} />)}</datalist></label><label><span className="sr-only">Filter by status</span><select className="input text-xs" value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}><option value="all">All statuses</option><option value="success">Success</option><option value="error">Error</option><option value="cancelled">Cancelled</option></select></label><label><span className="sr-only">Sort by date</span><select className="input text-xs" value={direction} onChange={e => { setDirection(e.target.value); setPage(0) }}><option value="desc">Newest first</option><option value="asc">Oldest first</option></select></label></div>
      {query.isPending ? <Loading label="Loading request history" /> : query.isError ? <div className="p-5"><ErrorState error={query.error} retry={() => void query.refetch()} /></div> : query.data.content.length ? <HistoryTable rows={query.data.content} onSelect={request => setParams({ request })} /> : <Empty title={search || model || status !== 'all' ? 'No matching requests' : 'No requests yet'} description="Requests appear after they finish. Try Playground or send a request through the Responses API." />}
      <div className="flex items-center justify-between border-t border-[var(--border)] px-5 py-3"><span className="text-xs text-[var(--muted)]">{query.data?.totalElements ?? 0} requests · 15 per page</span><div className="flex items-center gap-2"><Button variant="ghost" size="icon" aria-label="Previous page" disabled={page === 0 || query.isFetching} onClick={() => setPage(p => p - 1)}><ChevronLeft size={15} /></Button><span className="text-xs text-[var(--muted)]">{page + 1} / {Math.max(1, query.data?.totalPages ?? 1)}</span><Button variant="ghost" size="icon" aria-label="Next page" disabled={!query.data || page + 1 >= query.data.totalPages || query.isFetching} onClick={() => setPage(p => p + 1)}><ChevronRight size={15} /></Button></div></div>
    </Panel><p className="mt-4 text-[11px] text-[var(--subtle)]">A dash in token columns means the provider did not report usage.</p>
    <Dialog open={!!id} onOpenChange={open => !open && setParams({})} title="Request details" description="Recorded metadata and stored content, with secrets redacted." className="max-w-3xl">{id && <RequestDetails id={id} />}</Dialog>
    <Dialog open={clearOpen} onOpenChange={setClearOpen} title="Clear request history?" description="All API request records and bodies will be deleted from SQLite. Saved Playground chats are separate.">{clear.isError && <div className="mb-4"><ErrorState error={clear.error} /></div>}<div className="flex justify-end gap-2"><Button variant="secondary" disabled={clear.isPending} onClick={() => setClearOpen(false)}>Cancel</Button><Button variant="destructive" disabled={clear.isPending} onClick={() => clear.mutate()}>{clear.isPending ? 'Clearing...' : 'Clear history'}</Button></div></Dialog>
  </>
}
