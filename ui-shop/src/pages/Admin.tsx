import { useAuth } from '../auth';

export default function Admin() {
  const { user, isAdmin } = useAuth();

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
      <h1>Admin Panel</h1>
      <p>Welcome, {user?.name}.</p>

      <section style={{ marginTop: 24 }}>
        <h2>Dashboard</h2>
        <div style={{ display: 'flex', gap: 16, marginTop: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 180, padding: 16, border: '1px solid var(--border)', borderRadius: 8 }}>
            <h3>Users</h3>
            <p style={{ fontSize: '2rem', fontWeight: 700 }}>—</p>
            <p style={{ color: 'var(--text-secondary)' }}>Total registered users</p>
          </div>
          <div style={{ flex: 1, minWidth: 180, padding: 16, border: '1px solid var(--border)', borderRadius: 8 }}>
            <h3>Orders</h3>
            <p style={{ fontSize: '2rem', fontWeight: 700 }}>—</p>
            <p style={{ color: 'var(--text-secondary)' }}>Total orders placed</p>
          </div>
          <div style={{ flex: 1, minWidth: 180, padding: 16, border: '1px solid var(--border)', borderRadius: 8 }}>
            <h3>Revenue</h3>
            <p style={{ fontSize: '2rem', fontWeight: 700 }}>$—</p>
            <p style={{ color: 'var(--text-secondary)' }}>Total revenue</p>
          </div>
        </div>
      </section>

      <section style={{ marginTop: 24 }}>
        <h2>Manage</h2>
        <div style={{ display: 'flex', gap: 12, marginTop: 12 }}>
          <span className="status status-paid">Products</span>
          <span className="status status-paid">Categories</span>
          <span className="status status-paid">Orders</span>
          <span className="status status-pending">Users (coming soon)</span>
        </div>
      </section>
    </div>
  );
}
