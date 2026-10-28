import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { App } from './App';

// The gateway URL is set once here at build time and shared with every remote at runtime.
window.__PROPERTYOS_API__ = import.meta.env.VITE_API_URL || 'http://localhost:8080';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
