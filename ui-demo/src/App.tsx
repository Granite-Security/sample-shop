import { useEffect } from 'react';
import { BrowserRouter, Outlet, Route, Routes, useLocation } from 'react-router';
import { ShopProvider } from './store';
import { AuthProvider } from './auth';
import { MessagesProvider } from './messages';
import { Callback } from './components/Callback';
import { Header } from './components/Header';
import { Footer } from './components/Footer';
import { CartDrawer } from './components/CartDrawer';
import RequireAuth from './components/RequireAuth';
import { AccountLayout } from './components/AccountLayout';
import { Home } from './pages/Home';
import { ShopPage } from './pages/ShopPage';
import { OurStoryPage } from './pages/OurStoryPage';
import { GiftsPage } from './pages/GiftsPage';
import { ContactPage } from './pages/ContactPage';
import { ProductPage } from './pages/ProductPage';
import { AdminPage } from './pages/AdminPage';
import { UsersManagementPage } from './pages/UsersManagementPage';
import { DeliveriesPage } from './pages/DeliveriesPage';
import { UserProfileViewPage } from './pages/UserProfileViewPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { ProfilePage } from './pages/ProfilePage';
import { PasswordPage } from './pages/PasswordPage';
import { FilesPage } from './pages/FilesPage';
import { AddressesPage } from './pages/AddressesPage';
import { MessagesPage } from './pages/MessagesPage';
import { BalancePage } from './pages/BalancePage';
import { TreasuryPage } from './pages/TreasuryPage';
import { RevenuesPage } from './pages/RevenuesPage';
import { OrdersPage } from './pages/OrdersPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { Register } from './pages/Register';
import { ResetPasswordRequest } from './pages/ResetPasswordRequest';
import { ResetPasswordConfirm } from './pages/ResetPasswordConfirm';

/** Content is per-page now, so every navigation should start at the top. */
function ScrollToTop() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);
  return null;
}

function Layout() {
  return (
    <>
      <ScrollToTop />
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
      {/* Inside AuthProvider: the unread poll is gated on being signed in. */}
      <MessagesProvider>
        <ShopProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/callback" element={<Callback />} />
            <Route element={<Layout />}>
              <Route index element={<Home />} />
              <Route path="shop" element={<ShopPage />} />
              <Route path="our-story" element={<OurStoryPage />} />
              <Route path="gifts" element={<GiftsPage />} />
              <Route path="contact" element={<ContactPage />} />
              <Route path="products/:id" element={<ProductPage />} />
              <Route path="admin" element={<AdminPage />} />
              <Route path="admin/users" element={<UsersManagementPage />} />
              <Route path="admin/deliveries" element={<DeliveriesPage />} />
              <Route path="admin/users/:username" element={<UserProfileViewPage />} />
              <Route path="checkout" element={<CheckoutPage />} />
              {/* payment redirects a shopper to /orders/{id} after a redirect payment,
                  and it should not have to know how each storefront routes
                  (docs/bugs/redirects.md §3, D7). ui-shop serves those paths at the top
                  level; these aliases make them work here too, without moving the
                  account pages out of /profile/* or breaking existing links. */}
              <Route element={<RequireAuth />}>
                <Route element={<AccountLayout />}>
                  <Route path="orders" element={<OrdersPage />} />
                  <Route path="orders/:id" element={<OrderDetailPage />} />
                  <Route path="profile" element={<ProfilePage />} />
                  <Route path="profile/password" element={<PasswordPage />} />
                  <Route path="profile/files" element={<FilesPage />} />
                  <Route path="profile/addresses" element={<AddressesPage />} />
                  <Route path="profile/messages" element={<MessagesPage />} />
                  <Route path="profile/balance" element={<BalancePage />} />
                  <Route path="profile/treasury" element={<TreasuryPage />} />
                  <Route path="profile/revenues" element={<RevenuesPage />} />
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
      </MessagesProvider>
    </AuthProvider>
  );
}
