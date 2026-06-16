import { useState } from 'react';
import { useNavigate, Link } from 'react-router';
import { useCart } from '../contexts/CartContext';
import { useAuth } from '../auth';
import { api } from '../api';
import type { OrderResponse } from '../types';

export default function Checkout() {
  const { items, total, clearCart } = useCart();
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [placing, setPlacing] = useState(false);
  const [error, setError] = useState('');
  const [order, setOrder] = useState<OrderResponse | null>(null);

  if (!isAuthenticated) {
    navigate('/login', { replace: true });
    return null;
  }

  if (items.length === 0 && !order) {
    return (
      <div className="page">
        <h1>Checkout</h1>
        <p>Your cart is empty.</p>
        <Link to="/catalog" className="btn">Browse Products</Link>
      </div>
    );
  }

  if (order) {
    return (
      <div className="page">
        <h1>Order Placed!</h1>
        <p>Order #{order.id} — Status: {order.status}</p>
        <p>Total: ${Number(order.total).toFixed(2)}</p>
        <Link to={`/orders/${order.id}`} className="btn">View Order</Link>
      </div>
    );
  }

  const handlePlaceOrder = async () => {
    setPlacing(true);
    setError('');
    try {
      const result = await api.placeOrder({
        items: items.map(i => ({ productId: i.product.id, quantity: i.quantity })),
      });
      setOrder(result);
      clearCart();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to place order');
    } finally {
      setPlacing(false);
    }
  };

  return (
    <div className="page checkout-page">
      <h1>Checkout</h1>
      <div className="checkout-items">
        {items.map(({ product, quantity }) => (
          <div key={product.id} className="checkout-item">
            <span>{product.name} × {quantity}</span>
            <span>${(product.price * quantity).toFixed(2)}</span>
          </div>
        ))}
      </div>
      <h2>Total: ${total.toFixed(2)}</h2>
      {error && <p className="error">{error}</p>}
      <button
        className="btn btn-primary"
        disabled={placing}
        onClick={handlePlaceOrder}
      >
        {placing ? 'Placing Order...' : 'Place Order'}
      </button>
    </div>
  );
}
