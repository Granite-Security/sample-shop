import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { AuthProvider } from './auth'
import { CartProvider } from './contexts/CartContext'
import { MessagesProvider } from './contexts/MessagesContext'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <CartProvider>
          {/* Inside AuthProvider: the poll is gated on being signed in. */}
          <MessagesProvider>
            <App />
          </MessagesProvider>
        </CartProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
