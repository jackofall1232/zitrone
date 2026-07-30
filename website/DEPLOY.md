# Deploying the marketing site to Vercel

The site (`website/`) is a standalone Next.js 15 app inside the pnpm monorepo. It has no
`workspace:*` dependencies, so it builds on its own — Vercel just needs to treat `website/` as the
project root.

## One-time Vercel setup (Git integration)

1. In the Vercel dashboard: **Add New → Project → Import** the `jackofall1232/zitrone` repo.
2. Set **Root Directory** to `website`.
3. Leave the framework as **Next.js** (auto-detected). Defaults are correct:
   - Install Command: `pnpm install` (Vercel detects `pnpm-lock.yaml` at the repo root)
   - Build Command: `next build`
   - Output: `.next`
4. Keep **"Include source files outside of the Root Directory"** enabled (default) so the root
   lockfile/workspace is available during install.
5. Deploy. Pushes to `main` then auto-deploy; PRs get preview URLs.

`vercel.json` (in this directory) pins the framework and sends `X-Robots-Tag: noindex` for the
unlisted `/download/beta` page.

### Custom domain

Add `zitrone.app` under the project's **Domains** tab and point DNS as Vercel instructs. For DNS
kept at Porkbun, the standard records are an apex `A` → `76.76.21.21` and a `www` `CNAME` →
`cname.vercel-dns.com` (use whatever the Domains tab shows for your project — it is authoritative).
Note `.app` is HSTS-preloaded, so the site is HTTPS-only; Vercel provisions the TLS cert
automatically. This is independent of the `relay.sublemonable.com` host, which stays on the
self-hosted box behind Caddy and is NOT repointed as part of the website deploy.

## Android beta APK (temporary)

The unlisted page `/download/beta` links to a GitHub Release asset. To publish a build:

1. Build the **release** APK (with the `relay.sublemonable.com` certificate pin compiled in — see
   `docs/SELF_HOSTING.md`).
2. Create a GitHub pre-release with tag matching `ANDROID_BETA_VERSION` in `src/lib/links.ts`
   (currently `v1.5.0-beta`) and upload the APK named `zitrone-<tag>.apk`.
3. Compute the checksum and paste it into `ANDROID_BETA_SHA256` in `src/lib/links.ts`:
   ```bash
   sha256sum zitrone-v1.5.0-beta.apk
   ```
4. Commit and push — Vercel redeploys, and the page shows the live download + checksum.

The page is intentionally **not linked from the nav or sitemap** — share its URL directly with
testers. Delete `src/app/download/beta/` and the beta block in `src/lib/links.ts` at store launch.

## Beta-signup email delivery (RESEND_API_KEY)

The Play-beta signup form (`/download/beta`) posts to `/api/beta-signup`, which forwards each
signup as one email to `admin@zitrone.app` — no database anywhere. Delivery uses Resend's HTTP
API and needs one env var on the Vercel project:

1. Create a free account at https://resend.com and an API key.
2. Vercel dashboard → the project → **Settings → Environment Variables** → add
   `RESEND_API_KEY` (Production + Preview), then redeploy.
3. Optional: verify the `zitrone.app` domain in Resend and set `BETA_SIGNUP_FROM` to e.g.
   `Zitrone Beta <beta@zitrone.app>`. Until then the route uses Resend's `onboarding@resend.dev`
   sender, which works out of the box.

Without the key the route answers 503 and the form falls back to a prefilled
`mailto:admin@zitrone.app` link — signups still arrive, just typed by the tester's own mail app.
If storage is ever added here, `/privacy` and the form's "no database" copy MUST change in the
same commit.
