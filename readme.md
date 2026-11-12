# GeminiBridge

A local AI gateway with OpenAI Responses and Chat Completions APIs and a React management interface. Java 21, Spring Boot 3, SQLite, and one executable JAR.

**Gemini Web uses an unofficial session adapter.** It reads the Google session cookies from a Firefox profile you select, discovers models offered to that account in Gemini Web, and sends requests through the web service. This is not the public Gemini API. There are no hardcoded model substitutions. Google may change the web protocol or require browser verification; the adapter does not bypass those checks.

Only Gemini Web is registered in the running application. Connect your Firefox session in Settings to discover models.

## Disclaimer

GeminiBridge is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Google, OpenAI, Anthropic, or any other company whose names or products are mentioned here. Gemini, Google, OpenAI, Codex, Claude, and Claude Code are trademarks of their respective owners.

The project is provided "as is", without warranty of any kind, express or implied. You use it entirely at your own risk. The authors and contributors are not liable for any damages, data loss, account restrictions, suspensions, or bans, or any other consequences arising from its use.

Using an unofficial adapter to access Gemini Web programmatically may violate Google's Terms of Service and may lead to restrictions on your Google account. You are solely responsible for complying with the terms of every service you use with this software, and with the laws that apply to you.

The gateway reads session cookies from your browser profile and sends your prompts to Google. Do not use it with accounts you cannot afford to lose, and do not send confidential or regulated data through it. It is intended for personal, local, non-commercial use only. Do not expose it to a network or share access to it.

## Requirements

- Java 21
- Node.js 22.12 or newer
- npm

The Gradle wrapper is included. The first build needs access to Maven Central, the Gradle distribution service, and the npm registry.

## Build and run

```sh
./gradlew build-dependencies
./gradlew build
./gradlew run
```

`build` compiles both applications, runs backend and frontend tests, and packages the frontend into Spring Boot static resources. The executable is `build/libs/geminibridge-1.0.0.jar`.

You can run the packaged application without Node.js:

```sh
java -jar build/libs/geminibridge-1.0.0.jar
```

Open `http://127.0.0.1:8080`. The management interface opens directly without a login screen.

GeminiBridge does not generate or validate local API keys. The client API accepts any bearer value, and the Authorization header is optional. If a client SDK requires an API key setting, use a placeholder such as `local`.

Only loopback connections are accepted. Host validation, cross-origin restrictions, request-size limits, and secret redaction remain enabled. Google session access is configured separately in Settings.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `GB_HOST` | `127.0.0.1` | Bind address |
| `GB_PORT` | `8080` | HTTP port |
| `GB_DATA_DIR` | `./data` | SQLite, browser selection, and server configuration |
| `GB_FIREFOX_ROOT` | Platform Firefox directories | Optional Firefox profiles root directory |

Server settings, history preferences, and appearance are editable in Settings. Host and port changes require a restart. Environment variables override saved host and port values. The displayed Base URL describes the active server, including its actual port.

The UI permits loopback addresses only. Requests from non-loopback peers are rejected regardless of the bind address. Forwarding headers do not grant access. Keep the default local binding.

## Responses API

Supported endpoints:

```text
POST /v1/responses
POST /v1/chat/completions
GET /v1/models
GET /v1/models/{modelId}
```

The Authorization header is optional. Clients that require an API key can send any placeholder value.

```sh
curl http://127.0.0.1:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemini-3.5-flash-lite","input":"Hello, GeminiBridge!"}'
```

Add `"stream":true` and use `curl -N` for streaming. A keep-alive SSE comment is sent every 10 seconds while generation is running; it has no Responses event sequence number. The stream sends named Responses events: response creation, progress, output item and content part creation, text deltas, text and item completion, and `response.completed`. Errors after streaming begins use `response.failed`. Each event includes a sequential `sequence_number`.

The protocol follows the [official Responses streaming event reference](https://developers.openai.com/api/reference/resources/responses/streaming-events).

### Python client

Install the OpenAI Python SDK in your client environment, then:

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8080/v1",
    api_key="local",
)

response = client.responses.create(
    model="gemini-3.5-flash-lite",
    input="Hello, GeminiBridge!",
)
print(response.output_text)

with client.responses.stream(
    model="gemini-3.5-flash-lite",
    input="Test streaming",
) as stream:
    for event in stream:
        if event.type == "response.output_text.delta":
            print(event.delta, end="", flush=True)
```

The gateway implements text Responses and Chat Completions endpoints. Other OpenAI endpoints are not implemented.

### Chat Completions

`POST /v1/chat/completions` accepts a model ID, text messages, and optional streaming. Responses use the standard `choices[0].message.content` envelope. Streams send `chat.completion.chunk` objects, a final `finish_reason: "stop"` chunk, and `[DONE]`. Both protocols use the same provider routing, concurrency limits, cancellation handling, and SQLite history.

[Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).

Gemini Web does not provide verified native temperature controls, token limits, JSON Schema output, or function tools through this adapter. Requests containing those controls return a structured `400 unsupported_parameter` with the rejected field. Clients can negotiate provider defaults and request JSON through their prompt; JSON then needs client-side validation.

### Thinking

Enable extended thinking with `"thinking":true` in either API, or use the compatible reasoning controls:

```json
{"model":"gemini-3.1-pro","input":"Solve this problem step by step","reasoning":{"effort":"high","summary":"auto"}}
```

For Chat Completions, use `"reasoning_effort":"high"` alongside `messages`. Use either `thinking` or the reasoning control in a request; combining them returns `400 invalid_request`.

Gemini Web exposes two native levels: `low` and `medium` select standard thinking; `high` and `xhigh` select extended thinking. Omitted controls and `"thinking":false` use standard mode. Standard mode may still reason internally. The adapter sends the native thinking level while keeping the exact selected model. Availability and how much the model thinks remain controlled by Gemini and the connected account. Efforts `none` and `minimal`, token budgets, and explicit summary lengths are unsupported; `summary` accepts `auto` or null.

When Gemini returns thinking text, Responses includes a separate `reasoning` output item with `summary_text` parts. Streaming uses `response.reasoning_summary_part.added`, `response.reasoning_summary_text.delta`, `response.reasoning_summary_text.done`, and `response.reasoning_summary_part.done`, alongside output item events. Chat Completions returns this text in `choices[0].message.reasoning_content` or streaming `choices[0].delta.reasoning_content`; this field is a compatibility extension. Thinking is never mixed into the answer or retained as assistant context for `previous_response_id`. No thinking text or token counts are fabricated when Google omits them.

In Playground, enable **Thinking** beside **Streaming**. Thinking text appears in an expandable block, including during streaming. The selection is saved per conversation and carried into a new chat. Partial thinking is preserved on cancellation. As with other Playground messages, thinking text is saved in browser local storage; opt-in response body history also includes it.

## Coding agents

GeminiBridge implements only text generation. It rejects function tools with `400 unsupported_parameter`. Coding agents rely on tools to run commands and edit files, so agentic features (shell, file edits, patching) will not work through this gateway. Plain chat, explanation, and code-generation prompts that do not need tools are the supported use. Test with `curl` first to confirm that the gateway and your Gemini session work.

### Codex CLI

Codex CLI talks to custom providers over the Responses API, which GeminiBridge serves at `/v1/responses`.

Add to `~/.codex/config.toml`:

```toml
model = "gemini-3.5-flash-lite"
model_provider = "geminibridge"

[model_providers.geminibridge]
name = "GeminiBridge"
base_url = "http://127.0.0.1:8080/v1"
wire_api = "responses"
env_key = "GEMINIBRIDGE_API_KEY"
```

Set the placeholder key and start Codex:

```sh
export GEMINIBRIDGE_API_KEY=local
codex
```

Use a model ID listed in the **Models** page of GeminiBridge. One-off run without editing the config:

```sh
codex exec \
  -c model_provider=geminibridge \
  -c 'model_providers.geminibridge={name="GeminiBridge",base_url="http://127.0.0.1:8080/v1",wire_api="responses",env_key="GEMINIBRIDGE_API_KEY"}' \
  -m gemini-3.5-flash-lite \
  "Explain what this repository does"
```

If Codex fails with `unsupported_parameter` and names `tools`, the client is sending function tool definitions that the Gemini Web adapter cannot honor. This is expected.

### Claude Code

Claude Code speaks the Anthropic Messages API (`/v1/messages`). GeminiBridge does not implement it, so Claude Code cannot connect directly. You need a local translation proxy that accepts Anthropic Messages requests and forwards them to an OpenAI-compatible Chat Completions endpoint (for example LiteLLM or claude-code-router), pointed at:

```text
http://127.0.0.1:8080/v1
```

Configure the proxy with the upstream model ID from the **Models** page and any placeholder API key such as `local`. Then point Claude Code at the proxy, not at GeminiBridge:

```sh
export ANTHROPIC_BASE_URL=http://127.0.0.1:4000
export ANTHROPIC_AUTH_TOKEN=local
export ANTHROPIC_MODEL=gemini-3.5-flash-lite
export ANTHROPIC_SMALL_FAST_MODEL=gemini-3.5-flash-lite
claude
```

Replace `4000` with the port your proxy listens on, and `gemini-3.5-flash-lite` with the model name defined in the proxy. Claude Code sends tool definitions on every request, and GeminiBridge rejects them, so the session will fail unless the proxy strips tools. Even then, Claude Code will lose its agentic abilities. Treat this setup as experimental.

### Model IDs

Public IDs are derived from the display names returned by Gemini Web. For example:

| Gemini Web name | Gateway model ID |
| --- | --- |
| 3.1 Pro | `gemini-3.1-pro` |
| 3.5 Flash-Lite | `gemini-3.5-flash-lite` |
| 3.8 Flash | `gemini-3.8-flash` |
| 4 Argon | `gemini-4-argon` |

Only models discovered for the connected Google session are published. These names are examples of the mapping, not a fixed availability list. Model selection uses the exact upstream identifier behind each readable ID. Duplicate readable names for different upstream models are rejected rather than guessed.

Older opaque IDs remain accepted as exact aliases. Saved Playground conversations and their model labels are updated to the readable IDs after discovery. Request history preserves the original requested ID and records the resolved readable ID separately.

### Supported input and capabilities

- A text string or an array of text messages with user, assistant, system, or developer roles.
- `input_text` and `output_text` content parts.
- System instructions are passed to Gemini Web as text.
- Explicit message context or `previous_response_id`.
- Streaming, cancellation, and up to 16 concurrent generations.
- String metadata with the Responses API size limits.

Unknown models return `model_not_found`. No fallback is selected. The web adapter rejects unsupported temperature, output token limits, function tools, structured output, and image input. Usage is omitted when Google does not report it.

With `store=true`, the gateway retains response context **in memory** for 30 minutes, up to 100 conversations. Context is grouped by the client identifier and is lost on restart. A supplied bearer value is hashed for this identifier; requests without one use the local client group. This grouping is not authentication. This is separate from SQLite request history. Set `store=false` to avoid retaining context. Previous response instructions are not inherited. There is no persistent response retrieval endpoint.

Request bodies are limited to 1 MiB. New request text is limited to 64 KiB and combined context to 128 KiB. Streams have a 120-second timeout. A disconnected or explicitly cancelled stream is recorded with status 499.

## Management interface

- Dashboard: recorded request counts, latency, success rate, available models, provider state, recent requests, and active Base URL.
- Playground: streaming or complete-response chat, standard or extended thinking with expandable reasoning, system instructions, Markdown and highlighted code, copying, cancellation, elapsed waiting time, duration, and provider usage.
- Models: availability, capabilities, refresh, details, and model-specific Playground links.
- Request History: search, model and status filters, date sorting, pagination, details, JSON copying, and deletion.
- Providers: connection and authentication state, last request and error, and connection checks.
- Settings: server configuration, history policy, retention, Google session status, theme, and sidebar preferences.

If a browser interrupts an SSE connection, turn off **Streaming** in Playground to receive a complete JSON response. Both modes support explicit cancellation. New chats keep the selected model, and new assistant messages record the model that produced them.

Playground conversations are stored separately in browser local storage, with the latest 20 conversations retained. Delete a conversation in Playground to remove it from that browser. Clearing API history does not delete Playground conversations.

### Connect your Gemini Web session

1. Sign in at `https://gemini.google.com/app` in Firefox and verify that the desired models are available there.
2. Open **Settings > Authentication** in GeminiBridge.
3. Select that Firefox profile and click **Connect browser session**.
4. Open Models. Only models returned by Gemini Web for the authenticated account are listed.

The adapter supports normal Firefox profiles on Linux, macOS, and Windows, including the Linux Flatpak profile location. Firefox containers and encrypted Chromium cookie stores are not read. Use `GB_FIREFOX_ROOT` when your profiles are in another directory.

A read-only copy of the cookie database and its WAL is made in a private temporary directory. The snapshot is deleted after reading. Only the `__Secure-1PSID` and `__Secure-3PSID` session cookies, their `TS`/`CC` companions, and `GOOGLE_ABUSE_EXEMPTION` for Google domains and the default Firefox container are queried. The optional exemption cookie carries a security check already completed in the browser. They are read together from the same profile snapshot to preserve a consistent login. Cookies renewed by Google are retained in a session-scoped cookie jar and reused for model discovery and generation. They are sent only to the configured Gemini origin. Active cookies and web request tokens remain in memory; persistent configuration contains only the selected profile identifier. No cookie value or web token is returned to the UI or logged. Browser files are never modified.

Connection is reported as available only after the authenticated web page and the account model discovery response are verified. Guest, restricted, expired, and unsupported responses do not create model availability. Reconnect after renewing your Firefox login if needed. A redirect to Google's automated-access security check is reported separately as `gemini_security_check_required` (HTTP 403). The browser can remain signed in while programmatic generation is restricted. Refreshing the model list alone does not clear a known generation block. When Google supplies a verification URL, **Settings > Authentication > Open Google verification** opens that exact check in the selected Firefox profile. Complete it on the same network, then click **Check session** to import updated cookies and retry your message, even when the login cookies have not changed. Verification links stay in memory for up to ten minutes and are never returned in status, API errors, or saved preferences. Only HTTPS verification pages on explicitly allowed Google hosts can be opened. Manual-cookie users can select **Browser import** to choose the Firefox profile for this check, or enter the updated cookies manually. Reconnection verifies the page and model list; the next generation request determines whether Google has lifted its restriction. A repeated challenge is reported again without automatic retries. Ordinary redirects to another Gemini app URL are followed on the same origin; sign-in, consent, and browser-verification redirects are not followed by the HTTP client. The verification page is opened only through the explicit browser action. Account routing returned by the Gemini app URL is retained for subsequent requests. A saved successful profile selection is reconnected on application startup. The selected browser session is reread before its ten-minute verification window expires.

**Disconnect** clears GeminiBridge's session and disables automatic reconnection. It does not sign Firefox out of Google.

Gemini Web receives system instructions and conversation context as one text prompt. Native role semantics, temperature, output token limits, image input, and function calling are not claimed. The upstream read timeout is 110 seconds, with a 115-second overall request deadline. Requests select the exact discovered model identifier. The adapter requests a temporary web conversation; the data is still sent to Google and handled under your Google account settings. Token usage is unavailable unless the upstream reports it.

### Management endpoints

All management endpoints accept local requests directly:

```text
GET /api/dashboard
GET /api/providers
GET /api/providers/status
POST /api/providers/{id}/check
GET /api/models
GET /api/models/{id}
POST /api/models/refresh
GET /api/history
GET /api/history/{id}
DELETE /api/history
GET /api/settings
PUT /api/settings
GET /api/auth/status
GET /api/auth/profiles
POST /api/auth/start
POST /api/auth/logout
POST /api/playground/responses
POST /api/playground/requests/{id}/cancel
```

`POST /api/auth/start` accepts `{"profileId":"<id from /api/auth/profiles>"}`. It reads the selected session and verifies access. Missing, expired, denied, and changed-protocol responses have structured errors.

History accepts zero-based `page`, `size` from 1 to 100, `search`, `model`, `status` (`all`, `success`, `error`, `cancelled`), and `direction` (`asc`, `desc`). Search covers request ID, model, and client identifier.

## History and privacy

SQLite stores request ID, timestamp, requested and resolved model, provider, duration, streaming status, HTTP status, error, reported usage, and a hashed client identifier. Response requests rejected for authentication, invalid JSON, invalid parameters, or an unknown model are also recorded.

Request and response bodies are excluded by default. Body storage is opt-in. Stored headers use an allowlist; Authorization and Cookie values are redacted. Known Google session credentials, common token patterns, and sensitive JSON fields are removed from stored content. Redaction does not guarantee detection of arbitrary secrets in free text, so keep body storage disabled for sensitive conversations.

Retention defaults to 30 days. Cleanup runs shortly after startup and hourly afterward. Turning off content storage affects new requests; use Clear history to delete existing bodies. Deleting history removes database records, but it does not securely erase SQLite pages or external backups.

Cross-origin requests are rejected. Local host validation limits DNS rebinding. The UI has a content security policy, frame protection, and no external analytics or fonts.

## Development and tests

```sh
./gradlew test
./gradlew unitTest
./gradlew frontendTest
./gradlew runTest
```

- `test`: backend unit and SQLite/controller integration tests
- `unitTest`: provider, parser, context, history policy, and security units
- `frontendTest`: components, API errors, SSE parsing, Playground, and history
- `runTest`: isolated server on `127.0.0.1:18080`

`test_run/` contains test databases, temporary files, npm cache, and reports. It is excluded from Git. Normal application data remains in `data/`.

To test the packaged interface in Chromium:

```sh
cd frontend
npm run browser:install
cd ..
./gradlew browserTest
```

Browser tests start their own packaged server on port 18081 using `test_run/e2e/`. They cover direct workspace access, real streaming, cancellation, history, settings, model discovery, mobile navigation, and requests with arbitrary or missing API keys. Set `GB_TEST_BROWSER` to a Chromium executable path to use an existing installation.

You can also pass the executable directly with `./gradlew browserTest -PtestBrowser=/path/to/chromium`.

Backend HTML reports are in `test_run/reports/backend/`, unit reports in `test_run/reports/unit/`, frontend results in `test_run/reports/frontend.xml`, and browser reports in `test_run/reports/browser/`.

For frontend development, start the backend with `./gradlew run`, then run `npm run dev` inside `frontend/`. Vite proxies management and client API requests to port 8080. Update the proxy target in `vite.config.ts` if you use a different backend port.

After changing frontend dependencies, update the lockfile with `./gradlew frontendInstall -PupdateFrontendDependencies`. Normal builds use `npm ci`.

## Project structure

```text
src/main/java/me/vonyayou/geminibridge/
  gateway/    Responses routing, normalization, SSE, context, dashboard
  provider/   Provider contract, model registry, unofficial Gemini Web adapter
  auth/       Google session credentials, local request protection, provider auth state
  history/    SQLite records, filtering, redaction, retention
  config/     Persisted settings, data directory, SPA routing
frontend/
  src/        React, TypeScript, TanStack Query, Zustand, React Router
  e2e/        Packaged application browser tests
```

The UI uses Tailwind CSS, shadcn-style Radix components, and Lucide icons. Controllers delegate to services; providers expose their own availability and capabilities.

## Gemini Web protocol support

The unofficial adapter uses the Gemini Web page bootstrap and its model-status and generation RPCs. Protocol observations were cross-checked against [Gemini-API](https://github.com/HanaokaYuzu/Gemini-API). The Java implementation handles only verified text responses, strict model selection, and session errors. It does not import the third-party Python client or use browser fingerprint impersonation, device-binding workarounds, proxy rotation, or access-check bypasses.

Backend tests use a test-only provider, synthetic Firefox cookie databases, and a local MockWebServer. Browser tests exercise the packaged application against a synthetic local Gemini Web service. Test fixtures are not included in the executable JAR. Automated tests never read your real browser profile or call Google. A live connection check must be explicitly started with your selected browser session.

## Manual cookie entry

Manual mode means entering Google session cookies yourself. Requests and responses remain automatic. There is no prompt queue or copy-and-paste answer relay.

Open **Settings > Authentication**, choose **Manual cookies**, and enter cookie values from your signed-in Gemini Web browser session. `__Secure-1PSID` is required. Enter `__Secure-1PSIDTS` when present. The remaining `CC` and `3PSID` cookies can be supplied under **Additional session cookies**, together with `GOOGLE_ABUSE_EXEMPTION` if Google issued it after a browser security check. Copy values from the same browser session, then click **Connect with entered cookies**.

The manual connection calls the Gemini adapter directly and never reads the Firefox cookie database. Email and model discovery use the supplied session. Entered values are cleared from the form after successful connection and are never returned by status or settings endpoints, stored in local storage, written to the preference file, or logged.

Cookies remain in server memory until replacement, disconnection, or shutdown. The selected source is saved in `browser-session.json`, but its values are not. A restarted manual connection therefore asks you to enter the cookies again instead of importing a browser profile. Cookie updates from Google are reused in memory while the connection is active.

`POST /api/auth/manual` accepts `{"cookies":{"__Secure-1PSID":"<value>","__Secure-1PSIDTS":"<value>"}}`. Only the six supported Google session cookie names and the optional `GOOGLE_ABUSE_EXEMPTION` cookie are accepted. Status includes `source`, `manualCookiesLoaded`, and `accountEmail` when available, with no secret values.

Manual cookie entry isolates browser-import problems. It does not assert that any Google automated-access security check has been completed. Authentication failures and Google verification redirects remain separate structured errors. Models are published only after session and model discovery verification.

## Log in with Google

In **Settings > Authentication**, choose **Browser import**, select a Firefox profile, and click **Log in with Google**. GeminiBridge opens Google's account chooser in that profile with Gemini Web as the destination. Complete sign-in in Firefox, open Gemini, return to the panel, and click **Check session**. The panel imports and verifies the selected browser session and displays the account email when Google provides it.

This is a browser sign-in flow for Gemini Web, not Google OAuth authorization of the gateway. Passwords and verification codes are entered on Google's pages and are never submitted to GeminiBridge. A successful browser login does not remove Google's separate restrictions on automated generation.

Firefox must be installed and runnable in the server's desktop environment. If it cannot be opened, the panel reports the launch error; you can open the selected profile yourself and use **Connect browser session**. The launcher uses a fixed Google HTTPS URL and separate process arguments, without shell commands or profile-lock workarounds. Set `bridge.firefox-executable` as a Spring property if Firefox is installed at a custom path.

The pending login expires after ten minutes. Cancel closes only the panel's pending flow, not the user's Firefox window or Google account session.

```text
POST /api/auth/google/start
POST /api/auth/google/{id}/complete
POST /api/auth/google/{id}/cancel
```

`POST /api/auth/google/verify` starts the pending Google verification in the selected Firefox profile using the same `{"profileId":"<id>"}` body as the sign-in endpoint. It accepts no URL from the caller. Its flow is completed or cancelled using the existing Google flow endpoints. `verificationAvailable` in auth status indicates whether a current link can be opened. **Check session** rechecks authentication and model discovery; the next generation request determines whether Google's generation restriction has cleared.
