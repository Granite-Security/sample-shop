import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { api } from '../api';
import { useAuth } from '../auth';
import type { AdminUserProfile } from '../types';
import { Avatar } from '../components/Avatar';

export function UserProfileViewPage() {
  const { isAdmin, loading: authLoading } = useAuth();
  const { username } = useParams<{ username: string }>();
  const [profile, setProfile] = useState<AdminUserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAdmin || !username) return;
    let cancelled = false;
    api.getProfileByUsername(username)
      .then((p) => { if (!cancelled) setProfile(p); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isAdmin, username]);

  if (authLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-ivory pt-28 text-cocoa/50">
        Loading…
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-ivory px-6 pt-28 text-center">
        <h1 className="font-display text-[32px] text-cocoa">The Back of House is Locked</h1>
        <p className="max-w-md text-cocoa/60">
          This area is reserved for SI Chocolate staff. Sign in with an admin account to view
          customer profiles.
        </p>
        <Link
          to="/"
          className="mt-4 border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
        >
          Back to the Boutique
        </Link>
      </div>
    );
  }

  return (
    <div className="bg-ivory pt-28 lg:pt-32">
      <div className="mx-auto max-w-5xl px-5 pb-24 lg:px-8">
        <p className="text-xs uppercase tracking-[0.3em] text-terracotta">Back of House</p>
        <h1 className="mt-3 font-display text-[36px] leading-tight text-cocoa lg:text-[48px]">
          Customer Profile
        </h1>

        {error && (
          <p role="status" className="mt-6 border-l-2 border-terracotta bg-terracotta/10 px-4 py-3 text-sm text-terracotta">
            {error}
          </p>
        )}

        {loading ? (
          <p className="mt-10 text-sm text-cocoa/50">Loading profile…</p>
        ) : profile && (
          <>
          <div className="mt-10 flex items-center gap-4">
            <Avatar src={profile.avatarUrl} name={profile.username} size={64} ring />
            <span className="font-display text-[22px] text-cocoa">
              {profile.firstName} {profile.lastName}
            </span>
          </div>

          <dl className="mt-8 max-w-md divide-y divide-cocoa/10 border-y border-cocoa/10">
            <ProfileRow label="Username" value={profile.username} />
            <ProfileRow label="Name" value={`${profile.firstName} ${profile.lastName}`} />
            <ProfileRow label="Email" value={profile.email} />
            <ProfileRow label="Member since" value={new Date(profile.createdAt).toLocaleDateString()} />
          </dl>
          </>
        )}

        <div className="mt-10">
          <Link
            to="/admin/users"
            className="border border-cocoa px-8 py-3 text-xs uppercase tracking-[0.18em] text-cocoa transition-colors duration-300 hover:bg-cocoa hover:text-ivory"
          >
            Back to Customer Accounts
          </Link>
        </div>
      </div>
    </div>
  );
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="py-4">
      <dt className="text-xs uppercase tracking-[0.16em] text-cocoa/50">{label}</dt>
      <dd className="mt-1 text-sm text-cocoa">{value}</dd>
    </div>
  );
}
