# Official NaiveProxy native client

Pinned upstream: https://github.com/klzgrad/naiveproxy/releases/tag/v154.0.8037.49-5

`compile-naive.sh` downloads each official Android plugin APK, verifies its pinned SHA256 from
`assets.json`, and packages only `lib/<abi>/libnaive.so` in Omni. No external plugin installation is
required. The executable retains Chromium TLS, HTTP/2, authentication and padding; Omni's loopback
relay forwards opaque bytes to a protected WAN TCP socket. Only HTTPS/HTTP2 mode is supported.

Upstream source, build instructions and third-party notices: https://github.com/klzgrad/naiveproxy
