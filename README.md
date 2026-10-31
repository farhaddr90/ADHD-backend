# Daybook Plus server

A small Java 21 server for Daybook Plus. It does two jobs:

1. **`POST /plus/verify`**: asks Cafe Bazaar whether a subscription is paid. If it is, it gives the
   app an access token.
2. **`POST /v1/chat/completions`**: checks the token, counts the use, and passes the chat to your
   OpenAI-compatible AI server. It uses the server's own key and model.

The app's side of this contract is in `../android/docs/plus-server.md`.

It has no framework: the JDK HTTP server, SQLite (one file) and `org.json`.

## Build and test

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./gradlew -q test          # unit tests
./gradlew -q serverJar     # build/libs/plus-server.jar (everything inside)
```

## Settings

All settings are environment variables. See `.env.example` for the full list.

| Variable | What |
|---|---|
| `BAZAAR_CLIENT_ID`, `BAZAAR_CLIENT_SECRET` | From the Bazaar panel: **Developer API → New client** |
| `BAZAAR_REFRESH_TOKEN` | Made once, see below |
| `AI_URL`, `AI_KEY`, `AI_MODEL` | Your OpenAI-compatible AI server (`AI_URL` ends in `/v1`) |
| `PACKAGES`, `PRODUCTS` | The app id and subscription id this server accepts |
| `DAILY_LIMIT` | AI messages per subscriber per day (default 100) |

## One-time Bazaar login (refresh token)

Bazaar's API uses OAuth. You do this once:

1. In the Bazaar developer panel, open **Developer API** and create a **New client**. Set any
   Redirect URI, for example `https://your-server/bazaar-callback`. The page does not need to exist.
2. Open this address in your browser, logged in to the panel:
   `https://pardakht.cafebazaar.ir/devapi/v2/auth/authorize/?response_type=code&access_type=offline&redirect_uri=<REDIRECT_URI>&client_id=<CLIENT_ID>`
3. Click **Authorize**. The browser goes to your Redirect URI with `?code=…` in the address. Copy that code.
4. Run this right away (the code is valid only for a short time):
   ```bash
   BAZAAR_CLIENT_ID=… BAZAAR_CLIENT_SECRET=… java -jar plus-server.jar bazaar-login <code> <REDIRECT_URI>
   ```
   It prints `BAZAAR_REFRESH_TOKEN=…`. Put it in `.env`. The server then gets short access
   tokens with it by itself.

## Run on a Linux server

Put the server behind HTTPS. Here is an example with Caddy, which gets the certificate by itself:

```
# /etc/caddy/Caddyfile
api.example.ir {
    reverse_proxy 127.0.0.1:8080
}
```

```ini
# /etc/systemd/system/daybook-plus.service
[Unit]
Description=Daybook Plus server
After=network-online.target

[Service]
User=daybook
EnvironmentFile=/etc/daybook-plus/.env
ExecStart=/usr/bin/java -jar /opt/daybook-plus/plus-server.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

Then set `daybook.plusServer=https://api.example.ir` in the app's `gradle.properties`.

Check the server with `curl https://api.example.ir/health`. It answers `{"ok":true}`.

## How it decides

- **Paid** means that Bazaar knows the purchase and `validUntilTimestampMsec` is in the future.
  Bazaar's 404 `not_found` means "not bought". Any other Bazaar error returns 502, and the app keeps
  what it had.
- **One token per purchase.** Each verify makes a new token, and the old one stops working. So a
  shared purchase only works on one phone at a time.
- **Tokens expire** at the end of the paid period, or 24 hours after the last Bazaar check
  (`RECHECK_HOURS`). This way a refund is noticed. The app then gets `401`, verifies again on
  its own, and retries.
- **Fair use.** After `DAILY_LIMIT` messages in a day, the server answers `429`.
- **Errors from your AI server** (bad key, model not found, down) become `502`. The app shows
  "busy". The real error is in the server log.
- **Nothing secret is stored or logged.** The database stores only a SHA-256 of each access token.
  The log has one line per request, with no tokens or chat text.

## Data

There is one SQLite file (`DB_PATH`) with one row per purchase. Back it up if you like. If you
lose it, nothing breaks: each app verifies again and gets a new token.
