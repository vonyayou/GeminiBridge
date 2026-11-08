import { useEffect, Component, Suspense, lazy, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { Box, ChevronLeft, ChevronRight, History, LayoutDashboard, Menu, MessageSquare, Network, Settings as SettingsIcon, X } from 'lucide-react'
import { api } from './lib/api'
import { useUi } from './lib/store'
import { Button } from './components/ui/button'
import { ErrorState, Loading, Toast } from './components/common'
import type { Settings } from './lib/types'
const DashboardPage = lazy(() => import('./pages/Dashboard'))
const Playground = lazy(() => import('./pages/Playground'))
const Models = lazy(() => import('./pages/Models'))
const HistoryPage = lazy(() => import('./pages/History'))
const Providers = lazy(() => import('./pages/Providers'))
const SettingsPage = lazy(() => import('./pages/Settings'))

export const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 5000, refetchOnWindowFocus: false } } })
const links = [{ to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard }, { to: '/playground', label: 'Playground', icon: MessageSquare }, { to: '/models', label: 'Models', icon: Box }, { to: '/history', label: 'Request History', icon: History }, { to: '/providers', label: 'Providers', icon: Network }, { to: '/settings', label: 'Settings', icon: SettingsIcon }]

function Workspace() {
  const { collapsed, setCollapsed, mobileOpen, setMobileOpen } = useUi()
  const location = useLocation()
  const settings = useQuery({ queryKey: ['settings'], queryFn: () => api<Settings>('/api/settings') })
  useEffect(() => { setMobileOpen(false) }, [location.pathname, setMobileOpen])
  useEffect(() => {
    if (!settings.data) return
    setCollapsed(settings.data.compactSidebar)
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const apply = () => document.documentElement.classList.toggle('light', settings.data!.theme === 'light' || (settings.data!.theme === 'system' && !media.matches))
    apply(); media.addEventListener('change', apply); return () => media.removeEventListener('change', apply)
  }, [settings.data, setCollapsed])
  return <div className="app-shell" style={{ '--sidebar-width': collapsed ? '76px' : '224px' } as React.CSSProperties}>
    {mobileOpen && <button aria-label="Close navigation" className="fixed inset-0 z-30 bg-black/60 md:hidden" onClick={() => setMobileOpen(false)} />}
    <aside className={`sidebar ${mobileOpen ? 'mobile-open' : ''}`}>
      <div className="sidebar-heading">
        {!collapsed && <NavLink to="/dashboard" className="text-sm font-semibold tracking-tight">GeminiBridge</NavLink>}
        <Button variant="ghost" size="icon" className="hidden md:inline-flex" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => setCollapsed(!collapsed)}>{collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}</Button>
        <Button variant="ghost" size="icon" className="md:hidden" aria-label="Close navigation" onClick={() => setMobileOpen(false)}><X size={18} /></Button>
      </div>
      <nav aria-label="Main navigation" className="space-y-1 px-3">{links.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} title={collapsed ? label : undefined} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''} ${collapsed ? 'justify-center px-0' : ''}`}><Icon size={17} strokeWidth={1.7} />{!collapsed && <span>{label}</span>}</NavLink>)}</nav>
    </aside>
    <div className="workspace"><header className="mobile-toolbar"><Button variant="ghost" size="icon" aria-label="Open navigation" onClick={() => setMobileOpen(true)}><Menu size={18} /></Button><span className="text-sm font-medium">GeminiBridge</span></header>
      <main className={location.pathname === '/playground' ? 'page playground-page' : 'page'}><Suspense fallback={<Loading />}><Routes><Route path="/dashboard" element={<DashboardPage />} /><Route path="/playground" element={<Playground />} /><Route path="/models" element={<Models />} /><Route path="/history" element={<HistoryPage />} /><Route path="/providers" element={<Providers />} /><Route path="/settings" element={<SettingsPage />} /><Route path="*" element={<Navigate to="/dashboard" replace />} /></Routes></Suspense></main>
    </div>

  </div>
}
class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  render() { return this.state.failed ? <div className="m-10"><ErrorState error={new Error('The interface could not load. Reload to recover.')} retry={() => window.location.reload()} /></div> : this.props.children }
}
export default function App() { return <ErrorBoundary><QueryClientProvider client={queryClient}><BrowserRouter><Workspace /><Toast /></BrowserRouter></QueryClientProvider></ErrorBoundary> }
