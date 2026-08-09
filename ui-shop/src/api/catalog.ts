import { request } from './client';
import type {
  Category,
  CreateCategoryRequest,
  CreateProductRequest,
  PagedResult,
  Product,
} from '../types';

export const catalogApi = {
  getCategories: (page = 0, size = 50) =>
    request<PagedResult<Category>>(`/api/shop/categories?page=${page}&size=${size}`),

  createCategory: (body: CreateCategoryRequest) =>
    request<Category>('/api/shop/categories', { method: 'POST', body: JSON.stringify(body) }),

  updateCategory: (id: number, body: CreateCategoryRequest) =>
    request<Category>(`/api/shop/categories/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteCategory: (id: number) =>
    request<void>(`/api/shop/categories/${id}`, { method: 'DELETE' }),

  getProducts: (page = 0, size = 50) =>
    request<PagedResult<Product>>(`/api/shop/products?page=${page}&size=${size}`),

  // Admin listing: includes discontinued products so they can be restored.
  listForAdmin: (page = 0, size = 100) =>
    request<PagedResult<Product>>(
      `/api/shop/products?page=${page}&size=${size}&includeDiscontinued=true`,
    ),

  getProduct: (id: number) =>
    request<Product>(`/api/shop/products/${id}`),

  createProduct: (body: CreateProductRequest) =>
    request<Product>('/api/shop/products', { method: 'POST', body: JSON.stringify(body) }),

  updateProduct: (id: number, body: CreateProductRequest) =>
    request<Product>(`/api/shop/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),

  deleteProduct: (id: number) =>
    request<void>(`/api/shop/products/${id}`, { method: 'DELETE' }),
};
