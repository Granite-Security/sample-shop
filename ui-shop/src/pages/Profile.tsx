import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { api } from '../api';
import Avatar from '../components/Avatar';
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
          <AvatarPicker profile={profile} onChange={setProfile} />

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

function AvatarPicker({ profile, onChange }: {
  profile: ProfileResponse;
  onChange: (profile: ProfileResponse) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const displayName = profile.displayName || profile.username;
  const hasGoogle = profile.googlePictureUrl !== null;
  const hasUpload = profile.uploadedAvatarUrl !== null;

  async function run(action: () => Promise<ProfileResponse>) {
    setBusy(true);
    setError(null);
    try {
      onChange(await action());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (file) run(() => api.profile.uploadAvatar(file));
  }

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 16, marginBottom: 24,
      paddingBottom: 24, borderBottom: '1px solid var(--border)', flexWrap: 'wrap',
    }}>
      <Avatar src={profile.avatarUrl} name={displayName} size={72} />

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn" disabled={busy} onClick={() => fileInput.current?.click()}>
            {busy ? 'Working…' : hasUpload ? 'Replace picture' : 'Upload a picture'}
          </button>

          {/* Only offered when there is something to switch to, so the buttons
              never lead to a 400 the user can't act on. */}
          {hasGoogle && profile.avatarSource !== 'GOOGLE' && (
            <button className="btn" disabled={busy}
              onClick={() => run(() => api.profile.setAvatarSource('GOOGLE'))}>
              Use my Google picture
            </button>
          )}
          {hasUpload && profile.avatarSource !== 'UPLOAD' && (
            <button className="btn" disabled={busy}
              onClick={() => run(() => api.profile.setAvatarSource('UPLOAD'))}>
              Use my uploaded picture
            </button>
          )}
          {hasUpload && (
            <button className="btn" disabled={busy}
              onClick={() => run(() => api.profile.removeAvatar())}>
              Remove upload
            </button>
          )}
          {profile.avatarSource !== 'NONE' && (
            <button className="btn" disabled={busy}
              onClick={() => run(() => api.profile.setAvatarSource('NONE'))}>
              Show initials
            </button>
          )}
        </div>

        <p style={{ margin: 0, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          {profile.avatarSource === 'GOOGLE' && 'Showing your Google picture.'}
          {profile.avatarSource === 'UPLOAD' && 'Showing the picture you uploaded.'}
          {profile.avatarSource === 'NONE' && 'Showing your initials.'}
          {' '}JPEG, PNG or WebP — cropped to a square and resized before upload.
        </p>

        {error && <p style={{ margin: 0, color: 'var(--danger)', fontSize: '0.85rem' }}>{error}</p>}
      </div>

      <input
        ref={fileInput}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={onFileSelected}
        style={{ display: 'none' }}
      />
    </div>
  );
}
