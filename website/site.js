// Renova website: the theme switch, and download links read from the latest GitHub release so that they
// never point at an old installer. Every link works without JavaScript too: it then leads to the releases page.

const REPO = "Eselase-Noble/renova";

// ---- Theme: light, until the visitor switches; their choice is kept on this device.
(function theme() {
  const root = document.documentElement;
  document.addEventListener("click", (event) => {
    if (!event.target.closest("[data-theme-toggle]")) return;
    const dark = root.dataset.theme !== "dark";
    if (dark) root.dataset.theme = "dark";
    else delete root.dataset.theme;
    try { localStorage.setItem("renova-theme", dark ? "dark" : "light"); } catch { /* not remembered, still applied */ }
  });
})();

// ---- Downloads.
// Which release file is which: matched by name, because the version is part of it.
const KINDS = {
  windows: { match: /\.msi$/i, label: "Windows", note: "Installer (.msi), 64-bit" },
  // Before installers were named for their chip there was one .dmg, for Apple Silicon.
  mac: { match: /^(?!.*-x64)(.*)\.dmg$/i, label: "macOS", note: "Disk image (.dmg) for Apple Silicon" },
  macintel: { match: /-x64\.dmg$/i, label: "macOS", note: "Disk image (.dmg) for Intel Macs" },
  linux: { match: /\.deb$/i, label: "Linux", note: "Debian and Ubuntu package (.deb)" },
  rpm: { match: /\.rpm$/i },
  arch: { match: /\.pkg\.tar\.zst$/i },
  targz: { match: /linux.*\.tar\.gz$/i },
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

// Which chip a Mac has. Chromium browsers say; Safari and Firefox do not, and then Apple Silicon is assumed,
// which every Mac sold since 2020 has. Both installers are always listed.
async function intelMac() {
  try {
    const hints = await navigator.userAgentData?.getHighEntropyValues?.(["architecture"]);
    return hints?.architecture === "x86";
  } catch {
    return false;
  }
}

async function downloads() {
  const targets = document.querySelectorAll("[data-download], [data-download-os]");
  if (targets.length === 0) return;

  let mine = platform();
  const intel = mine === "mac" && await intelMac();
  if (intel) mine = "macintel";
  document.querySelectorAll("[data-mac-only]").forEach((el) => { el.hidden = !(mine === "mac" || mine === "macintel"); });
  if (intel) {
    document.querySelectorAll("[data-mac-chip]").forEach((el) => { el.textContent = "Intel"; });
    document.querySelectorAll("[data-mac-other]").forEach((el) => { el.textContent = "an Apple Silicon (M-series)"; });
    document.querySelectorAll("[data-mac-other-link]").forEach((el) => { el.dataset.download = "mac"; });
  }
  // Before the release is known: the visitor's own system is named, and links lead to the releases page.
  document.querySelectorAll("[data-download-os]").forEach((el) => {
    if (mine) el.textContent = KINDS[mine].label;
  });
  // Linux comes in several package formats; the others are offered beside the default.
  document.querySelectorAll("[data-linux-only]").forEach((el) => { el.hidden = mine !== "linux"; });

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
  document.querySelectorAll("[data-download]").forEach((el) => {
    const kind = el.dataset.download === "auto" ? mine : el.dataset.download;
    const asset = kind && files[kind];
    if (!asset) return;
    el.href = asset.browser_download_url;
    const size = el.closest("[data-download-row]")?.querySelector("[data-size]") ?? document.querySelector(`[data-size-for="${el.dataset.download}"]`);
    if (size) size.textContent = megabytes(asset.size);
    const name = document.querySelector(`[data-file-for="${el.dataset.download}"]`);
    if (name) name.textContent = `${megabytes(asset.size)} · Free to install. Assessing projects needs no licence.`;
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
