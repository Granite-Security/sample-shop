import { catalogApi } from './api/catalog';
import { deliveryApi } from './api/delivery';
import { ordersApi } from './api/orders';
import { paymentsApi } from './api/payments';
import { profileApi } from './api/profile';
import { shopApi } from './api/shop';

export const api = {
  shop: shopApi,
  catalog: catalogApi,
  orders: ordersApi,
  payments: paymentsApi,
  profile: profileApi,
  delivery: deliveryApi,
};

export { setAccessToken } from './api/client';
