#!/usr/bin/env python3
"""Local static server for the frontend (npm run serve).

Same as `python3 -m http.server` but:
- sends Cache-Control: no-store, so the browser always picks up edited JS/CSS
  instead of a heuristically cached copy
- binds 0.0.0.0, so a phone on the same wifi can open the page via this
  machine's LAN IP (printed below)
"""
import os
import socket
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

PORT = 5500


class NoCacheHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def translate_path(self, path):
        # Clean URLs, as Cloudflare Pages serves them: /privacy -> privacy.html.
        # Lets the e2e suite request the paths the site links to.
        full = super().translate_path(path)
        if not os.path.exists(full) and not os.path.splitext(full)[1]:
            candidate = full.rstrip("/") + ".html"
            if os.path.isfile(candidate):
                return candidate
        return full


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # UDP connect sends no packets; it just resolves the outbound interface
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


if __name__ == "__main__":
    print(f"Serving frontend at http://127.0.0.1:{PORT}/")
    ip = lan_ip()
    if ip:
        print(f"On your phone (same wifi): http://{ip}:{PORT}/")
    print("Ctrl-C to stop")
    ThreadingHTTPServer(("0.0.0.0", PORT), NoCacheHandler).serve_forever()
