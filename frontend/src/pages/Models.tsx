import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { RefreshCw, Search } from 'lucide-react'
import { api, post } from '../lib/api'
import type { Model } from '../lib/types'
import { Badge, CopyButton, Empty, ErrorState, Loading, PageHeader, Panel } from '../components/common'
import { Button } from '../components/ui/button'
import { Dialog } from '../components/ui/dialog'
import { date } from '../lib/utils'
import { useUi } from '../lib/store'

export default function Models() {
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['models'], queryFn: () => api<Model[]>('/api/models') })
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Model | null>(null)
  const refresh = useMutation({ mutationFn: () => post<Model[]>('/api/models/refresh'), onSuccess: data => { client.setQueryData(['models'], data); void client.invalidateQueries({ queryKey: ['providers'] }); void client.invalidateQueries({ queryKey: ['dashboard'] }); useUi.getState().notify('Models refreshed.') } })
  const models = query.data?.filter(model => `${model.id} ${model.displayName}`.toLowerCase().includes(search.toLowerCase())) ?? []
  return <><PageHeader title="Models" actions={<Button variant="secondary" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending}><RefreshCw size={14} className={refresh.isPending ? 'animate-spin' : ''} />Refresh models</Button>} />
    <label className="relative mb-5 block w-full max-w-xs"><Search size={14} className="absolute left-3 top-3 text-[var(--muted)]" /><input className="input w-full pl-9" placeholder="Search models..." aria-label="Search models" value={search} onChange={e => setSearch(e.target.value)} /></label>
    {refresh.isError && <div className="mb-4"><ErrorState error={refresh.error} /></div>}{query.isPending ? <Loading /> : query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : models.length ? <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{models.map(model => <Panel key={model.id} className="p-5"><div className="flex items-center justify-between gap-3"><h2 className="text-base font-medium">{model.displayName}</h2>{!model.available && <Badge>Unavailable</Badge>}</div><div className="mb-5 mt-2 flex items-center gap-2"><code className="text-xs text-[var(--muted)]">{model.id}</code><CopyButton text={model.id} label="Copy model ID" size="icon" /></div><div className="flex gap-2"><Button size="sm" asChild><Link to={`/playground?model=${encodeURIComponent(model.id)}`}>Open Playground</Link></Button><Button variant="ghost" size="sm" onClick={() => setSelected(model)}>Details</Button></div></Panel>)}</div> : <Panel><Empty title={search ? 'No matching models' : 'No models connected'} description={search ? 'Try another name.' : 'Connect Gemini Web in Settings.'}>{!search && <Button variant="secondary" size="sm" asChild><Link to="/settings">Open Settings</Link></Button>}</Empty></Panel>}
    <Dialog open={selected !== null} onOpenChange={open => !open && setSelected(null)} title={selected?.displayName ?? 'Model details'}>{selected && <div className="space-y-5"><dl className="detail-grid"><dt>Model ID</dt><dd className="font-mono">{selected.id}</dd><dt>Availability</dt><dd>{selected.available ? 'Available' : 'Unavailable'}</dd><dt>Streaming</dt><dd>{selected.streaming ? 'Supported' : 'Unavailable'}</dd><dt>Last check</dt><dd>{date(selected.checkedAt)}</dd><dt>Capabilities</dt><dd>{selected.capabilities.join(', ')}</dd></dl><Button asChild onClick={() => setSelected(null)}><Link to={`/playground?model=${encodeURIComponent(selected.id)}`}>Open Playground</Link></Button></div>}</Dialog>
  </>
}
