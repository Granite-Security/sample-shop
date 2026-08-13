import { request } from './client';
import type { CreateVoucherRequest, PackagingChoice, VoucherAdmin, VoucherPreview } from '../types';

export const vouchersApi = {
  /**
   * Prices a code against the cart. Read-only and non-binding: nothing is stored and
   * the code is not reserved, so placement validates it again and can still refuse it.
   *
   * A POST for the same reason the packaging quote is one — the cart is the question.
   */
  preview: (
    code: string,
    items: { productId: number; quantity: number }[],
    packaging?: PackagingChoice[],
  ) =>
    request<VoucherPreview>('/api/shop/vouchers/preview', {
      method: 'POST',
      body: JSON.stringify({ code, items, packaging }),
    }),

  // Admin. Listing is ADMIN or MANAGER; creating and revoking are ADMIN alone,
  // because a voucher decides what every future shopper is charged.
  list: () => request<VoucherAdmin[]>('/api/shop/admin/vouchers'),

  create: (body: CreateVoucherRequest) =>
    request<VoucherAdmin>('/api/shop/admin/vouchers', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  /** Withdraws it. Never a hard delete — placed orders reference it. */
  revoke: (id: number) =>
    request<VoucherAdmin>(`/api/shop/admin/vouchers/${id}`, { method: 'DELETE' }),
};
