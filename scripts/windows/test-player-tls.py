#!/usr/bin/env python3
"""Local HTTPS regression using the production Windows bridge and packaged runtime.

No provider credentials, external servers, administrator access or machine trust-store changes.
The fixture CA is appended only to a temporary copy of the shipped bundle.
"""
import argparse
import contextlib
import http.server
import io
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import wave


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", required=True, type=Path)
    parser.add_argument("--runtime", required=True, type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    bundle = runtime / "cacert.pem"
    assert bundle.is_file(), "The prepared Windows runtime must contain cacert.pem"
    assert "cacert.pem" in (runtime / "runtime-files.txt").read_text().splitlines(), "CA bundle missing from extraction index"
    assert ssl.create_default_context(cafile=str(bundle)).cert_store_stats()["x509_ca"] > 100
    openssl = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
    with tempfile.TemporaryDirectory(prefix="tuvora-tls-") as temporary:
        root = Path(temporary)

        def command(*argv):
            subprocess.run([openssl, *argv], cwd=root, check=True, capture_output=True, timeout=30)

        command("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem",
                "-days", "2", "-subj", "/CN=Tuvora TLS test CA", "-addext", "basicConstraints=critical,CA:TRUE")
        for name, hostname in [("valid", "localhost"), ("wrong-host", "different.invalid")]:
            command("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", name + ".key",
                    "-out", name + ".csr", "-subj", "/CN=" + hostname)
            (root / (name + ".ext")).write_text("subjectAltName=DNS:" + hostname + "\nextendedKeyUsage=serverAuth\n")
            command("x509", "-req", "-in", name + ".csr", "-CA", "ca.pem", "-CAkey", "ca.key",
                    "-CAcreateserial", "-out", name + ".pem", "-days", "2", "-extfile", name + ".ext")
        command("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "untrusted.key", "-out", "untrusted.pem",
                "-days", "2", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost")
        audio = io.BytesIO()
        with wave.open(audio, "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8000)
            output.writeframes(b"\0\0" * 800)

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(audio.getvalue())))
                self.end_headers()
                self.wfile.write(audio.getvalue())

            def log_message(self, *_):
                pass

        @contextlib.contextmanager
        def server(certificate):
            httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(root / (certificate + ".pem"), root / (certificate + ".key"))
            httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                yield f"https://localhost:{httpd.server_port}/sample.wav"
            finally:
                httpd.shutdown()
                httpd.server_close()
                thread.join(timeout=5)

        # Use a location unrelated to the working directory, with spaces and non-ASCII text.
        install = root / "Tuvora test é 漢字" / "native"
        install.mkdir(parents=True)
        exe = install / "tls_smoke.exe"
        shutil.copy2(args.exe, exe)
        for dll in runtime.glob("*.dll"):
            shutil.copy2(dll, install / dll.name)
        local_bundle = install / "cacert.pem"
        local_bundle.write_bytes(bundle.read_bytes() + b"\n" + (root / "ca.pem").read_bytes())
        environment = dict(os.environ, NUVIO_LIBMPV_PATH=str(install / "libmpv-2.dll"))

        def check(name, mode, url, expected):
            result = subprocess.run([str(exe), mode, url], cwd=root, env=environment,
                                    capture_output=True, text=True, timeout=25)
            assert result.returncode == expected, (name, result.returncode, result.stdout, result.stderr)
            print("PASS:", name, flush=True)

        with server("valid") as url:
            check("old setup rejects a trusted chain without explicit CA path", "baseline", url, 1)
            check("production setup plays a trusted chain from a Unicode install path", "strict", url, 0)
            # The ordinary shipped roots must not trust the test CA.
            local_bundle.write_bytes(bundle.read_bytes())
            check("unrecognized issuer remains rejected", "strict", url, 1)
            local_bundle.write_bytes(bundle.read_bytes() + b"\n" + (root / "ca.pem").read_bytes())
        with server("wrong-host") as url:
            check("trusted issuer with wrong hostname remains rejected", "strict", url, 1)
        with server("untrusted") as url:
            check("self-signed stream remains rejected", "strict", url, 1)
            check("explicit per-source panel exception is preserved", "panel", url, 0)
        local_bundle.unlink()
        with server("valid") as url:
            check("missing bundle fails explicitly rather than disabling verification", "strict", url, 2)
        print("All 7 Windows TLS regressions passed")


if __name__ == "__main__":
    main()
