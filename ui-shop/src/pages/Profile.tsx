import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { ProfileResponse, UserFile } from '../types';

const ALLOWED_FILE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf', 'text/plain'];
const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

function formatSize(bytes: number | null): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function Profile() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');

  const [files, setFiles] = useState<UserFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login', { replace: true });
      return;
    }
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

    api.profile.getFiles()
      .then(setFiles)
      .catch(() => {})
      .finally(() => setFilesLoading(false));
  }, [isAuthenticated, navigate]);

  const handleSave = async () => {
    await api.profile.updateProfile({ firstName, lastName, email, displayName });
    const updated = await api.profile.getProfile();
    setProfile(updated);
    setEditing(false);
  };

  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (!file) return;

    setFileError(null);
    if (!ALLOWED_FILE_TYPES.includes(file.type)) {
      setFileError(`Unsupported file type: ${file.type || 'unknown'}`);
      return;
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      setFileError('File exceeds the 10 MB limit');
      return;
    }

    setUploading(true);
    try {
      const uploaded = await api.profile.uploadFile(file);
      setFiles(prev => [uploaded, ...prev]);
    } catch (err) {
      setFileError(err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const handleDeleteFile = async (id: number) => {
    await api.profile.deleteFile(id);
    setFiles(prev => prev.filter(f => f.id !== id));
  };

  const handleCopyLink = (url: string) => {
    navigator.clipboard?.writeText(url).catch(() => {});
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

          <hr style={{ margin: '24px 0', border: 'none', borderTop: '1px solid var(--border)' }} />

          <h2>Files</h2>
          <p style={{ fontSize: 14, color: 'var(--muted, #666)' }}>
            Uploaded files are public — anyone with the link can open them. Don't upload sensitive documents here.
          </p>

          <input
            ref={fileInputRef}
            type="file"
            accept={ALLOWED_FILE_TYPES.join(',')}
            onChange={handleFileSelected}
            disabled={uploading}
            style={{ marginTop: 8 }}
          />
          {uploading && <p>Uploading…</p>}
          {fileError && <p style={{ color: 'red' }}>{fileError}</p>}

          {filesLoading ? (
            <p>Loading files…</p>
          ) : files.length === 0 ? (
            <p>No files uploaded yet.</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
              {files.map(file => (
                <div key={file.id} style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: 8, border: '1px solid var(--border)', borderRadius: 4,
                }}>
                  <div>
                    <a href={file.url} target="_blank" rel="noreferrer">{file.fileName}</a>
                    <div style={{ fontSize: 12, color: 'var(--muted, #666)' }}>
                      {formatSize(file.sizeBytes)} · {new Date(file.createdAt).toLocaleDateString()}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button className="btn" onClick={() => handleCopyLink(file.url)}>Copy link</button>
                    <button className="btn" onClick={() => handleDeleteFile(file.id)}>Delete</button>
                  </div>
                </div>
              ))}
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
