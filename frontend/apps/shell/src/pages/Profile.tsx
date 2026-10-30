import { useEffect, useRef, useState } from 'react';
import { PageHeader, Panel, Button, Field, Input, Avatar, Skeleton, useSession, useAction, api, toast } from '@propertyos/ui';

const SIZE = 256;

/** Crops the picture to a centred square and shrinks it, so the stored photo stays small. */
function toSquareJpeg(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const side = Math.min(img.width, img.height);
      const c = document.createElement('canvas');
      c.width = c.height = SIZE;
      c.getContext('2d')!.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, SIZE, SIZE);
      URL.revokeObjectURL(url);
      resolve(c.toDataURL('image/jpeg', 0.85));
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('bad image')); };
    img.src = url;
  });
}

/** Everyone can keep their own name, job title, phone and photo up to date. Email is how they sign in, so it is fixed. */
export function Profile() {
  const { session } = useSession();
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [phone, setPhone] = useState('');
  const [photo, setPhoto] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!session) return;
    setName(session.name ?? ''); setTitle(session.title ?? ''); setPhone(session.phone ?? ''); setPhoto(session.photo ?? null);
  }, [session?.userId, session?.name, session?.title, session?.phone, session?.photo]);
  const save = useAction(() => api.put('/api/identity/me/profile', { name, title, phone, photo }), { success: 'Profile saved', invalidate: ['/api/identity', 'session'] });
  if (!session) return <Skeleton className="h-96" />;
  const pick = async (f?: File) => {
    if (!f) return;
    if (!f.type.startsWith('image/')) return toast('Choose an image file (JPG or PNG)');
    try { setPhoto(await toSquareJpeg(f)); } catch { toast('That image could not be read. Try another one.'); }
  };
  const dirty = name !== (session.name ?? '') || title !== (session.title ?? '') || phone !== (session.phone ?? '') || photo !== (session.photo ?? null);
  return (<>
    <PageHeader title="My profile" description="How you appear to your team. Your email is how you sign in, so it can’t be changed here." />
    <div className="max-w-2xl space-y-6">
      <Panel title="Photo">
        <div className="flex items-center gap-5">
          <Avatar name={name || session.name} size={84} seed={session.email} src={photo} ring />
          <div className="space-y-2">
            <input ref={file} type="file" accept="image/*" hidden onChange={(e) => { pick(e.target.files?.[0]); e.target.value = ''; }} />
            <div className="flex gap-2">
              <Button variant="secondary" onClick={() => file.current?.click()}>{photo ? 'Change photo' : 'Upload photo'}</Button>
              {photo && <Button variant="quiet" onClick={() => setPhoto(null)}>Remove</Button>}
            </div>
            <p className="text-xs text-graphite-500">A square picture works best. It is cropped and resized for you.</p>
          </div>
        </div>
      </Panel>
      <Panel title="Details" actions={<Button variant="primary" disabled={!dirty || !name.trim()} loading={save.isPending} onClick={() => save.mutate(undefined as never)}>Save changes</Button>}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name"><Input value={name} maxLength={160} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Job title"><Input value={title} maxLength={160} onChange={(e) => setTitle(e.target.value)} /></Field>
          <Field label="Phone"><Input value={phone} maxLength={40} inputMode="tel" onChange={(e) => setPhone(e.target.value)} /></Field>
          <Field label="Email" hint="Used to sign in. Ask your administrator to change it."><Input value={session.email} disabled readOnly /></Field>
        </div>
      </Panel>
    </div>
  </>);
}
