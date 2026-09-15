# CatchCheck Web

Public CatchCheck NZ website and the authenticated admin workspace, built with Next.js, React, TypeScript, and Tailwind CSS.

## Local development

```bash
npm install
cp .env.example .env.local
npm run dev
```

Open `http://localhost:3000` for the public website and `http://localhost:3000/admin` for the admin foundation. The FastAPI base URL is configured with `NEXT_PUBLIC_API_URL`.

## Checks

```bash
npm run lint
npm run build
```
