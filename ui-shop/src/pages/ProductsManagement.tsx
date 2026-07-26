import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { Product } from '../types';

export default function ProductsManagement() {
  const { isAdmin, isManager } = useAuth();
  const canManage = isAdmin || isManager;
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api.catalog.getProducts(0, 100)
      .then(result => setProducts(result.items))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!canManage) return;
    load();
  }, [canManage]);

  if (!canManage) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have admin privileges.</p>
      </div>
    );
  }

  const handleDelete = async (id: number) => {
    if (!window.confirm('Delete this product?')) return;
    await api.catalog.deleteProduct(id);
    load();
  };

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>Products</h1>
        <Link to="/admin/products/new" className="btn btn-primary" style={{ textDecoration: 'none' }}>
          Add Product
        </Link>
      </div>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      {loading ? (
        <p>Loading products...</p>
      ) : products.length === 0 ? (
        <p>No products yet.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {products.map(p => (
            <div key={p.id} style={{
              display: 'flex', alignItems: 'center', gap: 12, padding: 12,
              background: 'var(--surface)', borderRadius: 8, border: '1px solid var(--border)',
            }}>
              <div style={{ width: 56, height: 56, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-secondary)', borderRadius: 6 }}>
                {p.imageUrl
                  ? <img src={p.imageUrl} alt={p.name} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
                  : <span className="img-placeholder">—</span>}
              </div>
              <div style={{ flex: 1 }}>
                <strong>{p.name}</strong>
                <p style={{ margin: '2px 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  ${p.price.toFixed(2)} · {p.stock} in stock
                </p>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <Link to={`/admin/products/${p.id}/edit`} className="btn">Edit</Link>
                <button className="btn" style={{ color: 'var(--danger)' }} onClick={() => handleDelete(p.id)}>Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
