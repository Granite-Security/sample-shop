import { Link } from 'react-router';
import { useCart } from '../contexts/CartContext';
import { useAuth } from '../auth';

export default function Cart() {
  const { items, total, updateQuantity, removeItem } = useCart();
  const { isAuthenticated } = useAuth();

  if (items.length === 0) {
    return (
      <div className="page cart-page">
        <h1>Shopping Cart</h1>
        <p>Your cart is empty.</p>
        <Link to="/catalog" className="btn">Browse Products</Link>
      </div>
    );
  }

  return (
    <div className="page cart-page">
      <h1>Shopping Cart</h1>
      <div className="cart-items">
        {items.map(({ product, quantity }) => (
          <div key={product.id} className="cart-item">
            <div className="cart-item-info">
              <h3>{product.name}</h3>
              <p>${product.price.toFixed(2)} each</p>
            </div>
            <div className="cart-item-qty">
              <button onClick={() => updateQuantity(product.id, quantity - 1)}>-</button>
              <span>{quantity}</span>
              <button onClick={() => updateQuantity(product.id, quantity + 1)}>+</button>
            </div>
            <p className="cart-item-total">${(product.price * quantity).toFixed(2)}</p>
            <button className="btn-sm btn-danger" onClick={() => removeItem(product.id)}>
              Remove
            </button>
          </div>
        ))}
      </div>
      <div className="cart-summary">
        <h2>Total: ${total.toFixed(2)}</h2>
        {isAuthenticated ? (
          <Link to="/checkout" className="btn btn-primary">Proceed to Checkout</Link>
        ) : (
          <Link to="/login" className="btn">Login to Checkout</Link>
        )}
      </div>
    </div>
  );
}
