// Renova website: the theme switch, and download links read from the latest GitHub release so that they
// never point at an old installer. Every link works without JavaScript too: it then leads to the releases page.

const REPO = "Eselase-Noble/renova";

// ---- Theme: the system's until the visitor chooses, then theirs on this device.
(function theme() {
  let saved = null;
  try { saved = localStorage.getItem("renova-theme"); } catch { /* private window: follow the system */ }
  if (saved === "light" || saved === "dark") document.documentElement.dataset.theme = saved;
  document.addEventListener("click", (event) => {
    const button = event.target.closest("[data-theme-toggle]");
    if (!button) return;
    const dark = document.documentElement.dataset.theme
      ? document.documentElement.dataset.theme === "dark"
      : matchMedia("(prefers-color-scheme: dark)").matches;
    const next = dark ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem("renova-theme", next); } catch { /* not remembered, still applied */ }
  });
})();

// ---- Downloads.
// Which release file is which: matched by name, because the version is part of it.
const KINDS = {
  windows: { match: /\.msi$/i, label: "Windows", note: "Installer (.msi), 64-bit" },
  mac: { match: /\.dmg$/i, label: "macOS", note: "Disk image (.dmg)" },
  linux: { match: /\.deb$/i, label: "Linux", note: "Debian and Ubuntu package (.deb)" },
  cli: { match: /^renova-cli-.*\.zip$/i },
  vscode: { match: /\.vsix$/i },
  intellij: { match: /^renova-intellij-.*\.zip$/i },
  web: { match: /^renova-web-.*\.tar\.gz$/i },
  sums: { match: /^SHA256SUMS\.txt$/i },
};

function platform() {
  const ua = `${navigator.userAgentData?.platform ?? ""} ${navigator.platform ?? ""} ${navigator.userAgent}`.toLowerCase();
  if (ua.includes("win")) return "windows";
  if (ua.includes("mac") || ua.includes("iphone") || ua.includes("ipad")) return "mac";
  if (ua.includes("linux") || ua.includes("x11")) return "linux";
  return null;
}

function megabytes(bytes) {
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

async function downloads() {
  const targets = document.querySelectorAll("[data-download], [data-release-version], [data-download-os]");
  if (targets.length === 0) return;

  const mine = platform();
  // Before the release is known: the visitor's own system is named, and links lead to the releases page.
  document.querySelectorAll("[data-download-os]").forEach((el) => {
    if (mine) el.textContent = KINDS[mine].label;
  });

  let release;
  try {
    const response = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: "application/vnd.github+json" } });
    if (!response.ok) return;
    release = await response.json();
  } catch {
    return; // offline, or the API's hourly limit: the links to the releases page stay
  }

  const files = {};
  for (const asset of release.assets ?? []) {
    for (const [kind, { match }] of Object.entries(KINDS)) {
      if (match.test(asset.name)) files[kind] = asset;
    }
  }
  const version = (release.tag_name ?? "").replace(/^v/, "");
  document.querySelectorAll("[data-release-version]").forEach((el) => { el.textContent = version; });
  document.querySelectorAll("[data-release-date]").forEach((el) => {
    el.textContent = new Date(release.published_at).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  });

  document.querySelectorAll("[data-download]").forEach((el) => {
    const kind = el.dataset.download === "auto" ? mine : el.dataset.download;
    const asset = kind && files[kind];
    if (!asset) return;
    el.href = asset.browser_download_url;
    const size = el.closest("[data-download-row]")?.querySelector("[data-size]") ?? document.querySelector(`[data-size-for="${el.dataset.download}"]`);
    if (size) size.textContent = megabytes(asset.size);
    const name = document.querySelector(`[data-file-for="${el.dataset.download}"]`);
    if (name) name.textContent = `${asset.name} · ${megabytes(asset.size)}`;
  });
  document.querySelectorAll("[data-download-note]").forEach((el) => {
    if (mine) el.textContent = KINDS[mine].note;
  });
}

downloads();

// ---- Copy buttons on commands.
document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-copy]");
  if (!button) return;
  const text = document.querySelector(button.dataset.copy)?.innerText ?? "";
  try {
    await navigator.clipboard.writeText(text.replace(/^\$ /gm, ""));
    const label = button.textContent;
    button.textContent = "Copied";
    setTimeout(() => { button.textContent = label; }, 1400);
  } catch { /* the text can still be selected by hand */ }
});
