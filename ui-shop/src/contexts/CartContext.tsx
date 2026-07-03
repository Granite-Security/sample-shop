import { createContext, useContext, useState, useCallback, type ReactNode } from 'react';
import type { Product, CartItem } from '../types';

interface CartContext {
  items: CartItem[];
  itemCount: number;
  total: number;
  addItem: (product: Product, qty?: number) => void;
  removeItem: (productId: number) => void;
  updateQuantity: (productId: number, qty: number) => void;
  clearCart: () => void;
}

const CartCtx = createContext<CartContext | null>(null);

function loadCart(): CartItem[] {
  try {
    const raw = localStorage.getItem('cart');
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveCart(items: CartItem[]) {
  localStorage.setItem('cart', JSON.stringify(items));
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CartItem[]>(loadCart);

  const persist = useCallback((next: CartItem[]) => {
    setItems(next);
    saveCart(next);
  }, []);

  const addItem = useCallback((product: Product, qty = 1) => {
    setItems(prev => {
      const existing = prev.find(i => i.product.id === product.id);
      const next = existing
        ? prev.map(i =>
            i.product.id === product.id
              ? { ...i, quantity: i.quantity + qty }
              : i
          )
        : [...prev, { product, quantity: qty }];
      saveCart(next);
      return next;
    });
  }, []);

  const removeItem = useCallback((productId: number) => {
    persist(items.filter(i => i.product.id !== productId));
  }, [items, persist]);

  const updateQuantity = useCallback((productId: number, qty: number) => {
    if (qty <= 0) {
      removeItem(productId);
      return;
    }
    persist(items.map(i =>
      i.product.id === productId ? { ...i, quantity: qty } : i
    ));
  }, [items, persist, removeItem]);

  const clearCart = useCallback(() => {
    persist([]);
  }, [persist]);

  const itemCount = items.reduce((s, i) => s + i.quantity, 0);
  const total = items.reduce((s, i) => s + i.product.price * i.quantity, 0);

  return (
    <CartCtx.Provider value={{ items, itemCount, total, addItem, removeItem, updateQuantity, clearCart }}>
      {children}
    </CartCtx.Provider>
  );
}

export function useCart(): CartContext {
  const ctx = useContext(CartCtx);
  if (!ctx) throw new Error('useCart must be used within CartProvider');
  return ctx;
}
