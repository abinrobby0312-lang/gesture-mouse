// Site configuration. (The offered app version lives in assets/release.json.)
window.GM_CONFIG = {
  // The download panel reads assets/release.json (version, size, SHA-256,
  // link), updated with each release — not GitHub's API, which is rate-limited
  // per IP (mobile carriers share IPs across thousands of users) and blocked
  // by some ad blockers. No release.json, or "version": null, shows the
  // beta-list signup instead of a download.

  // Supabase project for sign-ups, compatibility reports and messages (see
  // site/supabase/schema.sql). Leave empty and the forms say "opening soon"
  // rather than failing. The publishable key is safe to publish: the schema
  // lets it insert rows and nothing else (checked: reads, updates, deletes and
  // setting "reviewed" are all refused).
  supabaseUrl: "https://ruhwscwgveyzizpruxyf.supabase.co",
  supabaseAnonKey: "sb_publishable_DJY6ZHam2TEsLjbjf19M-w_fqKVSfqu",
};
