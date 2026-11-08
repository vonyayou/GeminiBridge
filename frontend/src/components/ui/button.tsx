import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '../../lib/utils'

const variants = cva('inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] disabled:pointer-events-none disabled:opacity-40', {
  variants: {
    variant: { default: 'bg-[var(--accent)] text-[#0d2419] hover:bg-[#b2efd0]', secondary: 'border border-[var(--border)] bg-[var(--surface)] text-[var(--fg)] hover:bg-[var(--hover)]', ghost: 'text-[var(--muted)] hover:bg-[var(--hover)] hover:text-[var(--fg)]', destructive: 'border border-red-500/25 bg-red-500/10 text-red-400 hover:bg-red-500/20' },
    size: { default: 'h-9 px-3.5', sm: 'h-8 px-3 text-xs', icon: 'h-9 w-9' },
  }, defaultVariants: { variant: 'default', size: 'default' },
})
export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof variants> { asChild?: boolean }
export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(({ className, variant, size, asChild = false, ...props }, ref) => {
  const Comp = asChild ? Slot : 'button'
  return <Comp ref={ref} className={cn(variants({ variant, size, className }))} {...props} />
})
Button.displayName = 'Button'
