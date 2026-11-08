import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api, post } from '../lib/api'
import type { Provider } from '../lib/types'
import { Badge, ErrorState, Loading, PageHeader, Panel } from '../components/common'
import { Button } from '../components/ui/button'
import { date } from '../lib/utils'
import { useUi } from '../lib/store'

function ProviderCard({ provider }: { provider: Provider }) {
  const client = useQueryClient()
  const check = useMutation({ mutationFn: () => post<Provider>(`/api/providers/${provider.id}/check`), onSuccess: result => { for (const key of ['providers', 'models', 'dashboard']) void client.invalidateQueries({ queryKey: [key] }); useUi.getState().notify(result.connection === 'available' ? 'Connection verified.' : 'Connection unavailable.') } })
  return <Panel className="max-w-2xl p-5"><div className="mb-5 flex items-center justify-between gap-3"><h2 className="text-base font-medium">{provider.name}</h2><Badge tone={provider.connection === 'available' ? 'green' : 'neutral'}>{provider.connection === 'available' ? 'Connected' : provider.connection === 'connecting' ? 'Connecting' : 'Disconnected'}</Badge></div><dl className="detail-grid"><dt>Models</dt><dd>{provider.availableModels}</dd><dt>Last request</dt><dd>{provider.lastSuccessfulRequest ? date(provider.lastSuccessfulRequest) : '—'}</dd></dl>{provider.lastError && <div className="notice mt-4">{provider.lastError}</div>}{check.isError && <div className="mt-4"><ErrorState error={check.error} /></div>}<div className="mt-5 flex gap-2"><Button variant="secondary" size="sm" disabled={check.isPending} onClick={() => check.mutate()}><RefreshCw size={13} className={check.isPending ? 'animate-spin' : ''} />{check.isPending ? 'Checking...' : 'Check connection'}</Button><Button variant="ghost" size="sm" asChild><Link to="/settings">Session settings</Link></Button></div></Panel>
}
export default function Providers() {
  const query = useQuery({ queryKey: ['providers'], queryFn: () => api<Provider[]>('/api/providers'), refetchInterval: 10_000 })
  return <><PageHeader title="Providers" />{query.isPending ? <Loading /> : query.isError ? <ErrorState error={query.error} retry={() => void query.refetch()} /> : <div className="space-y-4">{query.data.map(provider => <ProviderCard key={provider.id} provider={provider} />)}</div>}</>
}
