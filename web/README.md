# CatchCheck Web

Public CatchCheck NZ website and the authenticated admin workspace, built with Next.js, React, TypeScript, and Tailwind CSS.

## Local development

```bash
npm install
cp .env.example .env.local
npm run dev
```

Open `http://localhost:3000` for the public website, `http://localhost:3000/account` for registration and account management, and `http://localhost:3000/admin` for the account CMS. The account and CMS server routes use `FISH_ID_API_BASE_URL` (defaulting to the deployed CatchCheck Worker). Deploy the website with Next.js Route Handler support for these routes to work.

## Checks

```bash
npm run lint
npm run build
```
