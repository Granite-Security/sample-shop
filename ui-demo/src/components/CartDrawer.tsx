import { useNavigate } from 'react-router';
import { formatPrice, useShop } from '../store';
import { ChocolateArt, variantFor } from './ChocolateArt';
import { CloseIcon } from './icons';

const FREE_SHIPPING_THRESHOLD = 75;

export function CartDrawer() {
  const { cart, cartTotal, cartOpen, setCartOpen, setQuantity, removeFromCart, live } = useShop();
  const navigate = useNavigate();

  const remaining = FREE_SHIPPING_THRESHOLD - cartTotal;

  return (
    <div
      className={`fixed inset-0 z-50 transition-[visibility] ${cartOpen ? 'visible' : 'invisible delay-500'}`}
      aria-hidden={!cartOpen}
    >
      <button
        aria-label="Close cart"
        onClick={() => setCartOpen(false)}
        className={`absolute inset-0 bg-espresso/50 backdrop-blur-sm transition-opacity duration-500 ${
          cartOpen ? 'opacity-100' : 'opacity-0'
        }`}
        tabIndex={cartOpen ? 0 : -1}
      />
      <aside
        role="dialog"
        aria-label="Shopping cart"
        className={`absolute right-0 top-0 h-full w-full max-w-md bg-ivory shadow-2xl transition-transform duration-700 ease-luxe flex flex-col ${
          cartOpen ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between border-b border-cocoa/10 px-6 py-5">
          <h2 className="font-display text-2xl text-cocoa">Your Selection</h2>
          <button onClick={() => setCartOpen(false)} aria-label="Close cart" className="p-2 text-cocoa">
            <CloseIcon />
          </button>
        </div>

        {cart.length === 0 ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-4 px-8 text-center">
            <p className="font-display text-xl text-cocoa">Your cart is empty</p>
            <p className="text-sm text-cocoa/60">
              Every piece is made in small batches — explore the collection while today's batch lasts.
            </p>
            <a
              href="/shop"
              onClick={() => setCartOpen(false)}
              className="mt-2 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
            >
              Shop Collection
            </a>
          </div>
        ) : (
          <>
            <ul className="flex-1 overflow-y-auto divide-y divide-cocoa/10 px-6">
              {cart.map(({ product, quantity }) => (
                <li key={product.id} className="flex gap-4 py-5">
                  <div className="h-20 w-20 shrink-0 overflow-hidden rounded-md">
                    <ChocolateArt
                      seed={product.id}
                      variant={variantFor(product.name, product.id)}
                      className="h-full w-full"
                    />
                  </div>
                  <div className="flex flex-1 flex-col">
                    <div className="flex justify-between gap-2">
                      <p className="font-display text-cocoa leading-snug">{product.name}</p>
                      <p className="text-sm text-cocoa">{formatPrice(product.price * quantity)}</p>
                    </div>
                    <div className="mt-auto flex items-center justify-between pt-2">
                      <div className="flex items-center border border-cocoa/20 text-cocoa">
                        <button
                          className="px-3 py-1"
                          aria-label={`Decrease quantity of ${product.name}`}
                          onClick={() => setQuantity(product.id, quantity - 1)}
                        >
                          −
                        </button>
                        <span className="w-6 text-center text-sm">{quantity}</span>
                        <button
                          className="px-3 py-1"
                          aria-label={`Increase quantity of ${product.name}`}
                          onClick={() => setQuantity(product.id, quantity + 1)}
                        >
                          +
                        </button>
                      </div>
                      <button
                        className="text-xs uppercase tracking-wider text-cocoa/50 underline-offset-2 hover:underline"
                        onClick={() => removeFromCart(product.id)}
                      >
                        Remove
                      </button>
                    </div>
                  </div>
                </li>
              ))}
            </ul>

            <div className="border-t border-cocoa/10 px-6 py-5 space-y-4">
              {remaining > 0 ? (
                <p className="text-xs text-cocoa/60 tracking-wide">
                  You're {formatPrice(remaining)} away from complimentary shipping.
                </p>
              ) : (
                <p className="text-xs text-sage tracking-wide">Complimentary shipping unlocked.</p>
              )}
              <div className="flex justify-between font-display text-xl text-cocoa">
                <span>Subtotal</span>
                <span>{formatPrice(cartTotal)}</span>
              </div>
              <button
                onClick={() => {
                  setCartOpen(false);
                  navigate('/checkout');
                }}
                className="w-full bg-cocoa py-4 text-xs uppercase tracking-[0.2em] text-ivory transition-colors duration-300 hover:bg-espresso"
              >
                Proceed to Checkout
              </button>
              {!live && (
                <p className="text-center text-[11px] text-cocoa/40">
                  The shop backend is unreachable — preview pieces can't be ordered.
                </p>
              )}
            </div>
          </>
        )}
      </aside>
    </div>
  );
}
