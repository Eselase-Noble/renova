# Renova website

The public site: what Renova is, downloads, and a short guide to the desktop app, the CLI, the IDE plugins and
the web console. Plain HTML, one stylesheet and one script; there is nothing to build.

| File | What it is |
|---|---|
| `index.html` | Product page |
| `download.html` | Installers and the other downloads |
| `docs.html` | Getting started |
| `styles.css` | Every style. Light is the default theme; dark is used when a visitor switches to it |
| `site.js` | The theme switch, and download links read from the latest GitHub release |

Look at it locally with `python3 -m http.server -d website 8000` and open http://localhost:8000.

It is published to GitHub Pages by `.github/workflows/website.yml` whenever this folder changes on `main`.
GitHub accepts deployments only from the branches listed under **Settings → Environments → github-pages**,
which is `main` unless you add another. Switch Pages on once in the repository: **Settings → Pages → Source: GitHub Actions**. For
your own domain, set it there and add a `CNAME` file to this folder with the domain in it.

Download buttons ask GitHub for the latest release when the page opens, so a new release needs no change here.
Without JavaScript, or if GitHub cannot be reached, they lead to the releases page.

The pages are written out in full rather than generated: the header and footer are repeated in each, so a
change to either is made in all three.
