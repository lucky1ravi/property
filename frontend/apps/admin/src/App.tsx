import { Navigate, Route, Routes } from 'react-router-dom';
import { MicroApp, useSession } from '@propertyos/ui';
import { Access } from './pages/Access';
import { Configuration } from './pages/Config';
import { Automations, Workflows } from './pages/Automation';
import { Audit, Platform } from './pages/Audit';

export default function App() {
  const { session } = useSession();
  return (
    <MicroApp name="admin">
      <Routes>
        <Route index element={<Navigate to={session?.scope === 'PLATFORM' ? 'platform' : 'access'} replace />} />
        <Route path="access" element={<Access />} />
        <Route path="configuration" element={<Configuration />} />
        <Route path="automations" element={<Automations />} />
        <Route path="workflows" element={<Workflows />} />
        <Route path="audit" element={<Audit />} />
        <Route path="platform" element={<Platform />} />
      </Routes>
    </MicroApp>
  );
}
