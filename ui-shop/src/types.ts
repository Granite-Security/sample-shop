export interface Product {
  id: number;
  name: string;
  description: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl: string;
}

export interface Category {
  id: number;
  name: string;
  description: string;
}

export interface OrderItemResponse {
  id: number;
  productId: number;
  quantity: number;
  unitPrice: number;
}

export interface OrderResponse {
  id: number;
  username: string;
  status: string;
  total: number;
  createdAt: string;
  items: OrderItemResponse[];
}

export interface PagedResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface CreateProductRequest {
  name: string;
  description: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl: string;
}

export interface CreateCategoryRequest {
  name: string;
  description: string;
}

export interface PlaceOrderRequest {
  items: { productId: number; quantity: number }[];
}

export interface CartItem {
  product: Product;
  quantity: number;
}
