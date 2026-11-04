import { Navigate, Route, Routes } from 'react-router-dom';
import { MicroApp, useSession, AmenityManager } from '@propertyos/ui';
import { Access } from './pages/Access';
import { Configuration } from './pages/Config';
import { Automations, Workflows } from './pages/Automation';
import { Audit, Platform } from './pages/Audit';
import { Enquiries, Features } from './pages/Website';
import { Plans } from './pages/Plans';
import { ServiceHealth, CompanyUsage, DeliveryLog } from './pages/Operations';
import { PlatformPayments, Subscription } from './pages/PlatformPayments';
import { Boosts } from './pages/Boosts';
import { Webhooks } from './pages/Webhooks';
import { ProductDefaults } from './pages/ProductDefaults';

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
        <Route path="webhooks" element={<Webhooks />} />
        <Route path="platform" element={<Platform />} />
        <Route path="defaults" element={<ProductDefaults />} />
        <Route path="plans" element={<Plans />} />
        <Route path="enquiries" element={<Enquiries />} />
        <Route path="features" element={<Features />} />
        <Route path="amenities" element={<AmenityManager platform />} />
        <Route path="payments" element={<PlatformPayments />} />
        <Route path="boosts" element={<Boosts />} />
        <Route path="subscription" element={<Subscription />} />
        <Route path="health" element={<ServiceHealth />} />
        <Route path="usage" element={<CompanyUsage />} />
        <Route path="deliveries" element={<DeliveryLog />} />
        <Route path="website" element={<Navigate to="../enquiries" replace />} />
      </Routes>
    </MicroApp>
  );
}
