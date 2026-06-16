import { Link } from 'react-router';
import type { Product } from '../types';
import { useCart } from '../contexts/CartContext';

interface Props {
  product: Product;
}

export default function ProductCard({ product }: Props) {
  const { addItem } = useCart();

  return (
    <div className="product-card">
      <div className="product-card-img">
        {product.imageUrl
          ? <img src={product.imageUrl} alt={product.name} />
          : <div className="img-placeholder">No Image</div>}
      </div>
      <div className="product-card-body">
        <h3>{product.name}</h3>
        <p className="price">${product.price.toFixed(2)}</p>
        <p className="stock">
          {product.stock > 0 ? `${product.stock} in stock` : 'Out of stock'}
        </p>
        <div className="product-card-actions">
          <Link to={`/catalog/${product.id}`} className="btn-sm">Details</Link>
          <button
            className="btn-sm btn-primary"
            disabled={product.stock <= 0}
            onClick={() => addItem(product)}
          >
            Add to Cart
          </button>
        </div>
      </div>
    </div>
  );
}
