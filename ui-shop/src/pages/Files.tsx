import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { DuplicateFileError } from '../api/profile';
import type { UserFile } from '../types';

const ALLOWED_FILE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf', 'text/plain'];
// S3 (and Garage, which implements the same API) rejects a single PUT above
// 5 GiB — anything larger needs multipart upload, which this presign-based
// flow doesn't support. Stay comfortably under that hard ceiling, matching
// UserFileService's own limit on the backend.
const MAX_FILE_SIZE_BYTES = 5_000_000_000;

function formatSize(bytes: number | null): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

export default function Files() {
  const [files, setFiles] = useState<UserFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  const [duplicateFile, setDuplicateFile] = useState<UserFile | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.profile.getFiles()
      .then(setFiles)
      .catch(() => {})
      .finally(() => setFilesLoading(false));
  }, []);

  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (!file) return;

    setFileError(null);
    setDuplicateFile(null);
    if (!ALLOWED_FILE_TYPES.includes(file.type)) {
      setFileError(`Unsupported file type: ${file.type || 'unknown'}`);
      return;
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      setFileError('File exceeds the 5 GB limit');
      return;
    }

    setUploading(true);
    try {
      const uploaded = await api.profile.uploadFile(file);
      setFiles(prev => [uploaded, ...prev]);
    } catch (err) {
      if (err instanceof DuplicateFileError) {
        setDuplicateFile(err.existingFile);
      } else {
        setFileError(err instanceof Error ? err.message : 'Upload failed');
      }
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

  return (
    <div>
      <h1>Files</h1>
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
      {duplicateFile && (
        <p style={{ color: 'red' }}>
          You've already uploaded this file as{' '}
          <a href={duplicateFile.url} target="_blank" rel="noreferrer">{duplicateFile.fileName}</a>
          {' '}on {new Date(duplicateFile.createdAt).toLocaleDateString()}.
        </p>
      )}

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
    </div>
  );
}
