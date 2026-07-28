import { BrowserRouter, Outlet, Route, Routes } from 'react-router';
import { ShopProvider } from './store';
import { AuthProvider } from './auth';
import { Callback } from './components/Callback';
import { Header } from './components/Header';
import { Footer } from './components/Footer';
import { CartDrawer } from './components/CartDrawer';
import RequireAuth from './components/RequireAuth';
import { AccountLayout } from './components/AccountLayout';
import { Home } from './pages/Home';
import { ProductPage } from './pages/ProductPage';
import { AdminPage } from './pages/AdminPage';
import { UsersManagementPage } from './pages/UsersManagementPage';
import { UserProfileViewPage } from './pages/UserProfileViewPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { ProfilePage } from './pages/ProfilePage';
import { PasswordPage } from './pages/PasswordPage';
import { FilesPage } from './pages/FilesPage';
import { AddressesPage } from './pages/AddressesPage';
import { OrdersPage } from './pages/OrdersPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { Register } from './pages/Register';
import { ResetPasswordRequest } from './pages/ResetPasswordRequest';
import { ResetPasswordConfirm } from './pages/ResetPasswordConfirm';

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
              <Route path="admin/users" element={<UsersManagementPage />} />
              <Route path="admin/users/:username" element={<UserProfileViewPage />} />
              <Route path="checkout" element={<CheckoutPage />} />
              <Route element={<RequireAuth />}>
                <Route element={<AccountLayout />}>
                  <Route path="profile" element={<ProfilePage />} />
                  <Route path="profile/password" element={<PasswordPage />} />
                  <Route path="profile/files" element={<FilesPage />} />
                  <Route path="profile/addresses" element={<AddressesPage />} />
                  <Route path="profile/orders" element={<OrdersPage />} />
                  <Route path="profile/orders/:id" element={<OrderDetailPage />} />
                </Route>
              </Route>
              <Route path="register" element={<Register />} />
              <Route path="reset-password" element={<ResetPasswordRequest />} />
              <Route path="reset-password/confirm" element={<ResetPasswordConfirm />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ShopProvider>
    </AuthProvider>
  );
}
