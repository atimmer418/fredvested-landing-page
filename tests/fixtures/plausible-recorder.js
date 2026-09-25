// Served at /js/script.js during the end-to-end tests in place of the real
// Plausible script. It records every plausible() call the pages make (the exact
// boundary analytics.js talks to) on window.__plausible, and replays anything
// the inline queue snippet buffered before this file loaded. Transport to
// Plausible is the real script's job and was verified live in Phase 1; the
// suite asserts what the pages emit, not how Plausible delivers it.
(function () {
  var queued = (window.plausible && window.plausible.q) || [];
  window.__plausible = window.__plausible || [];
  window.plausible = function (name, options) {
    window.__plausible.push({ name: name, props: (options && options.props) || {}, url: location.href });
  };
  for (var i = 0; i < queued.length; i++) window.plausible.apply(null, queued[i]);
})();
