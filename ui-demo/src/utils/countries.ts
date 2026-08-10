// The only destinations si Chocolate ships to. The shop does not enforce this —
// the picker is what keeps an unshippable address from being saved or ordered to,
// so both the checkout form and the saved-address form must use this list.
export const SHIPPING_COUNTRIES = [
  'Moldova',
  'Romania',
  'Switzerland',
  'Italy',
  'United Kingdom',
] as const;

export const isShippable = (country: string) =>
  (SHIPPING_COUNTRIES as readonly string[]).includes(country);
