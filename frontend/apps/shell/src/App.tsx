import { BrowserRouter, Navigate, Route, Routes, Link } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { getToken } from '@propertyos/ui';
import { SignIn } from './pages/SignIn';
import { Messages } from './pages/Messages';
import { Deadlines } from './pages/Deadlines';
import { Preferences } from './pages/Preferences';
import { AppLayout } from './layout/AppLayout';
import { RemoteModule } from './layout/RemoteModule';
import { Toaster } from './layout/Toaster';
import { Logo } from './layout/Logo';

const qc = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1 } } });

// Each domain is a separately built and deployed micro-frontend, loaded at runtime.
const remotes = {
  leasing: () => import('leasing/App'),
  payments: () => import('payments/App'),
  documents: () => import('documents/App'),
  maintenance: () => import('maintenance/App'),
  broker: () => import('broker/App'),
  admin: () => import('admin/App'),
  insights: () => import('insights/App'),
};

function Landing() {
  return getToken() ? <Navigate to="/app" replace /> : <SignIn />;
}

function PublicLayout() {
  return (
    <div className="min-h-screen bg-limestone-100">
      <header className="border-b border-limestone-200 bg-white">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-6">
          <Link to="/homes"><Logo /></Link>
          <Link to="/signin" className="rounded bg-federal-900 px-4 py-2 text-sm font-semibold text-white hover:bg-federal-800">Sign in</Link>
        </div>
      </header>
      <main className="mx-auto max-w-[1200px] px-6 py-8">
        <RemoteModule name="leasing" loader={remotes.leasing} props={{ publicMode: true }} />
      </main>
    </div>
  );
}

function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 bg-limestone-100 px-6 text-center">
      <Logo />
      <h1 className="mt-6 text-2xl font-extrabold">This page doesn’t exist</h1>
      <p className="text-graphite-600">The link may be out of date.</p>
      <Link to="/app" className="mt-2 font-semibold text-federal-900 underline">Go to your home page</Link>
    </div>
  );
}

export function App() {
  return (
    <QueryClientProvider client={qc}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/signin" element={<SignIn />} />
          <Route path="/homes/*" element={<PublicLayout />} />
          <Route path="/app" element={<AppLayout />}>
            <Route index element={<RemoteModule name="insights" loader={remotes.insights} props={{ view: 'home' }} />} />
            <Route path="insights/*" element={<RemoteModule name="insights" loader={remotes.insights} props={{ view: 'reports' }} />} />
            <Route path="broker/*" element={<RemoteModule name="broker" loader={remotes.broker} />} />
            <Route path="leasing/*" element={<RemoteModule name="leasing" loader={remotes.leasing} />} />
            <Route path="payments/*" element={<RemoteModule name="payments" loader={remotes.payments} />} />
            <Route path="documents/*" element={<RemoteModule name="documents" loader={remotes.documents} />} />
            <Route path="maintenance/*" element={<RemoteModule name="maintenance" loader={remotes.maintenance} />} />
            <Route path="messages" element={<Messages />} />
            <Route path="deadlines" element={<Deadlines />} />
            <Route path="preferences" element={<Preferences />} />
            <Route path="admin/*" element={<RemoteModule name="admin" loader={remotes.admin} />} />
          </Route>
          <Route path="*" element={<NotFound />} />
        </Routes>
        <Toaster />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
