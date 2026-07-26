import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { AdminUserProfile } from '../types';

export default function UsersManagement() {
  const { isAdmin } = useAuth();
  const [profiles, setProfiles] = useState<AdminUserProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAdmin) return;
    api.profile.getProfiles()
      .then(setProfiles)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [isAdmin]);

  if (!isAdmin) {
    return (
      <div className="page">
        <h1>Access Denied</h1>
        <p>You do not have admin privileges.</p>
      </div>
    );
  }

  return (
    <div className="page">
      <h1>User Management</h1>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      {loading ? (
        <p>Loading users...</p>
      ) : profiles.length === 0 ? (
        <p>No users found.</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {profiles.map(p => (
            <div key={p.id} style={{
              padding: 16, background: 'var(--surface)', borderRadius: 8,
              border: '1px solid var(--border)',
            }}>
              <div>
                <strong>{p.username}</strong>
                <span style={{ marginLeft: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  {p.firstName} {p.lastName}
                </span>
              </div>
              <p style={{ margin: '8px 0 4px', fontSize: '0.9rem' }}>{p.email}</p>
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                <Link to={`/admin/users/${p.username}`} className="btn">View profile</Link>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
