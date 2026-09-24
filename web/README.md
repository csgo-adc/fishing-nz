# CatchCheck Web

Public CatchCheck NZ website and the authenticated admin workspace, built with Next.js, React, TypeScript, and Tailwind CSS.

## Local development

```bash
npm install
cp .env.example .env.local
npm run dev
```

The account page is at `/account`. The live account CMS is at [https://cms.fishnz.space/admin](https://cms.fishnz.space/admin). Sign in with the `ACCOUNT_ADMIN_TOKEN` configured on the CatchCheck Worker. The CMS and account proxy run on Cloudflare Workers through OpenNext; deploy from this folder with `npm run deploy`. The app calls the account API at `https://fishing.fishnz.space` by default.

## Checks

```bash
npm run lint
npm run build
```
