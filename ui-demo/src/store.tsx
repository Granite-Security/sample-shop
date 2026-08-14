import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { api, FALLBACK_PRODUCTS, SICHOCOLATE_CATEGORY_NAME } from './api';
import type { Category, Product } from './types';

export interface CartLine {
  product: Product;
  quantity: number;
}

interface ShopState {
  products: Product[];
  categories: Category[];
  /** id of the chocolate category on the live backend, if resolved */
  chocolateCategoryId: number | null;
  /** true while the first catalog request is in flight */
  loading: boolean;
  /** true when the shop backend answered; false → fallback catalog shown */
  live: boolean;
  /** re-fetches the catalog (used after admin CRUD) */
  refresh: () => Promise<void>;
  cart: CartLine[];
  cartCount: number;
  cartTotal: number;
  cartOpen: boolean;
  wishlist: Set<number>;
  addToCart: (product: Product) => void;
  removeFromCart: (productId: number) => void;
  clearCart: () => void;
  setQuantity: (productId: number, quantity: number) => void;
  toggleWishlist: (productId: number) => void;
  setCartOpen: (open: boolean) => void;
}

const ShopContext = createContext<ShopState | null>(null);

export function ShopProvider({ children }: { children: ReactNode }) {
  const [products, setProducts] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [chocolateCategoryId, setChocolateCategoryId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [live, setLive] = useState(false);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [cartOpen, setCartOpen] = useState(false);
  const [wishlist, setWishlist] = useState<Set<number>>(new Set());

  // The shared shop catalog is a general store; SI Chocolate sells only what
  // lives in its own category (see docs/plans/add-chocolates.md). Membership is
  // a property of the row, not a hardcoded list of names here — that is what
  // lets a piece added in the back of house show up in the boutique.
  //
  // Resolve the category by exact name and take everything in it, then top the
  // grid up with editorial fallback pieces so the storefront always feels
  // complete. No category (backend down, or shop not yet migrated) means no
  // live catalog, and the fallback pieces stand in whole.
  const refresh = useCallback(async () => {
    try {
      const [productPage, categoryPage] = await Promise.all([api.getProducts(), api.getCategories()]);
      const chocolateCategory = categoryPage.items.find((c) => c.name === SICHOCOLATE_CATEGORY_NAME);
      const chocolate = chocolateCategory
        ? productPage.items.filter((p) => p.categoryId === chocolateCategory.id)
        : [];
      const topUp = FALLBACK_PRODUCTS.filter((p) => !chocolate.some((c) => c.name === p.name)).slice(
        0,
        Math.max(0, 8 - chocolate.length),
      );
      setCategories(categoryPage.items);
      setChocolateCategoryId(chocolateCategory?.id ?? null);
      setProducts(chocolate.length > 0 ? [...chocolate, ...topUp] : FALLBACK_PRODUCTS);
      setLive(chocolate.length > 0);
    } catch {
      setProducts((prev) => (prev.length > 0 ? prev : FALLBACK_PRODUCTS));
    }
  }, []);

  useEffect(() => {
    refresh().finally(() => setLoading(false));
  }, [refresh]);

  const addToCart = useCallback((product: Product) => {
    setCart((lines) => {
      const existing = lines.find((l) => l.product.id === product.id);
      if (existing) {
        return lines.map((l) =>
          l.product.id === product.id ? { ...l, quantity: l.quantity + 1 } : l,
        );
      }
      return [...lines, { product, quantity: 1 }];
    });
    setCartOpen(true);
  }, []);

  const removeFromCart = useCallback((productId: number) => {
    setCart((lines) => lines.filter((l) => l.product.id !== productId));
  }, []);

  const clearCart = useCallback(() => setCart([]), []);

  const setQuantity = useCallback((productId: number, quantity: number) => {
    setCart((lines) =>
      quantity <= 0
        ? lines.filter((l) => l.product.id !== productId)
        : lines.map((l) => (l.product.id === productId ? { ...l, quantity } : l)),
    );
  }, []);

  const toggleWishlist = useCallback((productId: number) => {
    setWishlist((prev) => {
      const next = new Set(prev);
      if (next.has(productId)) next.delete(productId);
      else next.add(productId);
      return next;
    });
  }, []);

  const value = useMemo<ShopState>(
    () => ({
      products,
      categories,
      chocolateCategoryId,
      loading,
      live,
      refresh,
      cart,
      cartCount: cart.reduce((n, l) => n + l.quantity, 0),
      cartTotal: cart.reduce((n, l) => n + l.quantity * l.product.price, 0),
      cartOpen,
      wishlist,
      addToCart,
      removeFromCart,
      clearCart,
      setQuantity,
      toggleWishlist,
      setCartOpen,
    }),
    [products, categories, chocolateCategoryId, loading, live, refresh, cart, cartOpen, wishlist, addToCart, removeFromCart, clearCart, setQuantity, toggleWishlist],
  );

  return <ShopContext.Provider value={value}>{children}</ShopContext.Provider>;
}

export function useShop(): ShopState {
  const ctx = useContext(ShopContext);
  if (!ctx) throw new Error('useShop must be used within ShopProvider');
  return ctx;
}

/**
 * Storefront prices are shown in euros.
 *
 * <p>Note this is a display choice only, and a knowingly inexact one: the shop
 * charges CHF, and the payment step will say so. The two are close enough that
 * the boutique is happy to show €, but nothing here converts anything — the same
 * number is simply labelled differently, so a price shown as €75 is charged as
 * CHF 75. Balance, treasury and revenue figures are money movements rather than
 * prices, and stay in CHF.
 *
 * <p>The locale stays en-US so the copy's number format is unchanged (€75.00,
 * not 75,00 €) — only the symbol moves.
 */
export const formatPrice = (value: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'EUR' }).format(value);
