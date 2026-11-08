import * as DialogPrimitive from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { cn } from '../../lib/utils'
import type { ReactNode } from 'react'

export function Dialog({ open, onOpenChange, title, description, children, className }: { open: boolean; onOpenChange: (open: boolean) => void; title: string; description?: string; children: ReactNode; className?: string }) {
  return <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}><DialogPrimitive.Portal>
    <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-black/65 backdrop-blur-sm" />
    <DialogPrimitive.Content className={cn('fixed left-1/2 top-1/2 z-50 max-h-[85dvh] w-[calc(100%-2rem)] max-w-2xl -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6 shadow-2xl focus:outline-none', className)}>
      <DialogPrimitive.Title className="pr-8 text-lg font-semibold">{title}</DialogPrimitive.Title>
      <DialogPrimitive.Description className={cn('mt-1 text-sm text-[var(--muted)]', !description && 'sr-only')}>{description ?? title}</DialogPrimitive.Description>
      <DialogPrimitive.Close aria-label="Close dialog" className="absolute right-4 top-4 rounded p-1 text-[var(--muted)] hover:bg-[var(--hover)]"><X size={18} /></DialogPrimitive.Close>
      <div className="mt-5">{children}</div>
    </DialogPrimitive.Content>
  </DialogPrimitive.Portal></DialogPrimitive.Root>
}
