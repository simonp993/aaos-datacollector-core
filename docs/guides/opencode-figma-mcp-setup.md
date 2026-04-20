# Figma MCP Server Setup for OpenCode

Connect the [Figma MCP Server](https://developers.figma.com/docs/figma-mcp-server/) to [OpenCode](https://opencode.ai/) so AI agents can read Figma designs, inspect components, and extract design tokens directly from your Figma files.

## Why a Workaround Is Needed

Figma's remote MCP server (`https://mcp.figma.com/mcp`) uses an [MCP catalog allowlist](https://www.figma.com/mcp-catalog/) to restrict which clients can connect. Approved clients include VS Code, Cursor, Claude Code, Windsurf, and others — but **OpenCode is not on the list**.

Unapproved clients that attempt OAuth dynamic client registration receive a `403 Forbidden` response from Figma's registration endpoint.

The workaround registers a custom OAuth client against Figma's API using a spoofed `client_name` that matches an approved client. The resulting `client_id` and `client_secret` are then used by OpenCode to complete the standard OAuth flow.

> **Source:** [anomalyco/opencode#988 (comment)](https://github.com/anomalyco/opencode/issues/988#issuecomment-4022520800)

---

## Prerequisites

| Requirement | Notes |
|---|---|
| [OpenCode](https://opencode.ai/) | v1.3+ with remote MCP and OAuth support |
| Figma account | Any plan (Starter, Professional, Organization, Enterprise) |
| `curl` | Pre-installed on macOS and most Linux distributions |
| Shell with env var support | zsh (macOS default), bash, etc. |

### Figma Plan Limits

Figma MCP usage is subject to daily API call limits based on your plan:

| Plan | Calls per Day |
|---|---|
| Enterprise | 600 |
| Organization / Professional | 200 |
| Starter | 6 per month |

---

## Step 1 — Generate a Figma Personal Access Token

A temporary Personal Access Token (PAT) is needed to authenticate the one-time client registration. It can be revoked immediately after.

1. Go to [Figma Account Settings → Personal Access Tokens](https://www.figma.com/settings) (scroll to the *Personal access tokens* section).
2. Click **Generate new token**.
3. Set the scope to **File content** → **Read only** (`file_content:read`).
4. Copy the token — you will not see it again.

> This PAT is only used in Step 2. It is **not** stored in any config file.

---

## Step 2 — Register an OAuth Client

Run the following `curl` command, replacing `<YOUR_FIGMA_PAT>` with the token from Step 1:

```bash
curl -X POST https://api.figma.com/v1/oauth/mcp/register \
  -H "Content-Type: application/json" \
  -H "X-Figma-Token: <YOUR_FIGMA_PAT>" \
  -d '{
    "client_name": "Claude Code (figma)",
    "redirect_uris": ["http://127.0.0.1:19876/mcp/oauth/callback"],
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "client_secret_post",
    "scope": "mcp:connect"
  }'
```

**Expected response:**

```json
{
  "client_id": "...",
  "client_secret": "...",
  "client_name": "Claude Code (figma)",
  "redirect_uris": ["http://127.0.0.1:19876/mcp/oauth/callback"],
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "client_secret_post",
  "scope": "mcp:connect",
  "client_secret_expires_at": 0,
  "client_id_issued_at": ...
}
```

Save the `client_id` and `client_secret` from the response — you need them in Step 3.

**Key facts about these credentials:**

- `client_secret_expires_at: 0` means the credentials **never expire**.
- They are tied to your Figma account.
- You only need to run this registration once.

---

## Step 3 — Store Credentials as Environment Variables

Add the credentials to your shell environment. For zsh (macOS default), add to `~/.zshenv`:

```bash
export FIGMA_CLIENT_ID="<client_id from Step 2>"
export FIGMA_CLIENT_SECRET="<client_secret from Step 2>"
```

Then reload:

```bash
source ~/.zshenv
```

> **Why `~/.zshenv`?** It is loaded for all zsh invocations (interactive and non-interactive), ensuring OpenCode always has access regardless of how it is launched.

> **Alternative locations:** `~/.zshrc` (interactive shells only), `~/.bash_profile`, or a `.env` file loaded by your shell profile. The only requirement is that the variables are set in the process environment when OpenCode starts.

---

## Step 4 — Configure OpenCode

The project's `opencode.json` already includes the Figma MCP entry with `{env:}` references:

```jsonc
{
  "mcp": {
    "figma": {
      "type": "remote",
      "url": "https://mcp.figma.com/mcp",
      "oauth": {
        "clientId": "{env:FIGMA_CLIENT_ID}",
        "clientSecret": "{env:FIGMA_CLIENT_SECRET}"
      }
    }
  }
}
```

The `{env:VAR}` syntax is an OpenCode feature that substitutes environment variables into config values at load time. This keeps credentials out of config files entirely.

> **Note:** `opencode.json` is listed in `.gitignore` and is not committed to the repository. Each developer maintains their own copy. Copy the figma entry from the snippet above if your local `opencode.json` does not have it.

---

## Step 5 — Authenticate

Run the OAuth authorization flow:

```bash
opencode mcp auth figma
```

This opens your browser to Figma's authorization page. Sign in and approve the connection. OpenCode stores the resulting OAuth tokens in `~/.local/share/opencode/mcp-auth.json` and refreshes them automatically.

---

## Step 6 — Clean Up

The Figma PAT from Step 1 is no longer needed. Revoke it:

1. Go to [Figma Account Settings → Personal Access Tokens](https://www.figma.com/settings).
2. Find the token you created and delete it.

---

## Verification

Start OpenCode and confirm the Figma MCP server is connected:

1. Look for `figma` in the MCP server list (status bar or `opencode mcp status`).
2. Ask the agent to interact with a Figma file, e.g.: *"Get the design tokens from `<figma_file_url>`"*.

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| Registration returns `403 Forbidden` | Missing or invalid PAT | Ensure `-H "X-Figma-Token: <PAT>"` is included and the PAT has `file_content:read` scope |
| `"An error occurred processing your request"` during auth | `clientId`/`clientSecret` not in `opencode.json` or env vars not set | Verify `echo $FIGMA_CLIENT_ID` returns a value; restart OpenCode after setting env vars |
| Auth flow opens browser but fails to complete | Stale tokens in `mcp-auth.json` | Delete the `figma` entry from `~/.local/share/opencode/mcp-auth.json` and re-run `opencode mcp auth figma` |
| `oauth.clientId` resolves to empty string | Env var not exported or shell not reloaded | Run `source ~/.zshenv` or restart your terminal; verify with `echo $FIGMA_CLIENT_ID` |
| Figma tools not appearing in agent | Server not enabled or connection timeout | Check OpenCode logs in `~/.local/share/opencode/log/` for connection errors |

---

## How It Works

The setup uses a standard [OAuth 2.0 Authorization Code](https://datatracker.ietf.org/doc/html/rfc6749#section-4.1) flow:

1. **Client Registration** (one-time, Step 2): Registers an OAuth client with Figma's MCP registration endpoint. The `client_name` must match an entry in Figma's [MCP catalog](https://www.figma.com/mcp-catalog/) — we use `"Claude Code (figma)"` which is an approved client. This yields a permanent `client_id` / `client_secret` pair.

2. **Authorization** (one-time, Step 5): OpenCode opens a browser to Figma's authorization URL. The user signs in and grants the `mcp:connect` scope. Figma redirects back to `http://127.0.0.1:19876/mcp/oauth/callback` with an authorization code. OpenCode exchanges this code (plus the client credentials) for an access token and a refresh token.

3. **Token Refresh** (automatic): OAuth access tokens expire. OpenCode automatically uses the refresh token to obtain new access tokens without user interaction. Tokens are stored in `~/.local/share/opencode/mcp-auth.json`.

4. **MCP Communication** (ongoing): OpenCode sends the access token with each MCP request to `https://mcp.figma.com/mcp`. Figma validates the token and serves MCP tool responses (design data, component info, etc.).

---

## References

- [Figma MCP Server — Remote Installation](https://developers.figma.com/docs/figma-mcp-server/remote-server-installation/)
- [Figma MCP Catalog](https://www.figma.com/mcp-catalog/) — list of approved MCP clients
- [OpenCode MCP Documentation](https://opencode.ai/docs/config/mcp)
- [GitHub Issue: OpenCode Figma MCP Support](https://github.com/anomalyco/opencode/issues/988) — original discussion and workaround
- [Workaround Source (comment #4022520800)](https://github.com/anomalyco/opencode/issues/988#issuecomment-4022520800) — 3-step registration approach
- [PAT Auth Fix (comment #4049063083)](https://github.com/anomalyco/opencode/issues/988#issuecomment-4049063083) — adding `X-Figma-Token` header to bypass 403
