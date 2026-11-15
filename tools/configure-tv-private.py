"""Import a private IPTV source through Sokoul's existing shell-only loopback diagnostic."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import subprocess
import threading
import uuid

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--source-file', type=Path, required=True)
    parser.add_argument('--user', type=int, default=0)
    args = parser.parse_args()
    sources = json.loads(args.source_file.read_text())
    if len(sources) != 1:
        raise SystemExit('Expected exactly one authorized source; no source was imported.')
    body = json.dumps(sources[0]).encode()
    route = '/' + uuid.uuid4().hex
    delivered = threading.Event()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_GET(self):
            if self.path != route:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            delivered.set()
    server = HTTPServer(('127.0.0.1', 0), Handler)
    port = str(server.server_address[1])
    def adb(*cmd):
        return subprocess.run([args.adb, '-s', args.device, *cmd], capture_output=True, check=True, timeout=30)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    try:
        adb('reverse', 'tcp:' + port, 'tcp:' + port)
        thread.start()
        adb('shell', 'am', 'broadcast', '--user', str(args.user), '-a', 'com.sokoul.tv.GUIDE_DIAGNOSTICS',
            '-p', 'com.sokoul.tv', '--es', 'restoreUrl', 'http://127.0.0.1:' + port + route)
        if not delivered.wait(25):
            raise SystemExit('The owner-profile app did not request the source. Nothing was logged.')
        print('Private source delivered. Check GUIDE_DIAGNOSTICS for synchronization status.')
    finally:
        server.shutdown() if thread.is_alive() else None
        server.server_close()
        adb('reverse', '--remove', 'tcp:' + port)

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        raise SystemExit('Private setup failed; credential values were not logged.')
