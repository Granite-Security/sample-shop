import { useState } from 'react';
import { api } from '../api';
import { ApiError } from '../api/client';

// Split out of Profile.tsx. Note: unlike ProfileDetails, this page has no
// `profile` object to check `profile?.email` against, so the success message
// is phrased unconditionally rather than pulling in a whole getProfile() call
// for one sentence.
export default function Password() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSuccess, setPasswordSuccess] = useState<string | null>(null);

  const handleChangePassword = async () => {
    setPasswordError(null);
    setPasswordSuccess(null);

    if (newPassword.length < 8) {
      setPasswordError('New password must be at least 8 characters');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('New password and confirmation do not match');
      return;
    }

    setChangingPassword(true);
    try {
      await api.account.changePassword({ currentPassword, newPassword });
      setPasswordSuccess('Password changed. If your account has an email address, a confirmation was sent.');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      // The backend's ProblemDetail "detail" is already a friendly message
      // (e.g. "This account signs in with Google; there is no password to
      // change.") — just surface it directly rather than a generic error.
      if (err instanceof ApiError) {
        const detail = (err.data as { detail?: string } | undefined)?.detail;
        setPasswordError(detail ?? err.message);
      } else {
        setPasswordError('Failed to change password');
      }
    } finally {
      setChangingPassword(false);
    }
  };

  return (
    <div className="page" style={{ maxWidth: 600 }}>
      <h1>Password</h1>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 320, marginTop: 16 }}>
        <input
          type="password"
          value={currentPassword}
          onChange={e => setCurrentPassword(e.target.value)}
          placeholder="Current Password"
          autoComplete="current-password"
        />
        <input
          type="password"
          value={newPassword}
          onChange={e => setNewPassword(e.target.value)}
          placeholder="New Password"
          autoComplete="new-password"
        />
        <input
          type="password"
          value={confirmPassword}
          onChange={e => setConfirmPassword(e.target.value)}
          placeholder="Confirm New Password"
          autoComplete="new-password"
        />
        <button className="btn btn-primary" onClick={handleChangePassword} disabled={changingPassword}>
          {changingPassword ? 'Changing…' : 'Change Password'}
        </button>
        {passwordError && <p style={{ color: 'red' }}>{passwordError}</p>}
        {passwordSuccess && <p style={{ color: 'green' }}>{passwordSuccess}</p>}
      </div>
    </div>
  );
}
