import { useEffect, useState } from 'react';
import { api } from '../api';
import type { AddressResponse, AddressRequest } from '../types';

export default function Addresses() {
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<AddressResponse | null>(null);
  const [form, setForm] = useState<AddressRequest>({
    recipientName: '',
    addressLine1: '',
    addressLine2: '',
    city: '',
    state: '',
    zipCode: '',
    country: '',
    label: '',
    isDefault: false,
  });

  const load = () => {
    api.profile.getAddresses()
      .then(setAddresses)
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const resetForm = () => {
    setForm({ recipientName: '', addressLine1: '', addressLine2: '', city: '', state: '', zipCode: '', country: '', label: '', isDefault: false });
    setEditing(null);
    setShowForm(false);
  };

  const handleSubmit = async () => {
    if (editing) {
      await api.profile.updateAddress(editing.id, form);
    } else {
      await api.profile.createAddress(form);
    }
    resetForm();
    load();
  };

  const handleEdit = (addr: AddressResponse) => {
    setForm({
      recipientName: addr.recipientName,
      addressLine1: addr.addressLine1,
      addressLine2: addr.addressLine2 ?? '',
      city: addr.city,
      state: addr.state ?? '',
      zipCode: addr.zipCode,
      country: addr.country,
      label: addr.label ?? '',
      isDefault: addr.isDefault,
    });
    setEditing(addr);
    setShowForm(true);
  };

  const handleDelete = async (id: number) => {
    await api.profile.deleteAddress(id);
    load();
  };

  if (loading) return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>My Addresses</h1>
        <button className="btn btn-primary" onClick={() => { resetForm(); setShowForm(true); }}>
          Add Address
        </button>
      </div>

      {showForm && (
        <div className="address-form" style={{ border: '1px solid var(--border)', padding: 16, borderRadius: 8, marginBottom: 16 }}>
          <h3>{editing ? 'Edit Address' : 'New Address'}</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <input placeholder="Label (e.g. Home)" value={form.label ?? ''} onChange={e => setForm({ ...form, label: e.target.value })} />
            <input placeholder="Recipient Name *" required value={form.recipientName} onChange={e => setForm({ ...form, recipientName: e.target.value })} />
            <input placeholder="Address Line 1 *" required value={form.addressLine1} onChange={e => setForm({ ...form, addressLine1: e.target.value })} style={{ gridColumn: '1 / -1' }} />
            <input placeholder="Address Line 2" value={form.addressLine2 ?? ''} onChange={e => setForm({ ...form, addressLine2: e.target.value })} style={{ gridColumn: '1 / -1' }} />
            <input placeholder="City *" required value={form.city} onChange={e => setForm({ ...form, city: e.target.value })} />
            <input placeholder="State" value={form.state ?? ''} onChange={e => setForm({ ...form, state: e.target.value })} />
            <input placeholder="ZIP Code *" required value={form.zipCode} onChange={e => setForm({ ...form, zipCode: e.target.value })} />
            <input placeholder="Country *" required value={form.country} onChange={e => setForm({ ...form, country: e.target.value })} />
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
            <input type="checkbox" checked={form.isDefault ?? false} onChange={e => setForm({ ...form, isDefault: e.target.checked })} />
            Set as default address
          </label>
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <button className="btn btn-primary" onClick={handleSubmit}>
              {editing ? 'Save' : 'Add'}
            </button>
            <button className="btn" onClick={resetForm}>Cancel</button>
          </div>
        </div>
      )}

      {addresses.length === 0 ? (
        <p>No saved addresses yet.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {addresses.map(addr => (
            <div key={addr.id} className="address-card" style={{ border: '1px solid var(--border)', padding: 12, borderRadius: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                {addr.label && <strong>{addr.label}</strong>}
                {addr.isDefault && <span className="status status-paid" style={{ marginLeft: 8, fontSize: '0.75rem' }}>Default</span>}
                <p style={{ margin: '4px 0' }}>{addr.recipientName}</p>
                <p style={{ margin: '2px 0', color: 'var(--text-secondary)' }}>
                  {addr.addressLine1}{addr.addressLine2 ? `, ${addr.addressLine2}` : ''}
                </p>
                <p style={{ margin: '2px 0', color: 'var(--text-secondary)' }}>
                  {addr.city}{addr.state ? `, ${addr.state}` : ''} {addr.zipCode}, {addr.country}
                </p>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn" onClick={() => handleEdit(addr)}>Edit</button>
                <button className="btn" style={{ color: 'var(--danger)' }} onClick={() => handleDelete(addr.id)}>Delete</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
