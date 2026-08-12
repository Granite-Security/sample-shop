# Deferred guardrails

Things proposed, deliberately **not** built, with the reason each was proposed. Nothing
here is a bug today; each is a control we chose to leave out to keep a first version
simple. Pick them up when the shape of the feature justifies the cost.

## Public profile files (`docs/profile/public-profile.md` §11)

Proposed alongside "publish a file to your profile", dropped by decision to keep the
first version simple.

### 1. Refuse to publish `text/html` and `image/svg+xml`

Both execute script when opened directly, and both would be served from
`media.granite-security.org` — a domain that visibly belongs to us. A published SVG or
HTML file is a working phishing page on our own media domain, reachable from a profile
page anyone can view.

It is not a session-hijack risk: the media host is a different origin from the app, so
app cookies and tokens are out of reach. The risk is credibility lent to a hostile page.

Note the upload allow-list (`UserFileService.ALLOWED_CONTENT_TYPES`) is
`image/jpeg, image/png, image/webp, application/pdf, text/plain` — so **SVG and HTML
cannot be uploaded today at all**, which is why leaving this out costs nothing right now.
It becomes real the moment that list grows. If SVG is ever added for avatars or media,
this check has to land in the same change.

### 2. Render by content type on the public page

Currently every published file renders as a plain link. If that ever becomes an inline
preview, bind the rendering to `contentType`: `image/*` as `<img>`, everything else as a
download link. Never an inline HTML/PDF embed of a stranger's file.

### 3. Cap the number of published files

`GET /api/profiles/public/{handle}/files` is anonymous and unpaginated. The per-user
upload cap (`MAX_FILES_PER_USER = 50`) bounds it in practice, so the response cannot run
away today — but the bound is incidental, not chosen. A 10–20 file cap, or pagination,
is the deliberate version.

## Public profile, general

### 4. Rate limit on the anonymous read

`GET /api/profiles/public/{handle}` has no limit. The data is public by definition, so
the exposure is load, not disclosure. Revisit if it is ever abused.

### 5. Handle-change redirects

Changing a handle breaks previously shared links. The user is told so at the point of
change; there is no redirect from the old handle. Related: on account deletion the handle
is released immediately rather than tombstoned, so a URL can later point at a different
person (`docs/profile/public-profile.md`, open question 3).
