import { accountApi } from './api/account';
import { authApi } from './api/auth';
import { balanceApi } from './api/balance';
import { catalogApi } from './api/catalog';
import { contactApi } from './api/contact';
import { deliveryApi } from './api/delivery';
import { messagesApi } from './api/messages';
import { ordersApi } from './api/orders';
import { packagingApi } from './api/packaging';
import { paymentsApi } from './api/payments';
import { profileApi } from './api/profile';
import { reportsApi } from './api/reports';
import { shopApi } from './api/shop';
import { storageApi } from './api/storage';

export const api = {
  account: accountApi,
  auth: authApi,
  balance: balanceApi,
  shop: shopApi,
  catalog: catalogApi,
  contact: contactApi,
  orders: ordersApi,
  packaging: packagingApi,
  payments: paymentsApi,
  profile: profileApi,
  reports: reportsApi,
  delivery: deliveryApi,
  messages: messagesApi,
  storage: storageApi,
};

export { setAccessToken, setTokenRefresher } from './api/client';
