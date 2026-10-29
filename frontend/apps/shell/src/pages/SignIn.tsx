import { useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { motion, useScroll, useTransform } from 'framer-motion';
import { useQuery } from '@tanstack/react-query';
import { ArrowDown, KeyRound, ShieldCheck } from 'lucide-react';
import { api, ApiError, setToken, Button, Input, Field, Avatar, Spinner, Select, toastError, errorText } from '@propertyos/ui';
import { Streetscape } from './Streetscape';
import { Logo } from '../layout/Logo';

interface DemoUser { id: string; name: string; email: string; title: string; persona: string; roleKey: string; organization: string }

const PERSONA_ORDER = ['Broker', 'Property manager', 'Company owner / admin', 'Property owner', 'Tenant', 'Vendor', 'Leasing agent', 'Accountant', 'Platform admin', 'Company owner / admin (second company)'];

export function SignIn() {
  const nav = useNavigate();
  const [params] = useSearchParams();
  const hero = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: hero, offset: ['start start', 'end start'] });
  const headlineY = useTransform(scrollYProgress, [0, 1], [0, -60]);
  const headlineOpacity = useTransform(scrollYProgress, [0, 0.7], [1, 0]);

  const demo = useQuery({ queryKey: ['demo-users'], queryFn: () => api.get<{ enabled: boolean; users: DemoUser[] }>('/api/identity/public/demo-users') });
  const [busy, setBusy] = useState<string | null>(null);
  const [mode, setMode] = useState<'password' | 'persona'>('password');
  const options = useQuery({ queryKey: ['signin-options'], queryFn: () => api.get<{ demo: boolean; demoPassword?: string }>('/api/identity/public/options'), retry: false });
  const [password, setPassword] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const apiDown = options.isError && demo.isError;

  const [mustChange, setMustChange] = useState(false);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const signInWithPassword = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setFormError(null);
    setBusy('password');
    try {
      const r = await api.post<{ token: string }>('/api/identity/public/login', { email: email.trim(), password });
      finish(r.token);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'PASSWORD_CHANGE_REQUIRED') setMustChange(true);
      else setFormError(errorText(err));
      setBusy(null);
    }
  };
  const choosePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (newPassword !== confirmPassword) { setFormError('The two passwords don’t match.'); return; }
    setBusy('change');
    try {
      const r = await api.post<{ token: string }>('/api/identity/public/change-password', { email: email.trim(), currentPassword: password, newPassword });
      finish(r.token);
    } catch (err) {
      setFormError(errorText(err));
      setBusy(null);
    }
  };
  const [email, setEmail] = useState('');

  const finish = (token: string) => {
    setToken(token);
    nav('/app', { replace: true });
  };

  const signInAs = async (u: DemoUser) => {
    setBusy(u.id);
    try {
      const r = await api.post<{ token: string }>('/api/identity/public/demo-login', { userId: u.id });
      finish(r.token);
    } catch (e) {
      toastError(e, 'Sign-in failed');
      setBusy(null);
    }
  };

  const users = (demo.data?.users ?? []).slice().sort((a, b) => PERSONA_ORDER.indexOf(a.persona) - PERSONA_ORDER.indexOf(b.persona));

  return (
    <div className="bg-federal-950">
      <div ref={hero} className="relative min-h-screen overflow-hidden">
        <Streetscape scrollProgress={scrollYProgress} />
        <div className="absolute inset-0 bg-gradient-to-r from-federal-950/85 via-federal-950/40 to-transparent lg:to-federal-950/10" />

        <div className="relative z-10 mx-auto flex min-h-screen max-w-[1320px] flex-col gap-10 px-6 py-8 lg:flex-row lg:items-start lg:justify-between lg:py-10">
          <motion.div style={{ y: headlineY, opacity: headlineOpacity }} className="max-w-xl pt-2 text-white lg:pt-6">
            <Logo light />
            <h1 className="mt-16 text-[44px] font-black leading-[1.02] tracking-[-0.035em] sm:text-[60px]">
              One broker.<br />Hundreds of doors.
            </h1>
            <p className="mt-6 max-w-[44ch] text-lg leading-relaxed text-federal-100">
              PropertyOS answers enquiries, screens applicants, collects rent and dispatches repairs on its own,
              then brings you only the decisions that need a person.
            </p>
            <dl className="mt-10 grid max-w-md grid-cols-3 gap-6 rounded-md bg-federal-950/55 p-5 backdrop-blur-sm">
              <div className="flex flex-col-reverse"><dt className="mt-1 text-xs leading-snug text-federal-200">workflows run end to end</dt><dd className="text-3xl font-black">3</dd></div>
              <div className="flex flex-col-reverse"><dt className="mt-1 text-xs leading-snug text-federal-200">AI decisions, each with reasons</dt><dd className="text-3xl font-black">8</dd></div>
              <div className="flex flex-col-reverse"><dt className="mt-1 text-xs leading-snug text-federal-200">roles, data isolated in the database</dt><dd className="text-3xl font-black">9</dd></div>
            </dl>
            <a href="#how" className="mt-12 hidden items-center gap-2 text-sm font-semibold text-federal-100 hover:text-white lg:inline-flex">
              <ArrowDown className="h-4 w-4" /> See what runs on its own
            </a>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.3, duration: 0.5 }}
            className="w-full max-w-[440px] self-center rounded-lg bg-white/95 shadow-lift backdrop-blur lg:self-start"
          >
            <div className="border-b border-limestone-200 px-6 pb-4 pt-5">
              <h2 className="text-xl font-extrabold tracking-[-0.01em] text-graphite-950">Sign in</h2>
              <p className="mt-1 text-sm text-graphite-600">
                {params.get('suspended') ? 'This company’s PropertyOS account is suspended. Please contact your property manager or company administrator.' : params.get('expired') ? 'Your session ended. Sign in again to continue.' : 'Sign in with your email and password.'}
              </p>
              <div className="mt-4 grid grid-cols-2 gap-1 rounded bg-limestone-100 p-1 text-sm font-semibold">
                <button onClick={() => setMode('password')} className={`rounded py-1.5 ${mode === 'password' ? 'bg-white text-federal-900 shadow-sm' : 'text-graphite-500'}`}>Password</button>
                <button onClick={() => setMode('persona')} className={`rounded py-1.5 ${mode === 'persona' ? 'bg-white text-federal-900 shadow-sm' : 'text-graphite-500'}`}>Demo personas</button>
              </div>
            </div>

            {apiDown && (
              <div role="alert" className="mx-6 mt-4 rounded border border-crit-200 bg-crit-50 px-3 py-2 text-sm text-crit-800">
                We can’t connect to PropertyOS right now. Check your internet connection, wait a minute, and reload the page. If it keeps happening, contact your administrator.
              </div>
            )}
            {mode === 'password' && mustChange ? (
              <form onSubmit={choosePassword} className="space-y-4 px-6 py-5">
                <p className="text-sm text-graphite-700">You signed in with a temporary password. Choose your own to continue.</p>
                <Field label="New password" hint="At least 10 characters.">
                  <Input type="password" autoComplete="new-password" required minLength={10} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
                </Field>
                <Field label="Confirm new password">
                  <Input type="password" autoComplete="new-password" required value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
                </Field>
                {formError && <p role="alert" className="text-sm font-semibold text-crit-700">{formError}</p>}
                <Button type="submit" variant="primary" className="w-full" icon={<KeyRound className="h-4 w-4" />} loading={busy === 'change'} disabled={newPassword.length < 10 || !confirmPassword}>Save password and sign in</Button>
              </form>
            ) : mode === 'password' ? (
              <form onSubmit={signInWithPassword} className="space-y-4 px-6 py-5">
                <Field label="Work email">
                  <Input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" />
                </Field>
                <Field label="Password">
                  <Input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
                </Field>
                {formError && <p role="alert" className="text-sm font-semibold text-crit-700">{formError}</p>}
                <Button type="submit" variant="primary" className="w-full" icon={<KeyRound className="h-4 w-4" />} loading={busy === 'password'} disabled={!email.includes('@') || !password}>Sign in</Button>
                {options.data?.demo && (
                  <div className="rounded bg-limestone-100 p-3 text-xs text-graphite-600">
                    <p className="font-semibold text-graphite-800">Demo environment</p>
                    <p className="mt-1">Every demo user shares the password <code className="font-bold">{options.data.demoPassword}</code>. Pick a role to fill the form:</p>
                    <Select aria-label="Fill in a demo user" className="mt-2"
                      value="" onChange={(e) => { const u = users.find((x) => x.id === e.target.value); if (u) { setEmail(u.email); setPassword(options.data?.demoPassword ?? ''); } }}>
                      <option value="">Choose a persona…</option>
                      {users.map((u) => <option key={u.id} value={u.id}>{u.persona.replace(' (second company)', ' (company 2)')}: {u.name}</option>)}
                    </Select>
                  </div>
                )}
              </form>
            ) : mode === 'persona' ? (
              <ul className="scrollbar-thin max-h-[52vh] overflow-y-auto px-2 py-2">
                {demo.isLoading && <li className="flex items-center gap-2 px-4 py-6 text-sm text-graphite-500"><Spinner /> Loading personas…</li>}
                {demo.isError && <li className="px-4 py-6 text-sm text-crit-700">Can’t reach the API. Check that the gateway is running.</li>}
                {users.map((u) => (
                  <li key={u.id}>
                    <button onClick={() => signInAs(u)} disabled={!!busy}
                      className="flex w-full items-center gap-3 rounded px-3 py-2.5 text-left transition-colors hover:bg-federal-50 focus-visible:bg-federal-50 focus-visible:outline-none disabled:opacity-60">
                      <Avatar name={u.name} size={36} seed={u.email} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-bold text-graphite-900">{u.name}</span>
                        <span className="block truncate text-xs text-graphite-500">{u.title}{u.organization !== 'Summit Ridge Property Group' ? `, ${u.organization}` : ''}</span>
                      </span>
                      <span className="shrink-0 rounded-full bg-limestone-100 px-2 py-0.5 text-2xs font-semibold text-graphite-600">{u.persona.replace(' (second company)', '')}</span>
                      {busy === u.id && <Spinner className="h-4 w-4 text-federal-900" />}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
            <div className="flex items-center gap-2 border-t border-limestone-200 px-6 py-3 text-xs text-graphite-500">
              <ShieldCheck className="h-4 w-4 text-federal-700" /> Every role sees only its own data, enforced in the database.
            </div>
          </motion.div>
        </div>
      </div>

      <HowItWorks />
    </div>
  );
}

const FLOWS = [
  { name: 'Leasing', steps: ['AI writes and checks the listing', 'Leasing agent replies to every enquiry', 'Showings booked by area and capacity', 'Applicant scored with reasons', 'State-compliant lease, e-signed'], human: 'Broker approves the applicant' },
  { name: 'Rent', steps: ['Charges scheduled from the lease', 'Invoices and reminders sent', 'Late fees within state caps', 'At-risk tenants offered a plan', 'Owner statement and payout'], human: 'Accountant waives or approves exceptions' },
  { name: 'Maintenance', steps: ['Resident reports in plain words', 'AI triages trade, priority, cost', 'Owner approves above their limit', 'Best vendor dispatched', 'Resident confirms, vendor rated'], human: 'Broker handles emergencies' },
];

function HowItWorks() {
  return (
    <section id="how" className="bg-limestone-100">
      <div className="mx-auto max-w-[1320px] px-6 py-20">
        <h2 className="max-w-[22ch] text-[34px] font-black leading-tight tracking-[-0.025em] text-graphite-950">Three workflows that run without you, until they shouldn’t.</h2>
        <p className="mt-3 max-w-[60ch] text-[17px] text-graphite-600">Each step is a configurable state with an owner, a time limit and an audit trail. Automations move routine work forward; anything involving money, legal terms or safety waits for a person.</p>
        <div className="mt-12 grid gap-10 lg:grid-cols-3">
          {FLOWS.map((f) => (
            <div key={f.name}>
              <h3 className="border-b-2 border-federal-900 pb-2 text-lg font-extrabold text-federal-900">{f.name}</h3>
              <ol className="mt-4 space-y-3">
                {f.steps.map((s, i) => (
                  <li key={s} className="flex gap-3 text-[15px] text-graphite-800">
                    <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-federal-100 text-2xs font-bold text-federal-900">{i + 1}</span>
                    {s}
                  </li>
                ))}
              </ol>
              <p className="mt-4 rounded bg-brick-50 px-3 py-2 text-sm font-semibold text-brick-700">{f.human}</p>
            </div>
          ))}
        </div>
        <footer className="mt-20 flex flex-wrap items-center justify-between gap-4 border-t border-limestone-300 pt-6 text-sm text-graphite-500">
          <Logo />
          <span>Built for US residential property management.</span>
        </footer>
      </div>
    </section>
  );
}
