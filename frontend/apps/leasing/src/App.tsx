import { MicroApp } from '@propertyos/ui';
import { BaseCtx } from './common';
import { PublicSite } from './pages/PublicSite';
import { Staff } from './pages/Staff';

export default function App({ publicMode }: { publicMode?: boolean }) {
  return (
    <MicroApp name="leasing">
      <BaseCtx.Provider value={publicMode ? '/homes' : '/app/leasing'}>{publicMode ? <PublicSite /> : <Staff />}</BaseCtx.Provider>
    </MicroApp>
  );
}
