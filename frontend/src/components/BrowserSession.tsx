import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, RefreshCw } from 'lucide-react'
import { api, post } from '../lib/api'
import type { AuthStatus, BrowserProfile, GoogleLogin } from '../lib/types'
import { Badge, ErrorState, Field } from './common'
import { Button } from './ui/button'
import { Dialog } from './ui/dialog'
import { useUi } from '../lib/store'

const cookieNames = ['__Secure-1PSID', '__Secure-1PSIDTS', '__Secure-1PSIDCC', '__Secure-3PSID', '__Secure-3PSIDTS', '__Secure-3PSIDCC', 'GOOGLE_ABUSE_EXEMPTION'] as const
export function BrowserSession({ auth }: { auth?: AuthStatus }) {
  const client = useQueryClient()
  const profiles = useQuery({ queryKey: ['browser-profiles'], queryFn: () => api<BrowserProfile[]>('/api/auth/profiles') })
  const [profileId, setProfileId] = useState(auth?.profileId ?? '')
  const [source, setSource] = useState<'browser' | 'manual'>(auth?.source ?? 'browser')
  const [cookies, setCookies] = useState<Record<string, string>>({})
  const [googleFlow, setGoogleFlow] = useState<GoogleLogin | null>(null)
  useEffect(() => { if (auth?.source) setSource(auth.source) }, [auth?.source])
  useEffect(() => { if (!profileId) setProfileId(auth?.profileId ?? (profiles.data?.length === 1 ? profiles.data[0].id : '')) }, [auth?.profileId, profiles.data, profileId])
  const invalidate = () => { for (const key of ['auth', 'models', 'providers', 'dashboard']) void client.invalidateQueries({ queryKey: [key] }) }
  const connect = useMutation({ mutationFn: () => source === 'manual' ? post<AuthStatus>('/api/auth/manual', { cookies: Object.fromEntries(Object.entries(cookies).filter(([, value]) => value.trim())) }) : post<AuthStatus>('/api/auth/start', { profileId }), onSuccess: data => { setCookies({}); client.setQueryData(['auth'], data); invalidate(); useUi.getState().notify('Gemini Web session connected.') }, onError: invalidate })
  const logout = useMutation({ mutationFn: () => post<AuthStatus>('/api/auth/logout'), onSuccess: () => { setCookies({}); invalidate(); useUi.getState().notify('GeminiBridge disconnected from the session.') } })
  const google = useMutation({ mutationFn: () => post<GoogleLogin>('/api/auth/google/start', { profileId }), onSuccess: flow => { setGoogleFlow(flow); finishGoogle.reset() } })
  const verification = useMutation({ mutationFn: () => post<GoogleLogin>('/api/auth/google/verify', { profileId }), onSuccess: flow => { setGoogleFlow(flow); finishGoogle.reset() }, onError: invalidate })
  const finishGoogle = useMutation({ mutationFn: () => post<AuthStatus>(`/api/auth/google/${googleFlow!.id}/complete`), onSuccess: data => { setGoogleFlow(null); setCookies({}); setSource('browser'); client.setQueryData(['auth'], data); invalidate(); useUi.getState().notify('Google browser session connected.') }, onError: invalidate })
  const closeGoogle = () => { if (googleFlow) void post(`/api/auth/google/${googleFlow.id}/cancel`).catch(() => {}); setGoogleFlow(null) }
  const verifying = googleFlow?.state === 'waiting_for_verification'
  const busy = connect.isPending || logout.isPending || google.isPending || verification.isPending || finishGoogle.isPending
  const input = (name: string) => <Field key={name} label={name}><input className="input w-full font-mono" aria-label={name} type="password" autoComplete="off" spellCheck={false} maxLength={8192} placeholder="Cookie value" value={cookies[name] ?? ''} disabled={busy} onChange={e => setCookies(values => ({ ...values, [name]: e.target.value }))} /></Field>
  return <div><div className="flex flex-wrap items-center gap-2"><span className="text-sm font-medium">Gemini Web</span><Badge tone={auth?.sessionLoaded ? 'green' : auth?.state === 'verification_required' ? 'amber' : 'neutral'}>{auth?.sessionLoaded ? 'Connected' : connect.isPending ? 'Connecting' : auth?.state === 'verification_required' ? 'Google verification' : 'Disconnected'}</Badge></div>
    {auth?.accountEmail && <p className="mt-2 text-sm text-[var(--muted)]">{auth.accountEmail}</p>}
    {auth?.state === 'error' || auth?.state === 'reauthentication_required' || auth?.state === 'verification_required' ? <div className="notice mt-3">{auth.message}</div> : null}
    <div className="mt-4"><Field label="Session source"><select className="input w-full" value={source} disabled={busy} onChange={e => { setSource(e.target.value as 'browser' | 'manual'); setCookies({}); connect.reset() }}><option value="browser">Browser import</option><option value="manual">Manual cookies</option></select></Field></div>
    {source === 'browser' ? <><div className="mt-4"><Field label="Browser profile"><select className="input w-full" value={profileId} disabled={busy || profiles.isPending} onChange={e => setProfileId(e.target.value)}><option value="" disabled>Select a browser profile</option>{profiles.data?.map(profile => <option value={profile.id} key={profile.id}>{profile.browser} · {profile.name}</option>)}</select></Field></div>{profiles.isError && <div className="mt-3"><ErrorState error={profiles.error} retry={() => void profiles.refetch()} /></div>}{profiles.data?.length === 0 && <div className="notice mt-3">No readable Firefox profiles were found.</div>}</> : <div className="mt-4 space-y-4"><p className="text-xs leading-5 text-[var(--muted)]">Paste session cookie values from your signed-in Gemini browser. No browser profile is read in this mode.</p>{cookieNames.slice(0, 2).map(input)}<details className="text-xs text-[var(--muted)]"><summary className="cursor-pointer">Additional session cookies</summary><div className="mt-3 space-y-4">{cookieNames.slice(2).map(input)}</div></details><p className="text-xs text-[var(--muted)]">Values stay in memory and must be entered again after a restart.</p></div>}
    {connect.isError && connect.error.message !== auth?.message && <div className="mt-3"><ErrorState error={connect.error} /></div>}{logout.isError && <div className="mt-3"><ErrorState error={logout.error} /></div>}
    {google.isError && <div className="mt-3"><ErrorState error={google.error} /></div>}
    {verification.isError && <div className="mt-3"><ErrorState error={verification.error} /></div>}
    {auth?.verificationAvailable && source === 'manual' && <p className="mt-3 text-xs text-[var(--muted)]">Select Browser import to open the Google check in your Firefox profile.</p>}
    <div className="mt-4 flex flex-wrap gap-2">{source === 'browser' && auth?.verificationAvailable && <Button type="button" size="sm" disabled={busy || !profileId} onClick={() => verification.mutate()}>{verification.isPending ? 'Opening Firefox...' : 'Open Google verification'}</Button>}{source === 'browser' && <Button type="button" variant="secondary" size="sm" disabled={busy || !profileId} onClick={() => google.mutate()}>{google.isPending ? 'Opening Firefox...' : 'Log in with Google'}</Button>}<Button type="button" size="sm" disabled={busy || (source === 'manual' ? !cookies['__Secure-1PSID']?.trim() : !profileId)} onClick={() => connect.mutate()}>{connect.isPending && <RefreshCw size={13} className="animate-spin" />}{connect.isPending ? 'Connecting...' : source === 'manual' ? 'Connect with entered cookies' : auth?.sessionLoaded ? 'Reconnect browser session' : 'Connect browser session'}</Button><Button type="button" variant="secondary" size="sm" disabled={busy || !auth?.sessionLoaded && !auth?.manualCookiesLoaded && auth?.state !== 'verification_required'} onClick={() => logout.mutate()}>Disconnect</Button><Button type="button" variant="ghost" size="sm" asChild><a href="https://gemini.google.com/app" target="_blank" rel="noreferrer">Open Gemini<ExternalLink size={12} /></a></Button></div>
    <details className="mt-4 text-xs text-[var(--muted)]"><summary className="cursor-pointer">Session details</summary><p className="mt-2 leading-6">This is an unofficial Gemini Web connection. Manual entry replaces browser cookie import, not message generation. Google may separately restrict automated access.</p></details>
    <Dialog open={!!googleFlow} onOpenChange={open => { if (!open && !finishGoogle.isPending) closeGoogle() }} title={verifying ? 'Google verification' : 'Log in with Google'} description={verifying ? 'Complete the security check in Firefox, then return here.' : 'Complete sign-in in Firefox, then return here.'}>
      <p className="text-sm leading-6 text-[var(--muted)]">Google was opened in Firefox profile <span className="text-[var(--fg)]">{googleFlow?.profileName}</span>. {verifying ? 'Complete the security check in that tab, then click Check session. Afterwards, retry your message.' : 'Choose your Google account, open Gemini, and click Check session.'}</p>
      {finishGoogle.isError && <div className="mt-4"><ErrorState error={finishGoogle.error} /></div>}
      <div className="mt-5 flex justify-end gap-2"><Button variant="ghost" disabled={finishGoogle.isPending} onClick={closeGoogle}>Cancel</Button><Button disabled={finishGoogle.isPending} onClick={() => finishGoogle.mutate()}>{finishGoogle.isPending ? 'Checking...' : 'Check session'}</Button></div>
    </Dialog>
  </div>
}
