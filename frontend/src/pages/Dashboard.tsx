import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
import { api } from '../lib/api'
import type { Dashboard } from '../lib/types'
import { Badge, CopyButton, Empty, ErrorState, HistoryTable, Loading, PageHeader, Panel } from '../components/common'
import { Button } from '../components/ui/button'
import { duration } from '../lib/utils'

export default function DashboardPage() {
  const query = useQuery({ queryKey: ['dashboard'], queryFn: () => api<Dashboard>('/api/dashboard'), refetchInterval: 10_000 })
  const navigate = useNavigate()
  if (query.isPending) return <Loading />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  const data = query.data
  const stats = [
    { label: 'Requests', value: data.totalRequests.toLocaleString() },
    { label: 'Today', value: data.requestsToday.toLocaleString() },
    { label: 'Average latency', value: data.totalRequests ? duration(data.averageLatencyMs) : '—' },
    { label: 'Success rate', value: data.successRate == null ? '—' : `${data.successRate.toFixed(1)}%` },
    { label: 'Models', value: String(data.availableModels) },
  ]
  return <>
    <PageHeader title="Dashboard" actions={<Button variant="ghost" size="icon" aria-label="Refresh dashboard" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={15} className={query.isFetching ? 'animate-spin' : ''} /></Button>} />
    {!data.historyEnabled && <div className="notice mb-5">Request history is disabled.</div>}
    <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-5">{stats.map(({ label, value }) => <Panel key={label} className="px-4 py-4"><div className="text-xs text-[var(--muted)]">{label}</div><div className="mt-3 text-2xl font-semibold tracking-tight tabular-nums">{value}</div></Panel>)}</div>
    <div className="mb-5 grid gap-4 lg:grid-cols-2">
      <Panel className="p-5"><h2 className="text-sm font-medium">Base URL</h2><div className="mt-3 flex items-center justify-between gap-2 rounded-md border border-[var(--border)] bg-[var(--bg)] px-3 py-2"><code className="min-w-0 overflow-auto whitespace-nowrap text-xs">{data.baseUrl}</code><CopyButton text={data.baseUrl} label="Copy Base URL" size="icon" /></div></Panel>
      <Panel className="p-5"><div className="mb-4 flex items-center justify-between"><h2 className="text-sm font-medium">Connection</h2><Link to="/providers" className="text-xs text-[var(--muted)] hover:text-[var(--fg)]">Details</Link></div>{data.providers.map(provider => <div className="flex items-center justify-between gap-3 py-1" key={provider.id}><span className="text-sm">{provider.name}</span><Badge tone={provider.connection === 'available' ? 'green' : 'neutral'}>{provider.connection === 'available' ? 'Connected' : provider.connection === 'connecting' ? 'Connecting' : 'Disconnected'}</Badge></div>)}</Panel>
    </div>
    <Panel><div className="panel-heading"><h2 className="text-sm font-medium">Recent requests</h2><Link to="/history" className="text-xs text-[var(--muted)] hover:text-[var(--fg)]">View all</Link></div>{data.recentActivity.length ? <HistoryTable compact rows={data.recentActivity} onSelect={id => navigate(`/history?request=${encodeURIComponent(id)}`)} /> : <Empty title="No requests yet" description="Send a message in Playground to get started." />}</Panel>
  </>
}
