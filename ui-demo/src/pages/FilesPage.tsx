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

  const handleShare = async (file: UserFile) => {
    const updated = await api.setFileShared(file.id, !file.shared);
    setFiles((current) => current.map((f) => (f.id === updated.id ? updated : f)));
  };

  const handleDeleteFile = async (id: number) => {
    await api.deleteFile(id);
    setFiles((prev) => prev.filter((f) => f.id !== id));
  };

  const handleCopyLink = (url: string) => {
    navigator.clipboard?.writeText(url).catch(() => {});
  };

  return (
    <div>
      <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Your Account</p>
      <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">Files</h1>

      <section aria-label="Files" className="mt-10">
        <p className="text-sm text-cocoa/60">
          Uploaded files are public — anyone with the link can open them. Don't upload sensitive documents here.
        </p>

        <div className="mt-4 flex flex-wrap items-center gap-3">
          {/* Hidden but focusable, so the label beside it acts as a real button
              for mouse and keyboard alike. peer-* needs them to stay siblings. */}
          <input
            id="file-upload"
            ref={fileInputRef}
            type="file"
            accept={ALLOWED_FILE_TYPES.join(',')}
            onChange={handleFileSelected}
            disabled={uploading}
            className="peer sr-only"
          />
          <label
            htmlFor="file-upload"
            className={`inline-flex cursor-pointer items-center gap-2 border border-cocoa/30 bg-ivory px-6 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:border-cocoa hover:bg-cocoa hover:text-ivory peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-gold ${
              uploading ? 'pointer-events-none opacity-40' : ''
            }`}
          >
            {uploading ? (
              <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" className="opacity-25" />
                <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
            ) : (
              <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M4 16v2.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V16"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
            {uploading ? 'Uploading…' : 'Choose a file'}
          </label>
          <span className="text-[11px] uppercase tracking-wide text-cocoa/40">
            JPG, PNG, WEBP, PDF or TXT
          </span>
        </div>
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
                    {file.shared && ' · on your public profile'}
                  </p>
                </div>
                <div className="flex shrink-0 gap-4">
                  <button
                    onClick={() => handleShare(file)}
                    className="text-xs uppercase tracking-[0.14em] text-cocoa underline decoration-gold underline-offset-4 hover:text-terracotta"
                  >
                    {file.shared ? 'Remove from profile' : 'Publish to profile'}
                  </button>
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
  );
}
