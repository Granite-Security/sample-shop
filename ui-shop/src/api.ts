import { accountApi } from './api/account';
import { authApi } from './api/auth';
import { catalogApi } from './api/catalog';
import { deliveryApi } from './api/delivery';
import { messagesApi } from './api/messages';
import { ordersApi } from './api/orders';
import { paymentsApi } from './api/payments';
import { profileApi } from './api/profile';
import { shopApi } from './api/shop';
import { storageApi } from './api/storage';

export const api = {
  account: accountApi,
  auth: authApi,
  shop: shopApi,
  catalog: catalogApi,
  orders: ordersApi,
  payments: paymentsApi,
  profile: profileApi,
  delivery: deliveryApi,
  messages: messagesApi,
  storage: storageApi,
};

export { setAccessToken } from './api/client';
