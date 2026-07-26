import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router';
import { api } from '../api';
import type { Product } from '../types';
import { useCart } from '../contexts/CartContext';

export default function ProductDetail() {
  const { id } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeImage, setActiveImage] = useState<string | null>(null);
  const { addItem } = useCart();

  useEffect(() => {
    if (!id) return;
    api.catalog.getProduct(Number(id))
      .then(p => {
        setProduct(p);
        setActiveImage(p.imageUrl || p.media[0]?.url || null);
      })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="page"><p>Loading...</p></div>;
  if (!product) return <div className="page"><p>Product not found.</p></div>;

  return (
    <div className="page product-detail">
      <div>
        <div className="product-detail-img">
          {activeImage
            ? <img src={activeImage} alt={product.name} />
            : <div className="img-placeholder">No Image</div>}
        </div>
        {product.media.length > 1 && (
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            {product.media.map(item => (
              <button key={item.key} onClick={() => setActiveImage(item.url)}
                style={{ width: 48, height: 48, padding: 0, border: '1px solid var(--border)', borderRadius: 6, background: 'var(--bg-secondary)', cursor: 'pointer' }}>
                <img src={item.url} alt="" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
              </button>
            ))}
          </div>
        )}
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
