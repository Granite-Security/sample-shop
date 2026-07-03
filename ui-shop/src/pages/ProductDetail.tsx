import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router';
import { api } from '../api';
import type { Product } from '../types';
import { useCart } from '../contexts/CartContext';

export default function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const { addItem } = useCart();

  useEffect(() => {
    if (!id) return;
    api.getProduct(Number(id))
      .then(setProduct)
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="page"><p>Loading...</p></div>;
  if (!product) return <div className="page"><p>Product not found.</p></div>;

  return (
    <div className="page product-detail">
      <div className="product-detail-img">
        {product.imageUrl
          ? <img src={product.imageUrl} alt={product.name} />
          : <div className="img-placeholder">No Image</div>}
      </div>
      <div className="product-detail-info">
        <h1>{product.name}</h1>
        <p className="price">${product.price.toFixed(2)}</p>
        <p className="stock">
          {product.stock > 0 ? `In stock (${product.stock} available)` : 'Out of stock'}
        </p>
        <p className="description">{product.description}</p>
        <button
          className="btn btn-primary"
          disabled={product.stock <= 0}
          onClick={() => addItem(product)}
        >
          Add to Cart
        </button>
        <Link to="/catalog" className="btn" style={{ marginLeft: 8 }}>Back</Link>
      </div>
    </div>
  );
}
