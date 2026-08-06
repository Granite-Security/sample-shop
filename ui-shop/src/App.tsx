import { Routes, Route } from 'react-router'
import Layout from './components/Layout'
import RequireAuth from './components/RequireAuth'
import AccountLayout from './components/AccountLayout'
import Home from './pages/Home'
import Catalog from './pages/Catalog'
import ProductDetail from './pages/ProductDetail'
import Cart from './pages/Cart'
import Checkout from './pages/Checkout'
import Orders from './pages/Orders'
import OrderDetail from './pages/OrderDetail'
import RetryPayment from './pages/RetryPayment'
import Admin from './pages/Admin'
import ProductsManagement from './pages/ProductsManagement'
import ProductForm from './pages/ProductForm'
import DeliveryManagement from './pages/DeliveryManagement'
import UsersManagement from './pages/UsersManagement'
import UserProfileView from './pages/UserProfileView'
import Login from './pages/Login'
import Register from './pages/Register'
import ResetPasswordRequest from './pages/ResetPasswordRequest'
import ResetPasswordConfirm from './pages/ResetPasswordConfirm'
import Callback from './pages/Callback'
import Addresses from './pages/Addresses'
import Messages from './pages/Messages'
import Balance from './pages/Balance'
import Treasury from './pages/Treasury'
import Profile from './pages/Profile'
import Password from './pages/Password'
import Files from './pages/Files'

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="catalog" element={<Catalog />} />
        <Route path="catalog/:id" element={<ProductDetail />} />
        <Route path="cart" element={<Cart />} />
        <Route path="checkout" element={<Checkout />} />
        <Route path="orders/:id/pay" element={<RetryPayment />} />
        <Route element={<RequireAuth />}>
          <Route element={<AccountLayout />}>
            <Route path="profile" element={<Profile />} />
            <Route path="profile/password" element={<Password />} />
            <Route path="profile/files" element={<Files />} />
            <Route path="profile/balance" element={<Balance />} />
            <Route path="profile/treasury" element={<Treasury />} />
            <Route path="addresses" element={<Addresses />} />
            <Route path="messages" element={<Messages />} />
            <Route path="orders" element={<Orders />} />
            <Route path="orders/:id" element={<OrderDetail />} />
          </Route>
        </Route>
        <Route path="admin" element={<Admin />} />
        <Route path="admin/products" element={<ProductsManagement />} />
        <Route path="admin/products/new" element={<ProductForm />} />
        <Route path="admin/products/:id/edit" element={<ProductForm />} />
        <Route path="admin/deliveries" element={<DeliveryManagement />} />
        <Route path="admin/users" element={<UsersManagement />} />
        <Route path="admin/users/:username" element={<UserProfileView />} />
        <Route path="login" element={<Login />} />
        <Route path="register" element={<Register />} />
        <Route path="reset-password" element={<ResetPasswordRequest />} />
        <Route path="reset-password/confirm" element={<ResetPasswordConfirm />} />
        <Route path="callback" element={<Callback />} />
      </Route>
    </Routes>
  )
}
