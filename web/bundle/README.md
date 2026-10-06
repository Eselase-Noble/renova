# Renova Web

The Renova web API and console, ready to run. Nothing here is a hosted service: projects and every migrated copy
stay on the machine this runs on. Only AI requests leave it, to the provider and on the key you set.

**Needs:** Java 21 or later, Node.js 20 or later, Git, and Maven (to build the projects being migrated).
Docker is needed only for behavioural verification.

## One person, on their own machine

```sh
bin/renova-web local
```

No sign-in. Renova listens on `127.0.0.1` only and keeps its data in `~/.renova/local`.

## A team, on a server inside their network

Try it first:

```sh
bin/renova-web server --project-roots /srv/projects
```

Then install it as services so it starts with the machine:

1. Unpack this folder to `/opt/renova-web` and create a `renova` user that can read the projects.
2. Copy `systemd/renova-api.service` and `systemd/renova-console.service` to `/etc/systemd/system/`; adjust the
   paths, the data directory (`/var/lib/renova`, owned by `renova`) and `--renova.project-roots`.
3. `systemctl daemon-reload && systemctl enable --now renova-api renova-console`
4. Put the console behind HTTPS with a reverse proxy; the API is never exposed directly. For nginx:

   ```nginx
   server {
       listen 443 ssl;
       server_name renova.example.internal;
       ssl_certificate     /etc/ssl/renova.crt;
       ssl_certificate_key /etc/ssl/renova.key;
       location / {
           proxy_pass http://127.0.0.1:3000;
           proxy_set_header Host $host;
           proxy_set_header X-Forwarded-Proto https;
           proxy_read_timeout 300s;
       }
   }
   ```
5. Open the site and set up the first account and organisation. Everyone else joins by invitation.

## Settings

| Setting | How | Default |
|---|---|---|
| Data directory | `--renova.data-dir=DIR` | `~/.renova/server` (`~/.renova/local` in local mode) |
| Folders projects may be added from | `--renova.project-roots=A,B` | the home directory of the account the API runs as |
| Migrations at a time | `--renova.parallel-migrations=N` | 1 |
| Key that encrypts organisations' AI keys | `RENOVA_MASTER_KEY` (base64, 32 bytes) | `master.key` in the data directory, created on first start |
| Session cookie only over HTTPS | `--server.servlet.session.cookie.secure=true` | false; set it behind HTTPS |
| Where the console finds the API | `RENOVA_API_URL` | `http://127.0.0.1:8787` |

## Back up and upgrade

- **Back up** the data directory. Keep `master.key` (or `RENOVA_MASTER_KEY`) apart from that backup: together they
  hold the organisations' AI keys and what decrypts them.
- **Upgrade:** stop the services, replace `api/` and `console/` with the new release's, start them again. The data
  directory is left as it is.
