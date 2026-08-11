import type { PackagingQuote } from '../../types';

interface Props {
  quote: PackagingQuote | null;
  loading: boolean;
  error: string;
  /** groupId → chosen optionId. */
  selection: Record<number, number>;
  onSelect: (groupId: number, optionId: number) => void;
  disabled?: boolean;
}

/**
 * The box choice, between the cart and the address.
 *
 * Renders nothing at all when the cart contains nothing that needs packaging —
 * most carts. Every price shown here comes from the server's quote; this
 * component does no arithmetic of its own, so what the shopper reads is what
 * checkout will charge.
 */
export default function PackagingPicker({
  quote, loading, error, selection, onSelect, disabled,
}: Props) {
  if (loading) {
    return (
      <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
        Checking packaging…
      </p>
    );
  }

  if (error) {
    return <p className="error">{error}</p>;
  }

  if (!quote?.packagingRequired) return null;

  return (
    <>
      <h2>Packaging</h2>
      {quote.groups.map(group => (
        <div key={group.groupId} style={{ marginBottom: 16 }}>
          {/* Labelled by group only when there is more than one to tell apart —
              a single-group cart is the common case and needs no heading. */}
          {quote.groups.length > 1 && (
            <p style={{ margin: '0 0 6px', fontWeight: 600 }}>
              {group.name}
              <span style={{ fontWeight: 400, color: 'var(--text-secondary)', marginLeft: 6 }}>
                ({group.units} {group.units === 1 ? 'piece' : 'pieces'})
              </span>
            </p>
          )}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {group.options.map(option => {
              const checked = selection[group.groupId] === option.optionId;
              return (
                <label
                  key={option.optionId}
                  style={{
                    display: 'flex', alignItems: 'flex-start', gap: 8, padding: 8,
                    border: `1px solid ${checked ? 'var(--primary)' : 'var(--border)'}`,
                    borderRadius: 6,
                    cursor: disabled ? 'default' : 'pointer',
                    background: checked ? 'var(--surface-hover)' : 'transparent',
                  }}
                >
                  <input
                    type="radio"
                    name={`packaging-${group.groupId}`}
                    checked={checked}
                    disabled={disabled}
                    onChange={() => onSelect(group.groupId, option.optionId)}
                    style={{ marginTop: 3 }}
                  />
                  <div style={{ flex: 1 }}>
                    <strong>{option.name}</strong>
                    <span style={{ float: 'right', fontWeight: 600 }}>
                      {/* A price of zero reads as "included", not as "0.00" — the box
                          exists and is being given, which "free" says and a number does not. */}
                      {option.total > 0
                        ? `${quote.currency} ${option.total.toFixed(2)}`
                        : 'Included'}
                    </span>
                    {option.description && (
                      <p style={{ margin: '2px 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                        {option.description}
                      </p>
                    )}
                    <p style={{ margin: '2px 0', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      {option.packages} {option.packages === 1 ? 'box' : 'boxes'}
                      {option.total > 0 && ` × ${quote.currency} ${option.unitPrice.toFixed(2)}`}
                      {' · '}holds {option.capacity}
                    </p>
                  </div>
                </label>
              );
            })}
          </div>
        </div>
      ))}
    </>
  );
}
