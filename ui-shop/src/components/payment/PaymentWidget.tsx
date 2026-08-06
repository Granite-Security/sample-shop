import type { ConfirmationMode, ProviderPayload } from '../../types';
import StripePaymentWidget from './stripe/StripePaymentWidget';
import RedirectPaymentWidget from './RedirectPaymentWidget';

/**
 * Chooses how to complete a payment.
 *
 * <p>The switch is on `confirmationMode`, not on provider id, which is what keeps the
 * frontend additive: most non-card providers are redirect-shaped and need only a
 * selector entry and a backend adapter. Only a genuinely new *shape* of confirmation
 * — a second in-page SDK — needs a component here.
 */
export default function PaymentWidget({
  provider,
  displayName,
  confirmationMode,
  payload,
  returnUrl,
  onPaymentConfirmed,
  onError,
}: {
  provider: string;
  displayName?: string;
  confirmationMode: ConfirmationMode;
  payload: ProviderPayload;
  /** Where a CLIENT_SDK provider returns to. Orders and top-ups differ only here. */
  returnUrl: string;
  onPaymentConfirmed: () => void;
  onError: (msg: string) => void;
}) {
  switch (confirmationMode) {
    case 'CLIENT_SDK':
      if (provider === 'stripe') {
        return (
          <StripePaymentWidget
            payload={payload}
            returnUrl={returnUrl}
            onPaymentConfirmed={onPaymentConfirmed}
            onError={onError}
          />
        );
      }
      break;
    case 'REDIRECT':
      return (
        <RedirectPaymentWidget
          payload={payload}
          displayName={displayName ?? provider}
          onError={onError}
        />
      );
  }

  // Rendered, not thrown: an unrecognised provider means the backend enabled one
  // this build does not know about. That is a deploy-skew bug, and throwing would
  // blank the checkout page instead of showing the shopper what went wrong.
  return (
    <p className="error">
      This build cannot complete payments with {displayName ?? provider}. Please choose
      another payment method.
    </p>
  );
}
