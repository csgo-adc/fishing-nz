# Fishing Days Admin

Fishing Days NZ administrator workspace, built with Next.js, React, TypeScript, and Tailwind CSS. The home page redirects to `/admin`. User account registration and sign-in are available in the mobile apps only.

## Local development

```bash
npm install
cp .env.example .env.local
npm run dev
```

The live account CMS is at [https://cms.fishnz.space/admin](https://cms.fishnz.space/admin). Sign in with the `ACCOUNT_ADMIN_TOKEN` configured on the Fishing Days Worker. The CMS runs on Cloudflare Workers through OpenNext; deploy from this folder with `npm run deploy`. It calls the shared API at `https://fishing.fishnz.space` by default.

See [mobile Google and Apple setup](../docs/account-sign-in.md) for credentials, callbacks, migrations, and deployment. No provider credentials or user session cookies are handled by the CMS.

## Checks

```bash
npm run lint
npm run build
```
