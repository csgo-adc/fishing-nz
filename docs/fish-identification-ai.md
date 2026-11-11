# Fish identification AI

The apps never talk to an AI service themselves. They send the photo to the Fishdays - NZ API
(`POST /v1/fish/identify`), which checks the account's daily allowance, asks the AI provider chosen in the admin CMS,
adds the MPI rules and answers. Changing the provider, model or API key in the CMS takes effect at once, with no app
update and no deploy.

## Why identification stopped working (November 2026)

- Identification worked until 11 October 2026. Every attempt since 25 October failed (three on 25 October, one on
  10 November), seen in `api_usage_daily` for the `fish_identify` route.
- The identification code had not changed since 24 September, and the model, `gpt-5.6-luna`, is current and not on
  OpenAI's deprecation list.
- "Fish identification is temporarily unavailable." was what the old code returned whenever OpenAI answered with an
  error other than 429. It threw OpenAI's reason away and logged nothing, so the exact reason cannot be read back from
  production. A 429 (rate or billing limit) would have shown a different message, so it was not that.
- That leaves OpenAI rejecting the API key or the request: most likely the key in the `OPENAI_API_KEY` Worker secret
  was revoked or deleted, its project was archived, or the project no longer allows the model. No OpenAI key appears
  in the Git history, so a leaked-and-revoked key from this repository is unlikely.

What to do: in platform.openai.com check API keys (is the key still active?), Billing, and the project's model
limits. After deploying 1.0.69 or later, open the CMS → **Fish ID** and press **Test with sample picture**: it shows
the provider's own error (for example `HTTP 401: invalid_api_key`) and what to do about it. Paste a new key there, or
choose another provider.

## In the CMS (Fish ID page)

- **Main provider** answers every photo. **Backup provider** (optional) is tried when the main one fails for any
  reason; the app only sees an error when both fail. A failed identification never uses the person's allowance.
- Providers: OpenAI (Responses API), Anthropic Claude (Messages API through the official SDK), Google Gemini
  (`generateContent`), and any **OpenAI-compatible** service with a base URL, such as OpenRouter
  (`https://openrouter.ai/api/v1`). Each asks for the same JSON answer, so the apps see no difference.
- Default models: `gpt-5.6-luna`, `claude-opus-5-5` (at effort `low`, with Anthropic's server-side refusal fallback),
  `gemini-flash-latest`. Any model name can be typed; it must accept photos.
- **Chinese providers** (1.0.75), listed under "China" in the CMS. Each uses its OpenAI-compatible Chat Completions
  API in JSON object mode, with the answer's fields spelled out in the prompt. The base URL is filled in and can be
  changed for another region:

  | Provider | Default model | Default base URL | Other region |
  | --- | --- | --- | --- |
  | Alibaba Qwen (Model Studio) | `qwen-vl-max` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | Mainland China `https://dashscope.aliyuncs.com/compatible-mode/v1` |
  | Zhipu GLM (BigModel / Z.ai) | `glm-4.5v` | `https://open.bigmodel.cn/api/paas/v4` | International `https://api.z.ai/api/paas/v4` |
  | Moonshot Kimi | `kimi-k2.6` | `https://api.moonshot.ai/v1` | Mainland China `https://api.moonshot.cn/v1` |
  | ByteDance Doubao (Volcano Engine Ark) | `doubao-seed-1-6-vision-250815` | `https://ark.cn-beijing.volces.com/api/v3` | Your Ark endpoint ID (`ep-…`) also works as the model |
  | DeepSeek | `deepseek-v4-flash-vision-exp` | `https://api.deepseek.com` | Only its vision models accept photos |

  These providers rename and retire models often, so the defaults are a starting point: **Test** checks the model
  typed in before saving. Keys for them are saved in the CMS only (no Worker secrets). If a provider rejects the JSON
  option, the request is sent once more with the format in the prompt alone; any other error is not retried.
- **Test with sample picture** (the rules page's snapper) or **Test with a photo** runs the form as it is, before
  saving, and shows the answer, the time it took or the provider's error. Tests don't count toward anyone's allowance.
- The top card shows whether the main provider is working: the last success, the last failure with the provider's
  message and what to do about it. Each provider's last result is kept in `app_settings` (`fish_ai.status.<provider>`).
  Failures are also logged (`npx wrangler tail`).

## API keys

- A key pasted in the CMS is encrypted with AES-GCM and stored in D1 (`app_settings`, `fish_ai.key.<provider>`). It is
  never sent back to the CMS; only its last four characters are shown.
- Encryption key: the `SETTINGS_ENCRYPTION_KEY` Worker secret if set, otherwise one derived from
  `ACCOUNT_ADMIN_TOKEN`. Setting `SETTINGS_ENCRYPTION_KEY` (any long random string) before saving keys is recommended,
  so changing the admin token later doesn't make saved keys unreadable. A key that can't be read is reported in the CMS;
  enter it again.
- Without a saved key the Worker secret is used: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` or `GEMINI_API_KEY`
  (all optional). The OpenAI-compatible provider only uses a key saved in the CMS.

```sh
cd server/fishial-proxy
npx wrangler secret put SETTINGS_ENCRYPTION_KEY   # recommended, before saving keys in the CMS
```

## Privacy

The photo consent screens in both apps and the privacy policy (`server/fishial-proxy/src/privacy.ts`) say photos go to
OpenAI. Photos sent to a Chinese provider are handled under Chinese law, often on servers in China; under the New Zealand
Privacy Act (IPP 12, disclosure overseas) the privacy policy and consent have to say so before one is used. Before making another provider the main one or a backup, update those texts and the App Store App Privacy and
Google Play Data safety answers to name it. The CMS shows this reminder whenever a provider other than OpenAI is chosen.

## Deploying

Migration `0014_app_settings.sql` adds the settings table. The Deploy workflow applies it; by hand:

```sh
cd server/fishial-proxy
npx wrangler d1 migrations apply RULES_DB --remote
npm run deploy
```

Until the migration runs, identification keeps using OpenAI with `OPENAI_API_KEY` and the CMS page can't save.

## Admin API

All need the `x-account-admin-token` header. The CMS reaches them through `/api/cms/fish-ai…`.

| Route | Purpose |
| --- | --- |
| `GET /v1/admin/fish-ai` | Setup, providers with key source and last result, 14 days of identifications |
| `PUT /v1/admin/fish-ai` | `{ "primary": {provider, model, base_url}, "backup": {…} or null }` |
| `PUT /v1/admin/fish-ai/keys/<provider>` | `{ "key": "…" }`, stored encrypted |
| `DELETE /v1/admin/fish-ai/keys/<provider>` | Remove the saved key |
| `POST /v1/admin/fish-ai/test?slot=primary\|backup` or `?provider=&model=&base_url=` | Body: a JPEG, PNG or WebP under 8 MB |
