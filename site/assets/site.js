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
  // assets/release.json, same origin, decides what the panel offers. Missing
  // or without a version means "beta list" rather than a dead link.
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
      const r = await fetch("assets/release.json", { cache: "no-cache" });
      if (r.ok) rel = await r.json();
    } catch (_) { /* offline: fall through to the list */ }

    if (!rel || !rel.version || !rel.url) {
      box.innerHTML = `<p class="status-note">The public beta is opening soon.</p>
        <a class="btn primary" href="#updates">Join the list — get the download link first</a>`;
      return;
    }
    const mb = rel.size ? ` · ${(rel.size / 1048576).toFixed(0)} MB` : "";
    box.innerHTML = `
      <a class="btn primary" href="${escapeHtml(rel.url)}">Download for Android</a>
      <div class="meta">Version ${escapeHtml(rel.version)}${mb} · Android 9 or newer
        ${rel.notes ? `· <a href="${escapeHtml(rel.notes)}">What's new</a>` : ""}</div>`;
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

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // ------------------------------------------------------ install demo
  // The four install steps drive a CSS phone (#demo, data-step 1–4). It plays
  // through them on its own while on screen, and stops for good the moment
  // someone taps a step or touches the phone — then it's theirs to explore.
  function setupDemo() {
    const phone = $("#demo");
    if (!phone) return;
    const steps = [...document.querySelectorAll(".steps .step")];
    const cap = $("#demo-cap");
    const pad = $("#demo-pad"), touch = $(".touch", pad), kb = $("#demo-kb"), scr = $(".scr", phone);
    const captions = {
      1: "Step 1 — download on your phone",
      2: "Step 2 — allow your browser to install it",
      3: "Step 3 — open the app and allow Bluetooth",
      4: "Step 4 — connected. Try it: drag on the trackpad",
    };
    const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;

    function show(n) {
      phone.dataset.step = String(n);
      steps.forEach(b => {
        const on = b.dataset.step === String(n);
        b.classList.toggle("active", on);
        b.closest("li").classList.toggle("active", on);
        b.setAttribute("aria-pressed", on ? "true" : "false");
      });
      if (cap) cap.textContent = captions[n];
      kb.tabIndex = n === 4 ? 0 : -1;
      if (n !== 4) { scr.classList.remove("kb-open"); kb.setAttribute("aria-pressed", "false"); }
    }

    // autoplay: 1 → 4, hold, repeat — only while visible, never after input
    let timer = null, current = 1, visible = false, stopped = reduce;
    const dwell = n => (n === 4 ? 6000 : 3200);
    function tick() {
      if (stopped || !visible) return;
      current = current % 4 + 1;
      show(current);
      timer = setTimeout(tick, dwell(current));
    }
    function stop() { stopped = true; clearTimeout(timer); }
    new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      clearTimeout(timer);
      if (visible && !stopped) timer = setTimeout(tick, dwell(current));
    }, { threshold: 0.4 }).observe(phone);

    steps.forEach(b => b.addEventListener("click", () => {
      stop(); current = Number(b.dataset.step); show(current);
      // on phones the demo sits below the steps; a tap should show its result
      const r = phone.getBoundingClientRect();
      if (r.top < 0 || r.bottom > innerHeight) {
        phone.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "center" });
      }
    }));
    phone.addEventListener("pointerdown", stop);

    // step 4: the trackpad follows a finger, mouse or pen
    function place(e) {
      const r = pad.getBoundingClientRect();
      const x = Math.min(Math.max(e.clientX - r.left, 0), r.width);
      const y = Math.min(Math.max(e.clientY - r.top, 0), r.height);
      touch.style.left = `${(x / r.width) * 100}%`;
      touch.style.top = `${(y / r.height) * 100}%`;
    }
    pad.addEventListener("pointerdown", e => {
      if (phone.dataset.step !== "4" || e.target === kb) return;
      pad.setPointerCapture(e.pointerId);
      pad.classList.add("dragging", "used");
      place(e);
    });
    pad.addEventListener("pointermove", e => { if (pad.classList.contains("dragging")) place(e); });
    ["pointerup", "pointercancel"].forEach(t => pad.addEventListener(t, () => pad.classList.remove("dragging")));

    // step 4: the keyboard button
    kb.addEventListener("click", () => {
      if (phone.dataset.step !== "4") return;
      const open = scr.classList.toggle("kb-open");
      kb.setAttribute("aria-pressed", open ? "true" : "false");
      kb.setAttribute("aria-label", open ? "Hide keyboard" : "Show keyboard");
    });

    show(1);
  }

  const year = $("#year"); if (year) year.textContent = new Date().getFullYear();
  setupDemo();
  renderDownload();
  renderCompat();
})();
