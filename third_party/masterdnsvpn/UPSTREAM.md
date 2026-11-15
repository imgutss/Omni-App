# MasterDnsVPN upstream

- Project: https://github.com/masterking32/MasterDnsVPN
- Revision: `4144e544d220681a8e8fe808855ac09dcd07c31c`
- License: MIT; see `LICENSE` in this directory.
- Imported paths: `cmd/`, `internal/`, `go.mod`, `go.sum`, the client samples, and `README.MD`.

The Android app builds the official `cmd/client` entry point without changing the
engine. App-owned Kotlin code supplies the loopback listener, resolver file, and
Android `VpnService.protect(fd)` socket endpoint at runtime. No WhiteDNS source,
binary, asset, UI, or proprietary configuration format is included.

The app packages `masterdnsvpn-LICENSE.txt` and
`masterdnsvpn-THIRD-PARTY-NOTICES.txt` for the engine, Go runtime, and module
licenses.

Rebuild the four Android executables from the repository root with:

```sh
bash compile-masterdns.sh
```

The script writes generated binaries under `libs-masterdns/`; those binaries are
build products and are not committed.
