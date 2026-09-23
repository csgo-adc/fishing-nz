# NZ fish ID demo

A local, one-photo demo that asks an OpenAI vision model for a likely New Zealand fish name, scientific name, confidence, and a few alternatives. It is a lightweight standalone interface for trying the same model choice as the shared app service.

## Run it

1. Make sure your OpenAI API account has billing enabled. ChatGPT subscription access and API billing are separate.
2. In a terminal, go to the project folder and set your API key for that terminal session:

   ```sh
   export OPENAI_API_KEY="your-api-key"
   node demo/openai-fish-id/server.mjs
   ```

3. Open [http://127.0.0.1:4178](http://127.0.0.1:4178), choose a photo, and select **Identify fish**.

The key stays in the server process and is never sent to the browser. The demo uses `gpt-5.6-luna` by default; set `OPENAI_FISH_MODEL` to another vision-capable model if you want to compare results. Each image request uses paid API usage and is billed by token use. Keep this server local; it has no authentication and is not intended for public hosting.
