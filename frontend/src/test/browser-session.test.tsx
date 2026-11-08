import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserSession } from '../components/BrowserSession'
import type { AuthStatus } from '../lib/types'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
const auth = { provider: 'gemini-web', state: 'disconnected', supported: true, credentialStored: false, message: 'Choose your browser session.', sessionLoaded: false }
function mount(status: AuthStatus = auth) { const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); return render(<QueryClientProvider client={client}><BrowserSession auth={status} /></QueryClientProvider>) }
function json(value: unknown) { return new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } }) }

it('connects a selected browser profile without passing cookie values through the frontend', async () => {
  const fetcher = vi.fn(async (path: string) => path === '/api/auth/profiles' ? json([{ id: 'profile-id', browser: 'Firefox', name: 'default-release' }]) : json({ ...auth, state: 'authenticated', sessionLoaded: true }))
  vi.stubGlobal('fetch', fetcher)
  mount(); const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Connect browser session' })).toBeEnabled())
  expect(fetcher.mock.calls).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: 'Connect browser session' }))
  await waitFor(() => expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/start')).toBe(true))
  const call = vi.mocked(fetch).mock.calls.find(call => call[0] === '/api/auth/start')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({ profileId: 'profile-id' })
  expect(screen.getByText('Session details')).toBeInTheDocument()
})

it('keeps connection disabled when no supported browser profile exists', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json([])))
  mount()
  expect(await screen.findByText(/No readable Firefox profiles/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Connect browser session' })).toBeDisabled()
})

it('shows expired-session errors and allows reconnecting', async () => {
  vi.stubGlobal('fetch', vi.fn(async path => path === '/api/auth/profiles' ? json([{ id: 'profile-id', browser: 'Firefox', name: 'default-release' }]) : new Response(JSON.stringify({ error: { message: 'Sign in to Gemini again', code: 'gemini_reauthentication_required' } }), { status: 401 })))
  mount(); const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Connect browser session' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: 'Connect browser session' }))
  expect(await screen.findByText('Sign in to Gemini again')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Connect browser session' })).toBeEnabled()
})

it('submits manually entered cookie values and clears the inputs after connection', async () => {
  const fetcher = vi.fn(async (path: string) => path === '/api/auth/profiles' ? json([]) : json({ ...auth, source: 'manual', state: 'authenticated', sessionLoaded: true, accountEmail: 'person@example.com' }))
  vi.stubGlobal('fetch', fetcher)
  mount(); const user = userEvent.setup()
  await user.selectOptions(screen.getByLabelText('Session source'), 'manual')
  await user.type(screen.getByLabelText('__Secure-1PSID'), 'entered-session')
  await user.type(screen.getByLabelText('__Secure-1PSIDTS'), 'entered-timestamp')
  await user.click(screen.getByText('Additional session cookies'))
  await user.type(screen.getByLabelText('GOOGLE_ABUSE_EXEMPTION'), 'completed-verification')
  await user.click(screen.getByRole('button', { name: 'Connect with entered cookies' }))
  await waitFor(() => expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/manual')).toBe(true))
  const call = vi.mocked(fetch).mock.calls.find(call => call[0] === '/api/auth/manual')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({ cookies: { '__Secure-1PSID': 'entered-session', '__Secure-1PSIDTS': 'entered-timestamp', 'GOOGLE_ABUSE_EXEMPTION': 'completed-verification' } })
  await waitFor(() => expect(screen.getByLabelText('__Secure-1PSID')).toHaveValue(''))
  expect(screen.getByLabelText('GOOGLE_ABUSE_EXEMPTION')).toHaveValue('')
  expect(JSON.stringify(localStorage)).not.toContain('entered-session')
})
it('opens Google sign-in and verifies the session only after the user returns', async () => {
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/profiles') return json([{ id: 'profile-id', browser: 'Firefox', name: 'default-release' }])
    if (path === '/api/auth/google/start') return json({ id: 'login-id', state: 'waiting_for_sign_in', profileName: 'default-release', expiresAt: new Date(Date.now() + 600000).toISOString() })
    return json({ ...auth, source: 'browser', sessionLoaded: true, state: 'authenticated', accountEmail: 'person@example.com' })
  })
  vi.stubGlobal('fetch', fetcher)
  mount(); const user = userEvent.setup()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Log in with Google' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: 'Log in with Google' }))
  expect(await screen.findByRole('dialog')).toBeInTheDocument()
  expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/google/login-id/complete')).toBe(false)
  await user.click(screen.getByRole('button', { name: 'Check session' }))
  await waitFor(() => expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/google/login-id/complete')).toBe(true))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})


it('opens the pending Google check in the selected profile before reimporting cookies', async () => {
  const fetcher = vi.fn(async (path: string) => {
    if (path === '/api/auth/profiles') return json([{ id: 'profile-id', browser: 'Firefox', name: 'default-release' }])
    if (path === '/api/auth/google/verify') return json({ id: 'verify-id', state: 'waiting_for_verification', profileName: 'default-release', expiresAt: new Date(Date.now() + 600000).toISOString() })
    return json({ ...auth, source: 'browser', state: 'authenticated', sessionLoaded: true, verificationAvailable: false })
  })
  vi.stubGlobal('fetch', fetcher)
  mount({ ...auth, source: 'browser', profileId: 'profile-id', state: 'verification_required', verificationAvailable: true })
  const user = userEvent.setup()
  expect(screen.getByRole('button', { name: 'Disconnect' })).toBeEnabled()
  await user.click(screen.getByRole('button', { name: 'Open Google verification' }))
  expect(await screen.findByRole('dialog', { name: 'Google verification' })).toBeInTheDocument()
  const call = vi.mocked(fetch).mock.calls.find(call => call[0] === '/api/auth/google/verify')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({ profileId: 'profile-id' })
  expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/google/verify-id/complete')).toBe(false)
  await user.click(screen.getByRole('button', { name: 'Check session' }))
  await waitFor(() => expect(fetcher.mock.calls.some(call => call[0] === '/api/auth/google/verify-id/complete')).toBe(true))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})
