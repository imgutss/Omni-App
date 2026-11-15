#!/usr/bin/env python3
import os, selectors, socket, subprocess, threading, time, signal

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.environ.get('OMNI_NOIZDNS_CLIENT', '/tmp/omni-noizdns-host')
SERVER = os.environ.get('OMNI_NOIZDNS_SERVER' if os.environ.get('NOIZ') else 'OMNI_DNSTT_SERVER', '/tmp/omni-noizdns-server' if os.environ.get('NOIZ') else '/tmp/omni-dnstt-server')
NOIZ = bool(os.environ.get('NOIZ'))
NONAUTH = bool(os.environ.get('NONAUTH'))
MIXED = bool(os.environ.get('MIXED'))
STEALTH = bool(os.environ.get('STEALTH'))
RAW = bool(os.environ.get('RAW'))
KEY = os.environ.get('OMNI_LOOP_KEY', '/tmp/omni-loop.key')
PUB = open(os.environ.get('OMNI_LOOP_PUB', '/tmp/omni-loop.pub')).read().strip()
LOG_DIR = os.environ.get('OMNI_LOOP_LOG_DIR', '/tmp')
children = []
threads = []
stop = threading.Event()

def free_port(kind=socket.SOCK_STREAM):
    s = socket.socket(socket.AF_INET, kind)
    s.bind(('127.0.0.1', 0))
    p = s.getsockname()[1]
    s.close()
    return p

def readn(c, n):
    out = b''
    while len(out) < n:
        b = c.recv(n-len(out))
        if not b: raise RuntimeError('unexpected EOF')
        out += b
    return out

def relay(a, b):
    a.setblocking(False); b.setblocking(False)
    sel = selectors.DefaultSelector(); sel.register(a, selectors.EVENT_READ, b); sel.register(b, selectors.EVENT_READ, a)
    try:
        while not stop.is_set():
            for key, _ in sel.select(timeout=.2):
                src, dst = key.fileobj, key.data
                try: data = src.recv(65536)
                except BlockingIOError: continue
                if not data: return
                dst.setblocking(True); dst.sendall(data); dst.setblocking(False)
    except (OSError, RuntimeError): pass
    finally:
        sel.close()
        try: a.close()
        except OSError: pass
        try: b.close()
        except OSError: pass

def echo_server(port):
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); ls.bind(('127.0.0.1', port)); ls.listen()
    ls.settimeout(.2)
    def loop():
        while not stop.is_set():
            try: c, _ = ls.accept()
            except socket.timeout: continue
            except OSError: break
            def one(c=c):
                try:
                    while True:
                        data = c.recv(65536)
                        if not data: break
                        c.sendall(data)
                except OSError: pass
                finally: c.close()
            threading.Thread(target=one, daemon=True).start()
        ls.close()
    t = threading.Thread(target=loop, daemon=True); t.start(); threads.append(t)

def socks_server(port):
    ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); ls.bind(('127.0.0.1', port)); ls.listen(); ls.settimeout(.2)
    def loop():
        while not stop.is_set():
            try: c, _ = ls.accept()
            except socket.timeout: continue
            except OSError: break
            def one(c=c):
                try:
                    c.settimeout(20)
                    if readn(c, 1) != b'\x05': return
                    n = readn(c, 1)[0]; readn(c, n); c.sendall(b'\x05\x00')
                    h = readn(c, 4)
                    if h[1] != 1: c.sendall(b'\x05\x07\x00\x01' + b'\0'*6); return
                    if h[3] == 1: host = socket.inet_ntoa(readn(c, 4))
                    elif h[3] == 3: host = readn(c, readn(c, 1)[0]).decode()
                    elif h[3] == 4: host = socket.inet_ntop(socket.AF_INET6, readn(c, 16))
                    else: return
                    port2 = int.from_bytes(readn(c, 2), 'big')
                    upstream = socket.create_connection((host, port2), timeout=10)
                    c.sendall(b'\x05\x00\x00\x01' + socket.inet_aton('127.0.0.1') + (0).to_bytes(2, 'big'))
                    c.settimeout(None); upstream.settimeout(None)
                    relay(c, upstream)
                except (OSError, RuntimeError) as e:
                    try: c.close()
                    except OSError: pass
            threading.Thread(target=one, daemon=True).start()
        ls.close()
    t = threading.Thread(target=loop, daemon=True); t.start(); threads.append(t)

def dns_proxy(port, upstream_port):
    ls = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ls.bind(('127.0.0.1', port)); ls.settimeout(.2)
    def loop():
        while not stop.is_set():
            try: data, addr = ls.recvfrom(65535)
            except socket.timeout: continue
            except OSError: break
            def one(data=data, addr=addr):
                u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(20)
                try:
                    u.sendto(data, ('127.0.0.1', upstream_port))
                    response, _ = u.recvfrom(65535)
                    ls.sendto(response, addr)
                except OSError: pass
                finally: u.close()
            threading.Thread(target=one, daemon=True).start()
        ls.close()
    t = threading.Thread(target=loop, daemon=True); t.start(); threads.append(t)

def wait_port(port, timeout=45):
    end = time.time()+timeout
    while time.time() < end:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=1): return
        except OSError: time.sleep(.1)
    raise RuntimeError('local tunnel listener did not open')

def main():
    echo_port, socks_port, dns_port, local_port = [free_port() for _ in range(3)] + [free_port()]
    resolver_port = free_port(socket.SOCK_DGRAM) if NONAUTH or MIXED else dns_port
    resolver_port2 = free_port(socket.SOCK_DGRAM) if MIXED else None
    echo_server(echo_port); socks_server(socks_port)
    if NONAUTH or MIXED:
        dns_proxy(resolver_port, dns_port)
    if MIXED:
        dns_proxy(resolver_port2, dns_port)
    tag = ('noiz' if NOIZ else 'dnstt') + ('-mixed' if MIXED else ('-nonauth' if NONAUTH else '-authoritative')) + ('-stealth' if STEALTH else '') + ('-raw' if RAW else '')
    server_log = open(os.path.join(LOG_DIR, tag + '-server.log'), 'w')
    client_log = open(os.path.join(LOG_DIR, tag + '-client.log'), 'w')
    upstream_port = echo_port if RAW else socks_port
    server_args = [SERVER, '-udp', f'127.0.0.1:{dns_port}', '-privkey-file', KEY, 't.example.com', f'127.0.0.1:{upstream_port}']
    if NOIZ:
        server_args = [SERVER, '-privkey-file', KEY, 't.example.com', f'127.0.0.1:{dns_port}', f'127.0.0.1:{upstream_port}']
    server = subprocess.Popen(server_args, stdout=server_log, stderr=server_log)
    children.append(server)
    time.sleep(.3)
    if MIXED:
        client_args = [CLIENT, '-udp', f'127.0.0.1:{resolver_port},127.0.0.1:{resolver_port2}',
                       '-resolver-authoritative', '0,1', '-pubkey', PUB]
    else:
        client_args = [CLIENT, '-udp', f'127.0.0.1:{resolver_port}', '-pubkey', PUB]
    if not NONAUTH and not MIXED:
        client_args.append('-authoritative')
    if NOIZ:
        client_args.append('-noiz')
    if STEALTH:
        client_args.append('-stealth')
    if RAW:
        client_args.append('-raw-tcp')
    client_args += ['t.example.com', f'127.0.0.1:{local_port}']
    client = subprocess.Popen(client_args, stdout=client_log, stderr=client_log)
    children.append(client)
    wait_port(local_port)
    with socket.create_connection(('127.0.0.1', local_port), timeout=20) as c:
        c.settimeout(30)
        if RAW:
            # This is deliberately not a SOCKS greeting: the raw path must
            # carry the SSH client identification and payload unchanged.
            payload = b'SSH-2.0-omni-raw\r\n' + os.urandom(16)
            c.sendall(payload)
            got = readn(c, len(payload))
            assert got == payload, 'raw SSH byte-stream mismatch'
        else:
            c.sendall(b'\x05\x01\x00')
            assert readn(c, 2) == b'\x05\x00', 'SOCKS negotiation failed'
            c.sendall(b'\x05\x01\x00\x01' + socket.inet_aton('127.0.0.1') + echo_port.to_bytes(2, 'big'))
            reply = readn(c, 4)
            assert reply[:2] == b'\x05\x00', f'SOCKS connect failed: {reply!r}'
            atyp = reply[3]
            if atyp == 1: readn(c, 6)
            elif atyp == 3: readn(c, readn(c, 1)[0] + 2)
            elif atyp == 4: readn(c, 18)
            payload = b'omni-real-noizdns-loopback-' + os.urandom(16)
            c.sendall(payload)
            got = readn(c, len(payload))
            assert got == payload, 'echo payload mismatch'
    if RAW:
        print('PASS: real UDP DNSTT transport + Noise/KCP/smux + raw TCP SSH byte stream + TCP echo')
    else:
        print('PASS: real UDP DNSTT transport + Noise/KCP/smux + local SOCKS5 + remote SOCKS5 + TCP echo')
    print('ports:', {'dns': dns_port, 'local_listener': local_port, 'upstream_socks': socks_port, 'echo': echo_port})

try:
    main()
finally:
    stop.set()
    for p in reversed(children):
        if p.poll() is None:
            p.send_signal(signal.SIGTERM)
    end = time.time()+8
    for p in children:
        try: p.wait(timeout=max(.1, end-time.time()))
        except subprocess.TimeoutExpired: p.kill(); p.wait()
    print('exit codes:', [p.returncode for p in children])
