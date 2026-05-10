# WebAgent

WebAgent is a small Java web service that audits a website's health, suggests improvements, and can apply a safe set of fixes to a managed static site.

## What it checks

- Uptime and HTTP status
- Response latency
- Common security headers
- HTML basics like `<title>`, meta description, `lang`, and canonical URL
- Broken internal links discovered on the page

## What it can fix

For a local static site directory that you control, WebAgent can update:

- `<title>`
- meta description
- `<html lang="...">`
- canonical URL
- `robots.txt`
- an example security-headers config snippet for a reverse proxy

## Run

Compile:

```powershell
javac -d out (Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

Start:

```powershell
java -cp out webagent.Main
```

Open:

```text
http://localhost:8080
```

## Deploy To Render

This repo includes a `Dockerfile` and `render.yaml` for Render.

Important: the app now reads Render's `PORT` environment variable and defaults to `8080` locally.

### Option 1: Blueprint

1. Push this project to GitHub.
2. In Render, click `New` -> `Blueprint`.
3. Select your repo.
4. Render will detect `render.yaml` and create the web service.
5. Add an environment variable named `WEBAGENT_ADMIN_TOKEN` with a long random secret.
6. Add `WEBAGENT_ALLOWED_HOSTS` as a comma-separated list like `example.com,www.example.com`.
7. Optionally set `WEBAGENT_RATE_LIMIT_PER_MINUTE` such as `30`.
8. After deploy, open your Render URL.

### Option 2: Manual Web Service

1. Push this project to GitHub.
2. In Render, click `New` -> `Web Service`.
3. Connect your repo.
4. Set `Language` to `Docker`.
5. Leave the Dockerfile path as `./Dockerfile`.
6. Set the health check path to `/api/health`.
7. Add an environment variable named `WEBAGENT_ADMIN_TOKEN`.
8. Add `WEBAGENT_ALLOWED_HOSTS`.
9. Optionally add `WEBAGENT_RATE_LIMIT_PER_MINUTE`.
10. Deploy.

Render will build the Docker image from the repo and run the `CMD` from the `Dockerfile`.

## Security

Sensitive endpoints now require a bearer token from the `WEBAGENT_ADMIN_TOKEN` environment variable:

- `/api/audit`
- `/api/monitor/start`
- `/api/monitor/stop`
- `/api/monitor/latest`
- `/api/fix`

Audit and monitor targets are also restricted by `WEBAGENT_ALLOWED_HOSTS`. WebAgent only audits `http` and `https` URLs with a host, and rejects URLs with embedded credentials.

Example:

```text
WEBAGENT_ALLOWED_HOSTS=example.com,www.example.com
WEBAGENT_RATE_LIMIT_PER_MINUTE=30
```

Example request:

```powershell
$headers = @{ Authorization = "Bearer your-secret-token" }
Invoke-WebRequest -Headers $headers -UseBasicParsing "http://localhost:8080/api/audit?url=https://example.com"
```

Start and stop monitoring:

```powershell
Invoke-WebRequest -Method Post -Headers $headers -UseBasicParsing "http://localhost:8080/api/monitor/start?url=https://example.com&intervalSeconds=60"
Invoke-WebRequest -Method Post -Headers $headers -UseBasicParsing "http://localhost:8080/api/monitor/stop?url=https://example.com"
```

## Notes

- The audit engine works against any reachable URL.
- Automatic fixes are intentionally limited to local files inside a site root you provide.
- This first version is best suited for static or mostly static marketing sites.
