import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { Product } from '../types';
import { getDefaultMedia } from '../utils/media';

export default function ProductsManagement() {
  const { isAdmin, isManager } = useAuth();
  const canManage = isAdmin || isManager;
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    // Admin listing: includes discontinued products, since the storefront
    // listing hides them and they would otherwise be unreachable to restore.
    api.catalog.listForAdmin(0, 100)
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

  // Soft delete: the product leaves the catalog but is kept, because order_item
  // references it and stores no product name of its own.
  const handleDiscontinue = async (id: number) => {
    if (!window.confirm('Discontinue this product? It stays on past orders and can be restored.')) return;
    setError(null);
    try {
      await api.catalog.deleteProduct(id);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRestore = async (p: Product) => {
    setError(null);
    try {
      // Only `discontinued` is stated; the rest is sent unchanged so this does
      // not double as an edit.
      await api.catalog.updateProduct(p.id, {
        name: p.name,
        description: p.description,
        price: p.price,
        stock: p.stock,
        categoryId: p.categoryId,
        imageUrl: p.imageUrl,
        media: p.media ?? [],
        discontinued: false,
      });
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
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
          {products.map(p => {
            const thumbnail = getDefaultMedia(p.media)?.url ?? p.imageUrl;
            return (
            <div key={p.id} style={{
              display: 'flex', alignItems: 'center', gap: 12, padding: 12,
              background: 'var(--surface)', borderRadius: 8, border: '1px solid var(--border)',
            }}>
              <div style={{ width: 56, height: 56, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-secondary)', borderRadius: 6 }}>
                {thumbnail
                  ? <img src={thumbnail} alt={p.name} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
                  : <span className="img-placeholder">—</span>}
              </div>
              <div style={{ flex: 1 }}>
                <strong>{p.name}</strong>
                <p style={{ margin: '2px 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  ${p.price.toFixed(2)} · {p.stock} in stock
                  {p.discontinued && (
                    <span style={{
                      marginLeft: 8, padding: '1px 6px', borderRadius: 4,
                      border: '1px solid var(--border)', fontSize: '0.7rem', textTransform: 'uppercase',
                    }}>
                      Discontinued
                    </span>
                  )}
                </p>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <Link to={`/admin/products/${p.id}/edit`} className="btn">Edit</Link>
                {p.discontinued ? (
                  <button className="btn" onClick={() => handleRestore(p)}>Restore</button>
                ) : (
                  <button className="btn" style={{ color: 'var(--danger)' }} onClick={() => handleDiscontinue(p.id)}>Discontinue</button>
                )}
              </div>
            </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
