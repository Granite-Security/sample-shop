import type { PaymentProviderInfo } from '../../types';

/**
 * Lets the shopper pick who takes their money.
 *
 * <p>Renders nothing when fewer than two providers are enabled: with a single
 * provider the choice is not a choice, and the backend fills it in. So today this is
 * invisible, and it becomes visible the moment a second adapter is switched on —
 * without either page changing.
 */
export default function ProviderSelector({
  providers,
  selected,
  onSelect,
  disabled,
}: {
  providers: PaymentProviderInfo[];
  selected: string | null;
  onSelect: (id: string) => void;
  disabled?: boolean;
}) {
  if (providers.length < 2) return null;

  return (
    <fieldset className="provider-selector" style={{ border: 0, padding: 0, margin: '0 0 16px' }}>
      <legend style={{ fontWeight: 600, marginBottom: 8 }}>Payment method</legend>
      {providers.map(provider => (
        <label
          key={provider.id}
          style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0', cursor: 'pointer' }}
        >
          <input
            type="radio"
            name="payment-provider"
            value={provider.id}
            checked={selected === provider.id}
            disabled={disabled}
            onChange={() => onSelect(provider.id)}
          />
          <span>{provider.displayName}</span>
        </label>
      ))}
    </fieldset>
  );
}
