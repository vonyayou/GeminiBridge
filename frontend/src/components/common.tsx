import { cloneElement, isValidElement, useEffect, type ReactNode } from 'react'
import { Copy, Check, AlertCircle, Loader2, X } from 'lucide-react'
import { useState } from 'react'
import { Button } from './ui/button'
import { cn, date, duration, statusLabel } from '../lib/utils'
import { useUi } from '../lib/store'
import type { HistoryRow } from '../lib/types'

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'green' | 'amber' | 'red' | 'neutral' }) {
  return <span className={cn('inline-flex items-center gap-1.5 rounded border px-2 py-0.5 text-[11px] font-medium', { green: 'border-emerald-400/15 bg-emerald-400/8 text-[var(--good)]', amber: 'border-amber-400/15 bg-amber-400/8 text-[var(--warning)]', red: 'border-red-400/20 bg-red-400/8 text-[var(--danger)]', neutral: 'border-[var(--border)] bg-[var(--hover)] text-[var(--muted)]' }[tone])}>{children}</span>
}
export function CopyButton({ text, label = 'Copy', size = 'sm' }: { text: string; label?: string; size?: 'sm' | 'icon' }) {
  const [copied, setCopied] = useState(false)
  const notify = useUi(s => s.notify)
  useEffect(() => { if (copied) { const timer = setTimeout(() => setCopied(false), 1800); return () => clearTimeout(timer) } }, [copied])
  return <Button type="button" variant="ghost" size={size} aria-label={label} onClick={async () => { try { await navigator.clipboard.writeText(text); setCopied(true) } catch { notify('Clipboard is unavailable. Select and copy the text manually.') } }}>{copied ? <Check size={14} /> : <Copy size={14} />}{size !== 'icon' && (copied ? 'Copied' : label)}</Button>
}
export function PageHeader({ title, actions }: { title: string; actions?: ReactNode }) {
  return <div className="mb-6 flex flex-wrap items-center justify-between gap-4"><h1 className="text-2xl font-semibold tracking-tight">{title}</h1>{actions && <div className="flex items-center gap-2">{actions}</div>}</div>
}
export function Panel({ children, className = '' }: { children: ReactNode; className?: string }) { return <section className={cn('rounded-lg border border-[var(--border)] bg-[var(--surface)]', className)}>{children}</section> }
export function Loading({ label = 'Loading workspace' }: { label?: string }) { return <div role="status" className="flex min-h-48 items-center justify-center gap-3 text-sm text-[var(--muted)]"><Loader2 size={18} className="animate-spin" />{label}</div> }
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) { return <div role="alert" className="flex flex-wrap items-center gap-3 rounded-lg border border-red-400/20 bg-red-400/5 p-4 text-sm text-[var(--danger)]"><AlertCircle size={18} /><span className="flex-1">{error instanceof Error ? error.message : 'Something went wrong.'}</span>{retry && <Button variant="secondary" size="sm" onClick={retry}>Retry</Button>}</div> }
export function Empty({ title, description, children }: { title: string; description: string; children?: ReactNode }) { return <div className="flex flex-col items-center px-6 py-10 text-center"><h3 className="text-sm font-medium">{title}</h3><p className="mt-2 max-w-sm text-sm leading-6 text-[var(--muted)]">{description}</p>{children && <div className="mt-5">{children}</div>}</div> }
export function Toast() {
  const { toast, dismiss } = useUi()
  useEffect(() => { if (toast) { const timer = setTimeout(dismiss, 5000); return () => clearTimeout(timer) } }, [toast, dismiss])
  return toast ? <div role="status" className="fixed bottom-6 right-6 z-[60] flex max-w-[calc(100%-3rem)] items-center gap-4 rounded-lg border border-[var(--border)] bg-[var(--surface)] px-4 py-3 text-sm shadow-xl">{toast}<button aria-label="Dismiss notification" onClick={dismiss}><X size={16} /></button></div> : null
}
export function HistoryTable({ rows, onSelect, compact = false }: { rows: HistoryRow[]; onSelect: (id: string) => void; compact?: boolean }) {
  return <div className="overflow-x-auto"><table className="data-table"><thead><tr><th>Time</th><th>Model</th><th>Status</th><th>Duration</th>{!compact && <><th>Input tokens</th><th>Output tokens</th></>}<th>Client</th><th className="sr-only">Details</th></tr></thead><tbody>{rows.map(row => <tr key={row.id}>
    <td className="whitespace-nowrap text-[var(--muted)]">{date(row.timestamp)}</td><td><span className="font-mono text-xs">{row.requestedModel ?? 'Unknown'}</span>{row.streaming && <span className="ml-2 text-[10px] text-[var(--muted)]">SSE</span>}</td>
    <td><Badge tone={row.httpStatus === 499 ? 'amber' : row.httpStatus < 300 ? 'green' : 'red'}><span className="h-1 w-1 rounded-full bg-current" />{statusLabel(row.httpStatus)}</Badge></td><td className="tabular-nums">{duration(row.durationMs)}</td>{!compact && <><td>{row.inputTokens ?? '—'}</td><td>{row.outputTokens ?? '—'}</td></>}<td className="text-xs text-[var(--muted)]">{row.client}</td><td><Button variant="ghost" size="sm" aria-label={`View request ${row.id}`} onClick={() => onSelect(row.id)}>View</Button></td>
  </tr>)}</tbody></table></div>
}
export function Field({ label, description, children }: { label: string; description?: string; children: ReactNode }) {
  const control = isValidElement<{ 'aria-label'?: string }>(children) && ['input', 'select', 'textarea'].includes(String(children.type)) ? cloneElement(children, { 'aria-label': label }) : children
  return <label className="block space-y-2"><span className="text-sm font-medium">{label}</span>{control}{description && <span className="block text-xs leading-5 text-[var(--muted)]">{description}</span>}</label>
}
export function Toggle({ checked, onChange, label, description, disabled }: { checked: boolean; onChange: (value: boolean) => void; label: string; description?: string; disabled?: boolean }) {
  return <label className="flex cursor-pointer items-center justify-between gap-5 py-3"><span><span className="block text-sm">{label}</span>{description && <span className="mt-1 block text-xs leading-5 text-[var(--muted)]">{description}</span>}</span><input className="toggle" type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} aria-label={label} disabled={disabled} /></label>
}
