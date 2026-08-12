import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { api } from '../api';
import { ApiError } from '../api/client';
import Avatar from '../components/Avatar';
import type { HandleAvailability, ProfileResponse } from '../types';

export default function Profile() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [bio, setBio] = useState('');

  useEffect(() => {
    api.profile.getProfile()
      .then(p => {
        setProfile(p);
        setFirstName(p.firstName ?? '');
        setLastName(p.lastName ?? '');
        setEmail(p.email ?? '');
        setDisplayName(p.displayName ?? '');
        setBio(p.bio ?? '');
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    // bio goes with the rest: PUT /api/profiles/me overwrites every field it is
    // given, so omitting it here would blank it (docs/profile/public-profile.md D5).
    await api.profile.updateProfile({ firstName, lastName, email, displayName, bio });
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

          <PublicProfilePanel profile={profile} onChange={setProfile} />

          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <input value={firstName} onChange={e => setFirstName(e.target.value)} placeholder="First Name" />
              <input value={lastName} onChange={e => setLastName(e.target.value)} placeholder="Last Name" />
              <input value={email} onChange={e => setEmail(e.target.value)} placeholder="Email" />
              <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="Display Name" />
              <div>
                <textarea
                  value={bio}
                  onChange={e => setBio(e.target.value.slice(0, 500))}
                  rows={4}
                  placeholder="Short bio, shown on your public profile"
                  style={{ width: '100%' }}
                />
                <div style={{ fontSize: 12, opacity: 0.6, textAlign: 'right' }}>{bio.length}/500</div>
              </div>
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
              <p><strong>Bio:</strong> {profile.bio || '—'}</p>
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

/**
 * Handle and visibility (docs/profile/public-profile.md step 8).
 *
 * Separate from the details form above because both calls have their own
 * endpoints and their own failure modes — the handle can 409, and publishing is
 * a switch, not a text field.
 */
function PublicProfilePanel({ profile, onChange }: {
  profile: ProfileResponse;
  onChange: (profile: ProfileResponse) => void;
}) {
  const [handle, setHandle] = useState(profile.handle ?? '');
  const [availability, setAvailability] = useState<HandleAvailability | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const dirty = handle.trim().toLowerCase() !== (profile.handle ?? '');

  // Debounced so typing a handle does not fire a request per keystroke.
  useEffect(() => {
    if (!dirty || !handle.trim()) {
      setAvailability(null);
      return;
    }
    const timer = setTimeout(() => {
      api.profile.checkHandle(handle.trim().toLowerCase())
        .then(setAvailability)
        .catch(() => setAvailability(null));
    }, 400);
    return () => clearTimeout(timer);
  }, [handle, dirty]);

  const saveHandle = async () => {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.profile.setHandle(handle.trim().toLowerCase()));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save the handle');
    } finally {
      setBusy(false);
    }
  };

  const toggleVisibility = async () => {
    setBusy(true);
    setError(null);
    try {
      onChange(await api.profile.setVisibility(!profile.publicProfile));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not change visibility');
    } finally {
      setBusy(false);
    }
  };

  const url = profile.handle ? `${window.location.origin}/users/${profile.handle}` : null;

  return (
    <div style={{ marginTop: 24, paddingTop: 16, borderTop: '1px solid rgba(128,128,128,0.3)' }}>
      <h2 style={{ fontSize: 18 }}>Public profile</h2>
      <p style={{ fontSize: 13, opacity: 0.7 }}>
        Pick a handle, then choose whether anyone can see your profile. Your handle stays
        yours even while your profile is private.
      </p>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 }}>
        <span style={{ opacity: 0.6 }}>/users/</span>
        <input
          value={handle}
          onChange={e => setHandle(e.target.value.toLowerCase())}
          placeholder="your-handle"
          maxLength={32}
        />
        <button className="btn" disabled={busy || !dirty || !handle.trim()} onClick={saveHandle}>
          Save handle
        </button>
      </div>

      {dirty && availability && (
        <p style={{ fontSize: 13, marginTop: 4, opacity: 0.8 }}>
          {availability.available ? `${availability.handle} is available` : availability.reason}
        </p>
      )}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 12 }}>
        <button
          className={profile.publicProfile ? 'btn' : 'btn btn-primary'}
          disabled={busy || !profile.handle}
          onClick={toggleVisibility}
          title={profile.handle ? undefined : 'Choose a handle first'}
        >
          {profile.publicProfile ? 'Make my profile private' : 'Make my profile public'}
        </button>
        {!profile.handle && (
          <span style={{ fontSize: 13, opacity: 0.7 }}>Choose a handle first.</span>
        )}
      </div>

      {profile.publicProfile && url && (
        <div style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <code style={{ fontSize: 13 }}>{url}</code>
          <button
            className="btn"
            onClick={() => {
              navigator.clipboard.writeText(url);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            }}
          >
            {copied ? 'Copied' : 'Copy link'}
          </button>
          <a className="btn" href={`/users/${profile.handle}`} target="_blank" rel="noreferrer">
            View as visitor
          </a>
        </div>
      )}

      {error && <p className="error" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}
