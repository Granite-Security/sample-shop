import { useEffect, useRef, useState } from 'react';
import { api, DuplicateFileError } from '../api';
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

export function FilesPage() {
  const [files, setFiles] = useState<UserFile[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  const [duplicateFile, setDuplicateFile] = useState<UserFile | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api
      .getFiles()
      .then(setFiles)
      .catch(() => {})
      .finally(() => setLoading(false));
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
      const uploaded = await api.uploadFile(file);
      setFiles((prev) => [uploaded, ...prev]);
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
    await api.deleteFile(id);
    setFiles((prev) => prev.filter((f) => f.id !== id));
  };

  const handleCopyLink = (url: string) => {
    navigator.clipboard?.writeText(url).catch(() => {});
  };

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-3xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Files</h1>

        <section aria-label="Files" className="mt-10">
          <p className="text-sm text-cocoa/60">
            Uploaded files are public — anyone with the link can open them. Don't upload sensitive documents here.
          </p>

          <input
            ref={fileInputRef}
            type="file"
            accept={ALLOWED_FILE_TYPES.join(',')}
            onChange={handleFileSelected}
            disabled={uploading}
            className="mt-4 text-sm text-cocoa/70"
          />
          {uploading && <p className="mt-2 text-sm text-cocoa/60">Uploading…</p>}
          {fileError && (
            <p className="mt-2 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
              {fileError}
            </p>
          )}
          {duplicateFile && (
            <p className="mt-2 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
              You've already uploaded this file as{' '}
              <a href={duplicateFile.url} target="_blank" rel="noreferrer" className="underline">
                {duplicateFile.fileName}
              </a>{' '}
              on {new Date(duplicateFile.createdAt).toLocaleDateString()}.
            </p>
          )}

          {loading ? (
            <p className="mt-4 text-sm text-cocoa/50">Loading files…</p>
          ) : files.length === 0 ? (
            <p className="mt-4 text-sm text-cocoa/50">No files uploaded yet.</p>
          ) : (
            <ul className="mt-4 divide-y divide-cocoa/10 border-y border-cocoa/10">
              {files.map((file) => (
                <li key={file.id} className="flex items-center justify-between gap-4 py-4">
                  <div className="min-w-0">
                    <a href={file.url} target="_blank" rel="noreferrer" className="text-cocoa hover:text-terracotta">
                      {file.fileName}
                    </a>
                    <p className="mt-1 text-sm text-cocoa/50">
                      {formatSize(file.sizeBytes)} · {new Date(file.createdAt).toLocaleDateString()}
                    </p>
                  </div>
                  <div className="flex shrink-0 gap-4">
                    <button
                      onClick={() => handleCopyLink(file.url)}
                      className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                    >
                      Copy link
                    </button>
                    <button
                      onClick={() => handleDeleteFile(file.id)}
                      className="text-xs uppercase tracking-[0.14em] text-terracotta/80 underline underline-offset-4 hover:text-terracotta"
                    >
                      Delete
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
