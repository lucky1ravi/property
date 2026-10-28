import { Component, type ReactNode, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '../lib/api';

class Boundary extends Component<{ name: string; children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  render() {
    if (this.state.error) {
      return (
        <div className="rounded-md border border-crit-100 bg-crit-50 p-6">
          <p className="font-bold text-crit-700">The {this.props.name} module hit an error</p>
          <p className="mt-1 text-sm text-graphite-700">{this.state.error.message}</p>
          <button className="mt-3 text-sm font-semibold text-federal-900 underline" onClick={() => this.setState({ error: null })}>Try again</button>
        </div>
      );
    }
    return this.props.children;
  }
}

/**
 * Root for each micro-frontend: its own query cache and error boundary, so one module
 * failing never takes down the shell or the other modules.
 */
export function MicroApp({ name, children }: { name: string; children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: (count, err) => !(err instanceof ApiError && [401, 403, 404].includes(err.status)) && count < 2,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );
  return (
    <Boundary name={name}>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </Boundary>
  );
}
