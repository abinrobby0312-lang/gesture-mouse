// Site configuration — the only file to edit when setting things up.
window.GM_CONFIG = {
  // GitHub repo whose *published* releases carry GestureMouse.apk. Until one
  // is published the download area invites people to join the list instead.
  repo: "abinrobby0312-lang/gesture-mouse",

  // Oldest release the site will offer. v1.0.0 and v1.1.0 are still published
  // on GitHub but predate the connection fixes; below this version the page
  // treats it as "no release yet" rather than linking a build that won't connect.
  minVersion: "1.7.0",

  // Supabase project for sign-ups, compatibility reports and messages (see
  // site/supabase/schema.sql). Leave empty and the forms say "opening soon"
  // rather than failing. The anon key is safe to publish: the schema lets it
  // insert rows and nothing else.
  supabaseUrl: "",
  supabaseAnonKey: "",
};
