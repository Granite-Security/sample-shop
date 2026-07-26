import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useAuth } from '../auth';
import { api } from '../api';
import type { AdminUserProfile } from '../types';

export default function UserProfileView() {
  const { isAdmin } = useAuth();
  const { username } = useParams<{ username: string }>();
  const [profile, setProfile] = useState<AdminUserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAdmin || !username) return;
    api.profile.getProfileByUsername(username)
      .then(setProfile)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [isAdmin, username]);

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
      <h1>User Profile</h1>

      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}

      {loading ? (
        <p>Loading profile...</p>
      ) : profile && (
        <div style={{
          padding: 16, background: 'var(--surface)', borderRadius: 8,
          border: '1px solid var(--border)', maxWidth: 480,
        }}>
          <dl style={{ margin: 0 }}>
            <dt style={{ fontWeight: 700, marginTop: 8 }}>Username</dt>
            <dd style={{ margin: '2px 0 0' }}>{profile.username}</dd>
            <dt style={{ fontWeight: 700, marginTop: 8 }}>Name</dt>
            <dd style={{ margin: '2px 0 0' }}>{profile.firstName} {profile.lastName}</dd>
            <dt style={{ fontWeight: 700, marginTop: 8 }}>Email</dt>
            <dd style={{ margin: '2px 0 0' }}>{profile.email}</dd>
            <dt style={{ fontWeight: 700, marginTop: 8 }}>Member since</dt>
            <dd style={{ margin: '2px 0 0' }}>{new Date(profile.createdAt).toLocaleDateString()}</dd>
          </dl>
        </div>
      )}

      <div style={{ marginTop: 16 }}>
        <Link to="/admin/users" className="btn">Back to Users</Link>
      </div>
    </div>
  );
}
