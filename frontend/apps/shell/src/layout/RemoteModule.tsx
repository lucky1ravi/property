import { Component, Suspense, lazy, type ComponentType, type ReactNode } from 'react';
import { Skeleton } from '@propertyos/ui';

type Loader = () => Promise<{ default: ComponentType<any> }>;
const cache = new Map<string, ComponentType<any>>();

class LoadBoundary extends Component<{ name: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (this.state.failed) {
      return (
        <div className="rounded-md border border-warn-600/25 bg-warn-50 p-6">
          <p className="font-bold text-warn-700">The {this.props.name} module isn’t available right now</p>
          <p className="mt-1 text-sm text-graphite-700">It’s deployed separately and may be updating. The rest of PropertyOS keeps working.</p>
          <button className="mt-3 text-sm font-semibold text-federal-900 underline" onClick={() => location.reload()}>Reload</button>
        </div>
      );
    }
    return this.props.children;
  }
}

function Loading() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-4 w-96" />
      <Skeleton className="h-64 w-full" />
    </div>
  );
}

/** Loads a micro-frontend at runtime with an isolated failure boundary. */
export function RemoteModule({ name, loader, props }: { name: string; loader: Loader; props?: Record<string, unknown> }) {
  let C = cache.get(name);
  if (!C) {
    C = lazy(loader);
    cache.set(name, C);
  }
  return (
    <LoadBoundary name={name}>
      <Suspense fallback={<Loading />}>
        <C {...props} />
      </Suspense>
    </LoadBoundary>
  );
}
