#!/usr/bin/env python3
"""Real VayDNS server/client loopback; no mocked tunnel or timing samples."""
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time

BIND = "127.0.0.1"
DOMAIN = "t.example.com"


def read_exact(conn, size):
    data = bytearray()
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            raise RuntimeError("unexpected EOF")
        data.extend(chunk)
    return bytes(data)


def reserve_port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, kind) as sock:
        sock.bind((BIND, 0))
        return sock.getsockname()[1]


def reserve_adjacent():
    while True:
        port = reserve_port()
        if port == 65535:
            continue
        try:
            with socket.socket() as sock:
                sock.bind((BIND, port + 1))
            return port
        except OSError:
            pass


class EchoServer(threading.Thread):
    def __init__(self, socks_auth=None, raw=False, timeout=20):
        super().__init__(daemon=True)
        self.socks_auth = socks_auth
        self.raw = raw
        self.timeout = timeout
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((BIND, 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.error = None

    def run(self):
        try:
            conn, _ = self.sock.accept()
            with conn:
                conn.settimeout(self.timeout)
                if not self.raw:
                    assert read_exact(conn, 4) == b"\x05\x02\x00\x02"
                    if self.socks_auth is None:
                        conn.sendall(b"\x05\x00")
                    else:
                        conn.sendall(b"\x05\x02")
                        assert read_exact(conn, 1) == b"\x01"
                        user = read_exact(conn, read_exact(conn, 1)[0])
                        password = read_exact(conn, read_exact(conn, 1)[0])
                        assert (user, password) == self.socks_auth
                        conn.sendall(b"\x01\x00")
                    header = read_exact(conn, 4)
                    assert header[:3] == b"\x05\x01\x00"
                    if header[3] == 1:
                        read_exact(conn, 4)
                    elif header[3] == 3:
                        read_exact(conn, read_exact(conn, 1)[0])
                    elif header[3] == 4:
                        read_exact(conn, 16)
                    else:
                        raise RuntimeError("bad SOCKS address")
                    read_exact(conn, 2)
                    conn.sendall(b"\x05\x00\x00\x01" + b"\x00" * 6)
                payload = read_exact(conn, 32768)
                conn.sendall(payload)
        except Exception as exc:
            self.error = exc
        finally:
            self.sock.close()


class DnsTcpProxy(threading.Thread):
    """Length-prefixed DNS/TCP adapter in front of the real UDP test server."""

    def __init__(self, upstream_port):
        super().__init__(daemon=True)
        self.upstream = (BIND, upstream_port)
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((BIND, 0))
        self.sock.listen(2)
        self.port = self.sock.getsockname()[1]
        self.stopping = False
        self.conn = None
        self.error = None

    def run(self):
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            udp.settimeout(0.25)
            self.conn, _ = self.sock.accept()
            self.conn.settimeout(120)
            while not self.stopping:
                size = int.from_bytes(read_exact(self.conn, 2), "big")
                if size == 0:
                    raise RuntimeError("empty DNS/TCP query")
                query = read_exact(self.conn, size)
                response = None
                for _ in range(40):
                    udp.sendto(query, self.upstream)
                    try:
                        response, _ = udp.recvfrom(65535)
                        break
                    except socket.timeout:
                        continue
                if response is None:
                    raise RuntimeError("UDP DNS server did not answer after retries")
                self.conn.sendall(len(response).to_bytes(2, "big") + response)
        except (RuntimeError, ConnectionError, OSError) as exc:
            if not self.stopping:
                self.error = exc
        finally:
            udp.close()
            if self.conn is not None:
                self.conn.close()

    def stop(self):
        self.stopping = True
        self.sock.close()
        if self.conn is not None:
            self.conn.close()
        self.join(2)


def wait_connect(port, process):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"client exited early with {process.returncode}")
        try:
            return socket.create_connection((BIND, port), timeout=0.2)
        except OSError:
            time.sleep(0.05)
    raise RuntimeError("client listener did not open")


def run_scenario(server_bin, client_bin, private_key, public_key, mode):
    if mode not in ("raw", "socks", "authenticated-socks", "tcp-raw"):
        raise ValueError(mode)
    authenticated = mode == "authenticated-socks"
    dns_tcp = mode == "tcp-raw"
    bridged = mode not in ("raw", "tcp-raw")
    target = EchoServer((b"user", b"pass") if authenticated else None, raw=not bridged, timeout=120 if dns_tcp else 20)
    target.start()
    udp_port = reserve_port(socket.SOCK_DGRAM)
    public_port = reserve_adjacent()
    raw_port = public_port + 1
    server_log = tempfile.TemporaryFile()
    client_log = tempfile.TemporaryFile()
    server = subprocess.Popen([
        server_bin, "-udp", f"{BIND}:{udp_port}", "-privkey-file", private_key,
        "-domain", DOMAIN, "-upstream", f"{BIND}:{target.port}",
        "-idle-timeout", "30s", "-keepalive", "2s", "-log-level", "debug",
    ], stdout=server_log, stderr=subprocess.STDOUT)
    dns_proxy = DnsTcpProxy(udp_port) if dns_tcp else None
    if dns_proxy is not None:
        dns_proxy.start()
    resolver = f"{BIND}:{dns_proxy.port if dns_proxy is not None else udp_port}"
    env = os.environ.copy()
    if authenticated:
        env.update(OMNI_DNSTT_SOCKS_USER="user", OMNI_DNSTT_SOCKS_PASSWORD="pass")
    else:
        env.pop("OMNI_DNSTT_SOCKS_USER", None)
        env.pop("OMNI_DNSTT_SOCKS_PASSWORD", None)
    client_args = [
        client_bin, "-listen", f"{BIND}:{raw_port}",
        "-tcp" if dns_tcp else "-udp", resolver, "-domain", DOMAIN,
        "-pubkey", public_key, "-max-payload", "0",
        "-dnstt-compat=false", "-record-type", "txt", "-max-qname-len", "101",
        "-rps", "0", "-idle-timeout", "0", "-keep-alive", "0",
        "-udp-timeout", "60000", "-max-num-labels", "3", "-clientid-size", "2",
    ]
    if bridged:
        client_args += ["-socks-listen", f"{BIND}:{public_port}"]
    else:
        client_args += ["-raw-tcp"]
    client = subprocess.Popen(client_args, stdout=client_log, stderr=subprocess.STDOUT, env=env)
    try:
        with wait_connect(public_port if bridged else raw_port, client) as conn:
            conn.settimeout(120 if dns_tcp else 25)
            if bridged:
                conn.sendall(b"\x05\x01\x00")
                assert read_exact(conn, 2) == b"\x05\x00"
                host = b"example.com"
                conn.sendall(b"\x05\x01\x00\x03" + bytes([len(host)]) + host + b"\x00\x50")
                response = read_exact(conn, 10)
                assert response[:2] == b"\x05\x00", response
            payload = os.urandom(32768)
            conn.sendall(payload)
            echoed = read_exact(conn, len(payload))
            if echoed != payload:
                mismatch = next((i for i, (left, right) in enumerate(zip(payload, echoed)) if left != right), -1)
                raise AssertionError(f"echo mismatch at {mismatch}: expected {payload[mismatch:mismatch + 16].hex()}, got {echoed[mismatch:mismatch + 16].hex()}")
        target.join(130 if dns_tcp else 20)
        if target.is_alive() or target.error:
            raise RuntimeError(f"target failed: {target.error}")
        if dns_proxy is not None and dns_proxy.error:
            raise RuntimeError(f"DNS/TCP adapter failed: {dns_proxy.error}")
    except Exception:
        for name, log in (("server", server_log), ("client", client_log)):
            log.flush()
            log.seek(0)
            sys.stderr.write(f"\n--- {name} ({mode=}) ---\n" + log.read().decode(errors="replace"))
        if dns_proxy is not None and dns_proxy.error:
            sys.stderr.write(f"\n--- DNS/TCP adapter ---\n{dns_proxy.error}\n")
        raise
    finally:
        if dns_proxy is not None:
            dns_proxy.stop()
        for process in (client, server):
            process.terminate()
        for process in (client, server):
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        server_log.close()
        client_log.close()


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: omni_loopback.py SERVER_BIN CLIENT_BIN PRIVATE_KEY_FILE PUBLIC_KEY_HEX")
    server_bin, client_bin, private_key, public_key = sys.argv[1:]
    for mode in ("raw", "socks", "authenticated-socks", "tcp-raw"):
        run_scenario(server_bin, client_bin, private_key, public_key, mode)
    print("VAYDNS_LOOPBACK_OK udp/raw+noauth-socks+authenticated-socks and tcp/raw")


if __name__ == "__main__":
    main()
