import type { PaymentProviderInfo } from '../../types';

/**
 * Lets the shopper pick who takes their money.
 *
 * Renders nothing below two providers: with a single provider the choice is not a
 * choice, and the backend fills it in. So today this is invisible, and it appears
 * the moment a second adapter is enabled — without this page changing.
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
    <fieldset className="mb-6 border-0 p-0">
      <legend className="mb-3 text-xs uppercase tracking-[0.2em] text-cocoa/60">Payment method</legend>
      {providers.map((provider) => (
        <label key={provider.id} className="flex cursor-pointer items-center gap-3 py-2 text-sm text-cocoa">
          <input
            type="radio"
            name="payment-provider"
            value={provider.id}
            checked={selected === provider.id}
            disabled={disabled}
            onChange={() => onSelect(provider.id)}
            className="accent-cocoa"
          />
          <span>{provider.displayName}</span>
        </label>
      ))}
    </fieldset>
  );
}
