import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Save } from 'lucide-react'
import { BrowserSession } from '../components/BrowserSession'
import { api } from '../lib/api'
import type { AuthStatus, Settings, Model } from '../lib/types'
import { CopyButton, ErrorState, Field, Loading, PageHeader, Panel, Toggle } from '../components/common'
import { Button } from '../components/ui/button'
import { useUi } from '../lib/store'

function Editor({ initial, auth, modelId }: { initial: Settings; auth?: AuthStatus; modelId?: string }) {
  const [draft, setDraft] = useState(initial)
  const client = useQueryClient()
  const update = <K extends keyof Settings>(key: K, value: Settings[K]) => setDraft(current => ({ ...current, [key]: value }))
  useEffect(() => setDraft(initial), [initial])
  const save = useMutation({ mutationFn: () => api<Settings>('/api/settings', { method: 'PUT', body: JSON.stringify({ host: draft.host, port: draft.port, historyEnabled: draft.historyEnabled, saveRequestBodies: draft.saveRequestBodies, saveResponseBodies: draft.saveResponseBodies, retentionDays: draft.retentionDays, theme: draft.theme, compactSidebar: draft.compactSidebar }) }), onSuccess: () => { void client.invalidateQueries({ queryKey: ['settings'] }); void client.invalidateQueries({ queryKey: ['dashboard'] }); useUi.getState().notify('Settings saved.') } })
  const example = `from openai import OpenAI\n\nclient = OpenAI(\n    base_url="${initial.baseUrl}",\n    api_key="local",\n)\n\nresponse = client.responses.create(\n    model="${modelId}",\n    input="Hello, GeminiBridge!",\n)\nprint(response.output_text)`
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial)
  return <><PageHeader title="Settings" actions={<Button size="sm" form="settings-form" type="submit" disabled={save.isPending || !dirty}><Save size={13} />{save.isPending ? 'Saving...' : 'Save changes'}</Button>} />{save.isError && <div className="mb-5"><ErrorState error={save.error} /></div>}
    <form id="settings-form" onSubmit={e => { e.preventDefault(); save.mutate() }} className="space-y-5">
      <Panel className="settings-section"><div><h2 className="text-sm font-medium">Server</h2><p className="mt-2 text-xs leading-5 text-[var(--muted)]">Host and port changes apply after restart. Environment variables take precedence.</p></div><div className="space-y-5"><div className="grid gap-5 sm:grid-cols-2"><Field label="Host" description="Loopback addresses only."><select className="input w-full" value={draft.host} onChange={e => update('host', e.target.value)}><option>127.0.0.1</option><option>localhost</option><option>::1</option></select></Field><Field label="Port"><input className="input w-full" type="number" min={1024} max={65535} required value={draft.port} onChange={e => update('port', Number(e.target.value))} /></Field></div><Field label="Active Base URL" description={`Listening on ${initial.activeHost}:${initial.activePort}`}><div className="flex items-center justify-between rounded-md border border-[var(--border)] bg-[var(--bg)] px-3 py-1.5"><code className="overflow-auto text-xs">{initial.baseUrl}</code><CopyButton text={initial.baseUrl} label="Copy Base URL" size="icon" /></div></Field>{initial.restartRequired && <div className="notice">Restart to apply the saved host and port. GB_HOST and GB_PORT override stored values.</div>}</div></Panel>
      <Panel className="settings-section"><div><h2 className="text-sm font-medium">Request history</h2></div><div><Toggle label="Enable request history" description="Record model, status, latency, and reported usage." checked={draft.historyEnabled} onChange={value => update('historyEnabled', value)} /><Toggle label="Save request bodies" description="May contain private conversation content. Secrets are redacted." checked={draft.saveRequestBodies} onChange={value => update('saveRequestBodies', value)} disabled={!draft.historyEnabled} /><Toggle label="Save response bodies" description="Store output content for later inspection." checked={draft.saveResponseBodies} onChange={value => update('saveResponseBodies', value)} disabled={!draft.historyEnabled} /><div className="mt-4 max-w-xs"><Field label="Retention period (days)" description="Older records are removed hourly. Previous bodies remain until cleared or expired."><input type="number" className="input w-full" min={1} max={3650} required value={draft.retentionDays} onChange={e => update('retentionDays', Number(e.target.value))} /></Field></div></div></Panel>
      <Panel className="settings-section"><div><h2 className="text-sm font-medium">Authentication</h2><p className="mt-2 text-xs leading-5 text-[var(--muted)]">Choose browser import or enter session cookies manually.</p></div><BrowserSession auth={auth} /></Panel>
      <Panel className="settings-section"><div><h2 className="text-sm font-medium">Appearance</h2></div><div><div className="max-w-xs"><Field label="Theme"><select className="input w-full" value={draft.theme} onChange={e => update('theme', e.target.value as Settings['theme'])}><option value="dark">Dark</option><option value="light">Light</option><option value="system">System</option></select></Field></div><div className="mt-3"><Toggle label="Compact sidebar" description="Show navigation icons with labels on hover." checked={draft.compactSidebar} onChange={value => update('compactSidebar', value)} /></div></div></Panel>
    </form>
    {modelId && <Panel className="mt-5 p-5"><div className="flex items-center justify-between gap-4"><h2 className="text-sm font-medium">Client example</h2><CopyButton text={example} label="Copy example" /></div><pre className="json-block mt-4">{example}</pre></Panel>}
  </>
}
export default function SettingsPage() {
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/api/settings') })
  const auth = useQuery({ queryKey: ['auth'], queryFn: () => api<AuthStatus>('/api/auth/status') })
  const models = useQuery({ queryKey: ['models'], queryFn: () => api<Model[]>('/api/models') })
  if (settings.isPending) return <Loading label="Loading settings" />
  if (settings.isError) return <ErrorState error={settings.error} retry={() => void settings.refetch()} />
  return <>{auth.isError && <div className="mb-4"><ErrorState error={auth.error} retry={() => void auth.refetch()} /></div>}<Editor initial={settings.data} auth={auth.data} modelId={models.data?.[0]?.id} /></>
}
