import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div className="page" style={{ textAlign: 'center', paddingTop: '3rem' }}>
            <h1>Something went wrong</h1>
            <p>An unexpected error occurred. Please try reloading the page.</p>
            {this.state.error && (
              <p style={{ marginTop: 16, color: 'var(--danger)', fontSize: '0.85rem', fontFamily: 'monospace' }}>
                {this.state.error.name}: {this.state.error.message}
              </p>
            )}
          </div>
        )
      );
    }
    return this.props.children;
  }
}
