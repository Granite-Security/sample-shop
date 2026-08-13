import { useEffect, useState } from 'react';
import { api } from '../api';
import type { PackagingChoice, VoucherPreview } from '../types';

interface Props {
  items: { productId: number; quantity: number }[];
  /** The shopper's chosen boxes, so the preview prices the same cart placement will. */
  packaging: PackagingChoice[];
  /** Identity of the cart + boxes this preview would describe. */
  cartKey: string;
  disabled?: boolean;
  formatPrice: (value: number) => string;
  /** The applied voucher, or null when there is none. The parent sends its code on placement. */
  onChange: (voucher: VoucherPreview | null) => void;
}

const inputStyle =
  'w-full border border-cocoa/20 bg-white/70 px-4 py-3 text-sm text-cocoa placeholder:text-cocoa/40 focus:border-gold focus:outline-none';

/**
 * The voucher code box at checkout.
 *
 * The discount shown is always the server's number, never computed here — the same
 * rule the packaging quote follows. Applying a code stores and reserves nothing:
 * placement validates it again and can still refuse it.
 *
 * A code stays applied across cart edits and is silently re-priced, because the
 * discount is a percentage of a subtotal that just changed.
 */
export default function VoucherField({
  items, packaging, cartKey, disabled, formatPrice, onChange,
}: Props) {
  const [code, setCode] = useState('');
  const [applied, setApplied] = useState<VoucherPreview | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed) return;
    setBusy(true);
    setMessage('');
    try {
      const preview = await api.previewVoucher(trimmed, items, packaging);
      // A refused code is a 200 with valid:false — the answer, not an error.
      if (preview.valid) {
        setApplied(preview);
        onChange(preview);
      } else {
        setApplied(null);
        onChange(null);
        setMessage(preview.message || 'That code cannot be used.');
      }
    } catch (e: unknown) {
      setApplied(null);
      onChange(null);
      setMessage(e instanceof Error ? e.message : 'Could not check that code.');
    } finally {
      setBusy(false);
    }
  };

  // Re-priced whenever the cart or the chosen boxes change: the discount is a
  // percentage of a subtotal that just moved, and a stale number here is one the
  // shopper was never going to be charged.
  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    api.previewVoucher(applied.code, items, packaging)
      .then((preview) => {
        if (cancelled) return;
        if (preview.valid) {
          setApplied(preview);
          onChange(preview);
        } else {
          setApplied(null);
          onChange(null);
          setMessage(preview.message || 'That code no longer applies to this cart.');
        }
      })
      .catch(() => {
        // Leave it applied and let placement judge: dropping a valid discount because
        // one request failed is the worse mistake, and the server reprices anyway.
      });
    return () => { cancelled = true; };
    // Keyed on the cart, not on `applied` — re-running on its own result would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cartKey]);

  if (applied) {
    return (
      <p className="mt-4 flex justify-between text-sm text-cocoa/70">
        <span>
          Voucher {applied.code} ({applied.percentOff}%)
          <button
            type="button"
            onClick={() => { setApplied(null); setCode(''); setMessage(''); onChange(null); }}
            disabled={disabled}
            className="ml-2 underline underline-offset-2 hover:text-terracotta"
          >
            remove
          </button>
        </span>
        <span>−{formatPrice(applied.discountTotal)}</span>
      </p>
    );
  }

  return (
    <div className="mt-4">
      <div className="flex gap-2">
        <input
          aria-label="Voucher code"
          placeholder="Voucher code"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); submit(code); } }}
          disabled={disabled || busy}
          className={`${inputStyle} uppercase`}
        />
        <button
          type="button"
          onClick={() => submit(code)}
          disabled={disabled || busy || !code.trim()}
          className="border border-cocoa/20 px-4 py-3 text-sm text-cocoa transition hover:border-gold disabled:opacity-40"
        >
          {busy ? '…' : 'Apply'}
        </button>
      </div>
      {message && <p className="mt-2 text-sm text-terracotta">{message}</p>}
    </div>
  );
}
