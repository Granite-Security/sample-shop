import { useEffect, useState } from 'react';
import { api } from '../api';
import type { ProfileResponse } from '../types';

export default function Profile() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');

  useEffect(() => {
    api.profile.getProfile()
      .then(p => {
        setProfile(p);
        setFirstName(p.firstName ?? '');
        setLastName(p.lastName ?? '');
        setEmail(p.email ?? '');
        setDisplayName(p.displayName ?? '');
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    await api.profile.updateProfile({ firstName, lastName, email, displayName });
    const updated = await api.profile.getProfile();
    setProfile(updated);
    setEditing(false);
  };

  if (loading) return <div className="spinner" style={{ margin: '0 auto' }} />;

  return (
    <div>
      <h1>My Profile</h1>

      {profile ? (
        <div style={{ marginTop: 16 }}>
          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <input value={firstName} onChange={e => setFirstName(e.target.value)} placeholder="First Name" />
              <input value={lastName} onChange={e => setLastName(e.target.value)} placeholder="Last Name" />
              <input value={email} onChange={e => setEmail(e.target.value)} placeholder="Email" />
              <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="Display Name" />
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="btn btn-primary" onClick={handleSave}>Save</button>
                <button className="btn" onClick={() => setEditing(false)}>Cancel</button>
              </div>
            </div>
          ) : (
            <div>
              <p><strong>Username:</strong> {profile.username}</p>
              <p><strong>Display Name:</strong> {profile.displayName || profile.username}</p>
              <p><strong>Name:</strong> {[profile.firstName, profile.lastName].filter(Boolean).join(' ') || '—'}</p>
              <p><strong>Email:</strong> {profile.email || '—'}</p>
              <button className="btn" style={{ marginTop: 8 }} onClick={() => setEditing(true)}>Edit Profile</button>
            </div>
          )}
        </div>
      ) : (
        <p>Could not load profile.</p>
      )}
    </div>
  );
}
