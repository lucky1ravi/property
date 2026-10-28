import { Component, type ReactNode } from 'react';

/** Keeps a rendering error inside one area of the app instead of blanking the whole page. */
export class ErrorBoundary extends Component<{ children: ReactNode; label?: string; compact?: boolean }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: unknown) { console.error('Render error in', this.props.label ?? 'app', error); }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div role="alert" className={this.props.compact ? 'fixed bottom-4 right-4 z-50 max-w-sm rounded-md border border-crit-200 bg-white p-4 shadow-lift' : 'mx-auto max-w-md py-16 text-center'}>
        <p className="font-bold text-graphite-900">Something went wrong on this screen.</p>
        <p className="mt-1 text-sm text-graphite-600">Your data is safe. Reload the screen to continue.</p>
        <button onClick={() => this.setState({ failed: false })} className="mt-3 rounded bg-federal-900 px-3 py-1.5 text-sm font-semibold text-white hover:bg-federal-800">Try again</button>
      </div>
    );
  }
}
