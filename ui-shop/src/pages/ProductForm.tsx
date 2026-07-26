import { useEffect, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { Category, CreateProductRequest, MediaItem } from '../types';

const emptyForm: CreateProductRequest = {
  name: '',
  description: '',
  price: 0,
  stock: 0,
  categoryId: 0,
  imageUrl: '',
  media: [],
};

export default function ProductForm() {
  const { id } = useParams();
  const isEdit = id !== undefined;
  const navigate = useNavigate();
  const { isAdmin, isManager } = useAuth();
  const canManage = isAdmin || isManager;

  const [categories, setCategories] = useState<Category[]>([]);
  const [form, setForm] = useState<CreateProductRequest>(emptyForm);
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!canManage) return;
    api.catalog.getCategories(0, 100).then(result => setCategories(result.items));
  }, [canManage]);

  useEffect(() => {
    if (!canManage || !isEdit) return;
    api.catalog.getProduct(Number(id))
      .then(product => setForm({
        name: product.name,
        description: product.description,
        price: product.price,
        stock: product.stock,
        categoryId: product.categoryId,
        imageUrl: product.imageUrl ?? '',
        media: product.media ?? [],
      }))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [canManage, isEdit, id]);

  if (!canManage) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have admin privileges.</p>
      </div>
    );
  }

  const handleSubmit = async () => {
    setSaving(true);
    setError(null);
    try {
      if (isEdit) {
        await api.catalog.updateProduct(Number(id), form);
        navigate('/admin/products');
      } else {
        const created = await api.catalog.createProduct(form);
        navigate(`/admin/products/${created.id}/edit`);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const persistMedia = async (media: MediaItem[]) => {
    const next = { ...form, media };
    setForm(next);
    if (isEdit) {
      await api.catalog.updateProduct(Number(id), next);
    }
  };

  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    setUploading(true);
    setError(null);
    try {
      const item = await api.storage.uploadFile(file);
      await persistMedia([...form.media, item]);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setUploading(false);
    }
  };

  const handleRemoveMedia = async (item: MediaItem) => {
    setError(null);
    try {
      await api.storage.deleteObject(item.key);
      await persistMedia(form.media.filter(m => m.key !== item.key));
    } catch (err) {
      setError((err as Error).message);
    }
  };

  if (loading) return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;

  return (
    <div className="page">
      <h1>{isEdit ? 'Edit Product' : 'New Product'}</h1>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, maxWidth: 640 }}>
        <input placeholder="Name *" required value={form.name}
          onChange={e => setForm({ ...form, name: e.target.value })} style={{ gridColumn: '1 / -1' }} />
        <textarea placeholder="Description" value={form.description}
          onChange={e => setForm({ ...form, description: e.target.value })} style={{ gridColumn: '1 / -1' }} rows={3} />
        <input type="number" step="0.01" placeholder="Price *" required value={form.price}
          onChange={e => setForm({ ...form, price: Number(e.target.value) })} />
        <input type="number" placeholder="Stock *" required value={form.stock}
          onChange={e => setForm({ ...form, stock: Number(e.target.value) })} />
        <select value={form.categoryId} onChange={e => setForm({ ...form, categoryId: Number(e.target.value) })}>
          <option value={0} disabled>Select category *</option>
          {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <input placeholder="Fallback image URL" value={form.imageUrl}
          onChange={e => setForm({ ...form, imageUrl: e.target.value })} />
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <button className="btn btn-primary" disabled={saving} onClick={handleSubmit}>
          {saving ? 'Saving...' : isEdit ? 'Save Changes' : 'Create Product'}
        </button>
        <Link to="/admin/products" className="btn">Cancel</Link>
      </div>

      {isEdit && (
        <section style={{ marginTop: 32, maxWidth: 640 }}>
          <h2>Media</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Uploaded images are stored separately from the fallback image URL above.
          </p>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, marginTop: 12 }}>
            {form.media.map(item => (
              <div key={item.key} style={{ width: 120 }}>
                <div style={{ width: 120, height: 120, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-secondary)', borderRadius: 6 }}>
                  <img src={item.url} alt="" style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
                </div>
                <button className="btn" style={{ width: '100%', marginTop: 4, color: 'var(--danger)' }}
                  onClick={() => handleRemoveMedia(item)}>
                  Remove
                </button>
              </div>
            ))}
          </div>

          <div style={{ marginTop: 16 }}>
            <input type="file" accept="image/jpeg,image/png,image/webp"
              disabled={uploading} onChange={handleFileSelected} />
            {uploading && <span style={{ marginLeft: 8, color: 'var(--text-secondary)' }}>Uploading...</span>}
          </div>
        </section>
      )}
    </div>
  );
}
