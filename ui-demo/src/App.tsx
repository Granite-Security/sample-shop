import { BrowserRouter, Outlet, Route, Routes } from 'react-router';
import { ShopProvider } from './store';
import { AuthProvider } from './auth';
import { Callback } from './components/Callback';
import { Header } from './components/Header';
import { Footer } from './components/Footer';
import { CartDrawer } from './components/CartDrawer';
import { Home } from './pages/Home';
import { ProductPage } from './pages/ProductPage';
import { AdminPage } from './pages/AdminPage';
import { CheckoutPage } from './pages/CheckoutPage';

function Layout() {
  return (
    <>
      <Header />
      <main>
        <Outlet />
      </main>
      <Footer />
      <CartDrawer />
    </>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <ShopProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/callback" element={<Callback />} />
            <Route element={<Layout />}>
              <Route index element={<Home />} />
              <Route path="products/:id" element={<ProductPage />} />
              <Route path="admin" element={<AdminPage />} />
              <Route path="checkout" element={<CheckoutPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ShopProvider>
    </AuthProvider>
  );
}
