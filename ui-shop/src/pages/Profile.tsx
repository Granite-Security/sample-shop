import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { ProfileResponse } from '../types';

export default function Profile() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');

  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login', { replace: true });
      return;
    }
    api.getProfile()
      .then(p => {
        setProfile(p);
        setFirstName(p.firstName ?? '');
        setLastName(p.lastName ?? '');
        setEmail(p.email ?? '');
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [isAuthenticated, navigate]);

  const handleSave = async () => {
    await api.updateProfile({ firstName, lastName, email });
    const updated = await api.getProfile();
    setProfile(updated);
    setEditing(false);
  };

  if (loading) return <div className="page"><div className="spinner" style={{ margin: '0 auto' }} /></div>;

  return (
    <div className="page" style={{ maxWidth: 600 }}>
      <h1>My Profile</h1>

      {profile ? (
        <div style={{ marginTop: 16 }}>
          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <input value={firstName} onChange={e => setFirstName(e.target.value)} placeholder="First Name" />
              <input value={lastName} onChange={e => setLastName(e.target.value)} placeholder="Last Name" />
              <input value={email} onChange={e => setEmail(e.target.value)} placeholder="Email" />
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn btn-primary" onClick={handleSave}>Save</button>
                <button className="btn" onClick={() => setEditing(false)}>Cancel</button>
              </div>
            </div>
          ) : (
            <div>
              <p><strong>Username:</strong> {profile.username}</p>
              <p><strong>Name:</strong> {[profile.firstName, profile.lastName].filter(Boolean).join(' ') || '—'}</p>
              <p><strong>Email:</strong> {profile.email || '—'}</p>
              <button className="btn" style={{ marginTop: 8 }} onClick={() => setEditing(true)}>Edit Profile</button>
            </div>
          )}

          <hr style={{ margin: '24px 0', border: 'none', borderTop: '1px solid var(--border)' }} />

          <h2>Quick Links</h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
            <Link to="/orders" className="btn" style={{ textAlign: 'center' }}>My Orders</Link>
            <Link to="/addresses" className="btn" style={{ textAlign: 'center' }}>My Addresses</Link>
          </div>
        </div>
      ) : (
        <p>Could not load profile.</p>
      )}
    </div>
  );
}
