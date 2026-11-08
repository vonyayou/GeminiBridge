import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) { return twMerge(clsx(inputs)) }
export function duration(ms: number | null | undefined) { return ms == null ? 'Unavailable' : ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(2)} s` }
export function date(value: string) { return new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }) }
export function statusLabel(code: number) { return code === 499 ? 'Cancelled' : code >= 200 && code < 300 ? 'Success' : 'Error' }
