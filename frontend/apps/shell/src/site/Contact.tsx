import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CheckCircle2 } from 'lucide-react';
import { api, Button, Field, Input, Select, Textarea, errorText } from '@propertyos/ui';
import { TOPIC_LABEL, useTitle } from './data';
import { Container, PageHero } from './parts';

export function Contact() {
  useTitle('Contact us');
  const [params] = useSearchParams();
  const initialTopic = (params.get('topic') ?? 'DEMO').toUpperCase();
  const plan = params.get('plan');
  const [f, setF] = useState({
    name: '', email: '', company: '', phone: '', units: '', website: '',
    topic: TOPIC_LABEL[initialTopic] ? initialTopic : 'DEMO',
    message: plan ? `I’m interested in the ${plan.charAt(0) + plan.slice(1).toLowerCase()} plan. ` : '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api.post('/api/identity/public/contact', { ...f, units: f.units.trim() });
      setSent(true);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHero title="Talk to our team" lead="Tell us about your portfolio and what you’d like to improve. A person reads every message and replies by email with answers or a time for a walkthrough." />
      <Container className="grid gap-12 py-14 lg:grid-cols-[1.4fr_1fr]">
        {sent ? (
          <div role="status" className="rounded-lg border border-federal-200 bg-federal-50 p-8">
            <CheckCircle2 className="h-8 w-8 text-federal-700" aria-hidden />
            <h2 className="mt-3 text-2xl font-black text-federal-900">Thanks, we’ve got your message</h2>
            <p className="mt-2 max-w-[52ch] text-graphite-700">We’ll reply to <strong>{f.email}</strong>. If it’s urgent and you already have an account, sign in and tell your administrator.</p>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-5" noValidate>
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label="Your name" required><Input autoComplete="name" required maxLength={120} value={f.name} onChange={set('name')} /></Field>
              <Field label="Work email" required><Input type="email" autoComplete="email" required maxLength={200} value={f.email} onChange={set('email')} /></Field>
              <Field label="Company"><Input autoComplete="organization" maxLength={160} value={f.company} onChange={set('company')} /></Field>
              <Field label="Phone" hint="Optional."><Input type="tel" autoComplete="tel" maxLength={40} value={f.phone} onChange={set('phone')} /></Field>
              <Field label="Units you manage" hint="A rough number is fine."><Input inputMode="numeric" maxLength={7} value={f.units} onChange={(e) => setF({ ...f, units: e.target.value.replace(/\D/g, '') })} /></Field>
              <Field label="What’s this about?">
                <Select value={f.topic} onChange={set('topic')}>{Object.entries(TOPIC_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
              </Field>
            </div>
            <Field label="Message" required hint="At least 10 characters."><Textarea rows={6} required maxLength={2000} value={f.message} onChange={set('message')} /></Field>
            {/* Trap for bots: people never see or fill this. */}
            <div aria-hidden className="absolute -left-[9999px] h-0 w-0 overflow-hidden">
              <label>Leave this empty<input tabIndex={-1} autoComplete="off" value={f.website} onChange={set('website')} /></label>
            </div>
            {error && <p role="alert" className="text-sm font-semibold text-crit-700">{error}</p>}
            <Button type="submit" variant="primary" loading={busy} disabled={f.name.trim().length < 2 || !f.email.includes('@') || f.message.trim().length < 10}>Send message</Button>
          </form>
        )}
        <aside className="self-start rounded-lg bg-limestone-100 p-7">
          <h2 className="text-lg font-extrabold text-graphite-950">What happens next</h2>
          <ol className="mt-4 space-y-4 text-[15px] text-graphite-700">
            <li className="flex gap-3"><span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-federal-900 text-xs font-bold text-white">1</span>A person reads your message.</li>
            <li className="flex gap-3"><span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-federal-900 text-xs font-bold text-white">2</span>We reply by email with answers or a time for a demo.</li>
            <li className="flex gap-3"><span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-federal-900 text-xs font-bold text-white">3</span>If it’s a fit, we set up your company and its first administrator.</li>
          </ol>
        </aside>
      </Container>
    </>
  );
}
