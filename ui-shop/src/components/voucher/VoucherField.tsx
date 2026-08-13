import { useEffect, useState } from 'react';
import { api } from '../../api';
import type { PackagingChoice, VoucherPreview } from '../../types';

interface Props {
  items: { productId: number; quantity: number }[];
  /** The shopper's chosen boxes, so the preview prices the same cart placement will. */
  packaging: PackagingChoice[];
  /** Identity of the cart + boxes this preview would describe. */
  cartKey: string;
  disabled?: boolean;
  /** The applied voucher, or null when there is none. The parent sends its code on placement. */
  onChange: (voucher: VoucherPreview | null) => void;
}

/**
 * The voucher code box at checkout.
 *
 * The discount shown here is always the server's number — the same rule the packaging
 * picker follows. Nothing is computed in the browser, and applying a code stores and
 * reserves nothing: placement validates it again and can still refuse it.
 *
 * A code stays applied across cart edits and is silently re-priced, because the
 * discount is a percentage of a subtotal that just changed. If the re-price refuses
 * it — an expired code, a cart that dropped below the chargeable minimum — the field
 * says so and the parent is told the voucher is gone.
 */
export default function VoucherField({ items, packaging, cartKey, disabled, onChange }: Props) {
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
      const preview = await api.vouchers.preview(trimmed, items, packaging);
      // A refused code is a 200 with valid:false — the answer, not an error.
      if (preview.valid) {
        setApplied(preview);
        onChange(preview);
      } else {
        setApplied(null);
        onChange(null);
        setMessage(preview.message || 'That code cannot be used');
      }
    } catch (e: unknown) {
      setApplied(null);
      onChange(null);
      setMessage(e instanceof Error ? e.message : 'Could not check that code');
    } finally {
      setBusy(false);
    }
  };

  // Re-priced whenever the cart or the chosen boxes change: the discount is a
  // percentage of a subtotal that just moved, and a stale number here is a number the
  // shopper was never going to be charged.
  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    api.vouchers.preview(applied.code, items, packaging)
      .then(preview => {
        if (cancelled) return;
        if (preview.valid) {
          setApplied(preview);
          onChange(preview);
        } else {
          setApplied(null);
          onChange(null);
          setMessage(preview.message || 'That code no longer applies to this cart');
        }
      })
      .catch(() => {
        if (cancelled) return;
        // Leave the code applied and let placement be the judge: dropping a valid
        // discount because one request failed is the worse of the two mistakes, and
        // the server reprices from scratch anyway.
      });
    return () => { cancelled = true; };
    // Deliberately keyed on the cart, not on `applied` — re-running on its own result
    // would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cartKey]);

  const remove = () => {
    setApplied(null);
    setCode('');
    setMessage('');
    onChange(null);
  };

  if (applied) {
    return (
      <div style={{ margin: '8px 0' }}>
        <p style={{ fontSize: '0.9rem', margin: '4px 0' }}>
          Voucher <strong>{applied.code}</strong> applied — {applied.percentOff}% off
          {' '}(−${applied.discountTotal.toFixed(2)})
          {' '}
          <button
            type="button"
            className="btn-link"
            onClick={remove}
            disabled={disabled}
            style={{ background: 'none', border: 'none', textDecoration: 'underline', cursor: 'pointer', padding: 0 }}
          >
            remove
          </button>
        </p>
      </div>
    );
  }

  return (
    <div style={{ margin: '8px 0' }}>
      <label htmlFor="voucher-code" style={{ fontSize: '0.9rem', display: 'block', marginBottom: 4 }}>
        Have a voucher code?
      </label>
      <div style={{ display: 'flex', gap: 8 }}>
        <input
          id="voucher-code"
          value={code}
          onChange={e => setCode(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); submit(code); } }}
          placeholder="SPRING25"
          disabled={disabled || busy}
          style={{ flex: 1, textTransform: 'uppercase' }}
        />
        <button
          type="button"
          className="btn"
          onClick={() => submit(code)}
          disabled={disabled || busy || !code.trim()}
        >
          {busy ? 'Checking…' : 'Apply'}
        </button>
      </div>
      {message && <p className="error" style={{ fontSize: '0.85rem', margin: '4px 0' }}>{message}</p>}
    </div>
  );
}
