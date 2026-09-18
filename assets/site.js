// Gesture Mouse — landing page behaviour. No libraries, no trackers.
(function () {
  "use strict";
  const cfg = window.GM_CONFIG || {};
  const $ = (sel, root = document) => root.querySelector(sel);
  const ua = navigator.userAgent;
  const isAndroid = /Android/i.test(ua);
  const isIOS = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && "ontouchend" in document);
  if (!isAndroid && !isIOS) document.documentElement.classList.add("is-desktop");

  // ---------------------------------------------------------------- download
  // The newest *published* GitHub release decides what the panel offers. A
  // draft or no release at all means "beta list" rather than a dead link.
  async function renderDownload() {
    const box = $("#download-state");
    if (!box) return;
    if (isIOS) {
      box.innerHTML = `<p class="status-note">Gesture Mouse is for <strong>Android</strong> phones — iPhones don't allow an app to act as a Bluetooth mouse.</p>
        <a class="btn" href="#updates">Tell me about other platforms</a>`;
      return;
    }
    let rel = null;
    try {
      const r = await fetch(`https://api.github.com/repos/${cfg.repo}/releases/latest`,
        { headers: { Accept: "application/vnd.github+json" } });
      if (r.ok) rel = await r.json();
    } catch (_) { /* offline or rate-limited: fall through to the list */ }

    const recent = rel && !older(rel.tag_name, cfg.minVersion || "0");
    const apk = recent && (rel.assets || []).find(a => /\.apk$/i.test(a.name));
    if (!apk) {
      box.innerHTML = `<p class="status-note">The public beta is opening soon.</p>
        <a class="btn primary" href="#updates">Join the list — get the download link first</a>`;
      return;
    }
    const mb = (apk.size / 1048576).toFixed(0);
    const sha = ((rel.body || "").match(/\b[0-9a-f]{64}\b/i) || [])[0];
    box.innerHTML = `
      <a class="btn primary" href="${apk.browser_download_url}" data-dl>Download for Android</a>
      <div class="meta">Version ${escapeHtml(rel.tag_name.replace(/^v/, ""))} · ${mb} MB · Android 9 or newer
        · <a href="${rel.html_url}">What's new</a>
        ${sha ? `<br>SHA-256 <code>${sha}</code>` : ""}</div>`;
  }

  // ------------------------------------------------------------------- forms
  const backend = cfg.supabaseUrl && cfg.supabaseAnonKey;

  async function insert(table, row) {
    const r = await fetch(`${cfg.supabaseUrl}/rest/v1/${table}`, {
      method: "POST",
      headers: {
        apikey: cfg.supabaseAnonKey,
        Authorization: `Bearer ${cfg.supabaseAnonKey}`,
        "Content-Type": "application/json",
        Prefer: "return=minimal",
      },
      body: JSON.stringify(row),
    });
    if (r.status === 409) return "duplicate";
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return "ok";
  }

  /**
   * Wires a form to a table. `build` turns the form into a row (or throws a
   * message for the person). Honeypot and a minimum time-on-page keep the
   * simplest bots out without a CAPTCHA.
   */
  function wire(form, table, build, messages) {
    if (!form) return;
    const msg = $(".form-msg", form);
    const btn = $("button[type=submit]", form);
    const openedAt = Date.now();
    if (!backend) {
      btn.disabled = true;
      msg.textContent = "Opening soon.";
      return;
    }
    form.addEventListener("submit", async e => {
      e.preventDefault();
      msg.className = "form-msg";
      if ($(".hp input", form)?.value || Date.now() - openedAt < 2500) {
        msg.textContent = messages.ok; msg.classList.add("ok"); return;   // quietly drop bots
      }
      let row;
      try { row = build(new FormData(form)); }
      catch (err) { msg.textContent = err.message; msg.classList.add("err"); return; }
      btn.disabled = true;
      try {
        const res = await insert(table, row);
        msg.textContent = res === "duplicate" ? messages.duplicate : messages.ok;
        msg.classList.add("ok");
        form.reset();
      } catch (_) {
        msg.textContent = "That didn't go through — please try again in a moment.";
        msg.classList.add("err");
      } finally { btn.disabled = false; }
    });
  }

  const clip = (v, n) => String(v || "").trim().slice(0, n);
  const emailOk = v => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v) && v.length <= 254;

  wire($("#signup-form"), "signups", fd => {
    const email = clip(fd.get("email"), 254).toLowerCase();
    if (!emailOk(email)) throw new Error("That email address doesn't look right.");
    if (!fd.get("consent")) throw new Error("Please tick the box so we're allowed to email you.");
    return { email, consent: true, source: clip(new URLSearchParams(location.search).get("src") || "site", 40) };
  }, { ok: "You're on the list — we'll email you when there's something new.",
       duplicate: "You're already on the list." });

  wire($("#contact-form"), "feedback", fd => {
    const message = clip(fd.get("message"), 2000);
    if (message.length < 3) throw new Error("Please write a message.");
    const email = clip(fd.get("email"), 254).toLowerCase();
    if (email && !emailOk(email)) throw new Error("That email address doesn't look right.");
    return { message, email: email || null };
  }, { ok: "Thanks — message received.", duplicate: "Thanks — message received." });

  wire($("#report-form"), "compat_reports", fd => {
    const row = {
      phone_make: clip(fd.get("phone_make"), 60),
      phone_model: clip(fd.get("phone_model"), 80),
      android_version: clip(fd.get("android_version"), 20) || null,
      app_version: clip(fd.get("app_version"), 20) || null,
      host_os: clip(fd.get("host_os"), 20),
      result: clip(fd.get("result"), 10),
      notes: clip(fd.get("notes"), 1000) || null,
    };
    if (!row.phone_make || !row.phone_model) throw new Error("Please fill in your phone's make and model.");
    if (!row.host_os) throw new Error("Please pick the computer you connected to.");
    if (!row.result) throw new Error("Please say whether it worked.");
    return row;
  }, { ok: "Thank you — this helps other people with the same phone.",
       duplicate: "Thank you — this helps other people with the same phone." });

  // The app opens report.html with its phone details in the query string, so
  // people only have to say how it went.
  const report = $("#report-form");
  if (report) {
    const q = new URLSearchParams(location.search);
    for (const k of ["phone_make", "phone_model", "android_version", "app_version"]) {
      const v = q.get(k);
      if (v && report.elements[k]) report.elements[k].value = clip(v, 80);
    }
  }

  // ---------------------------------------------------- compatibility list
  // A curated file, not live reports: anyone can submit a report, so what's
  // shown publicly is reviewed first.
  async function renderCompat() {
    const body = $("#compat-body");
    if (!body) return;
    try {
      const rows = await (await fetch("assets/compat.json", { cache: "no-cache" })).json();
      body.innerHTML = rows.map(r => `<tr>
        <td>${escapeHtml(r.phone)}</td><td>${escapeHtml(r.android)}</td>
        <td>${escapeHtml(r.computer)}</td>
        <td><span class="pill ${r.result}">${{ works: "Works", partly: "Partly", no: "Doesn't work" }[r.result] || ""}</span></td>
        <td class="dim">${escapeHtml(r.notes || "")}</td></tr>`).join("");
    } catch (_) {
      body.innerHTML = `<tr><td colspan="5" class="dim">Couldn't load the list.</td></tr>`;
    }
  }

  /** "v1.1.0" older than "1.7.0"? Numeric, part by part. */
  function older(tag, min) {
    const a = String(tag).replace(/^v/, "").split(".").map(Number);
    const b = String(min).replace(/^v/, "").split(".").map(Number);
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      const x = a[i] || 0, y = b[i] || 0;
      if (x !== y) return x < y;
    }
    return false;
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  const year = $("#year"); if (year) year.textContent = new Date().getFullYear();
  renderDownload();
  renderCompat();
})();
