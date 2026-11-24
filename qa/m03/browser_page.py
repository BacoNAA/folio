"""为 M03 设备验收提供本机页面，配合 adb reverse tcp:8765 tcp:8765 使用。"""

from http.server import BaseHTTPRequestHandler, HTTPServer


class FilterGuide(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/filter?part=42":
            self.send_error(404)
            return
        # 片段留在浏览器端。只有打开 #guide 时，验收用例才能读到这段文字。
        page = b"""<!doctype html><meta charset="utf-8"><title>Filter guide</title>
<style>#guide:not(:target) { display: none }</style>
<p id="guide">FOLIO-M03-FILTER-42</p>"""
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(page)))
        self.end_headers()
        self.wfile.write(page)


if __name__ == "__main__":
    with HTTPServer(("127.0.0.1", 8765), FilterGuide) as server:
        server.serve_forever()
