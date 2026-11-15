// Omni patch (applied by compile-shadowquic.sh over upstream v0.4.8).
//
// Upstream protect_socket() connects with tokio UnixStream::connect(path), which is filesystem-only.
// On locked-down Android builds a filesystem socket file can be refused by SELinux, so the protect
// request never completes and the QUIC socket loops back into the VPN. Omni serves the protect
// endpoint in the ABSTRACT namespace (the same one sing-box and MasterDnsVPN use) and names it with a
// leading '@'. This patch teaches the client to connect to an abstract socket when the path starts
// with '@', and keeps the filesystem behaviour for any other path. The wire protocol is unchanged:
// one 0x00 dummy byte + the SCM_RIGHTS descriptor, then a single reply byte where only 0xFF fails.
use std::{
    future,
    io::{self, ErrorKind},
    os::fd::RawFd,
    path::Path,
    task::{Poll, ready},
};

use sendfd::SendWithFd;
use tokio::{io::AsyncReadExt, net::UnixStream};

/// Connect to the protect endpoint: an abstract-namespace socket when `path` begins with '@'
/// (Linux/Android), otherwise the upstream filesystem path.
async fn connect_protect(path: &Path) -> io::Result<UnixStream> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        if let Some(name) = path.to_str().and_then(|s| s.strip_prefix('@')) {
            #[cfg(target_os = "linux")]
            use std::os::linux::net::SocketAddrExt;
            #[cfg(target_os = "android")]
            use std::os::android::net::SocketAddrExt;
            let addr = std::os::unix::net::SocketAddr::from_abstract_name(name)?;
            let std_stream = std::os::unix::net::UnixStream::connect_addr(&addr)?;
            std_stream.set_nonblocking(true)?;
            return UnixStream::from_std(std_stream);
        }
    }
    UnixStream::connect(path).await
}

/// protect socket from loop back on Android
pub async fn protect_socket<P: AsRef<Path>>(path: P, fd: RawFd) -> io::Result<()> {
    let mut stream = connect_protect(path.as_ref()).await?;
    let dummy = [0u8];
    send_with_fd(&stream, &dummy, &[fd]).await?;
    // receive the return value
    let mut response = [0; 1];
    stream.read_exact(&mut response).await?;

    if response[0] == 0xFF {
        return Err(io::Error::other("protect socket failed"));
    }

    Ok(())
}
pub async fn protect_socket_with_retry<P: AsRef<Path>>(path: P, fd: RawFd) -> io::Result<()> {
    let mut out = Ok(());
    for delay in [0, 100, 300, 800] {
        tokio::time::sleep(tokio::time::Duration::from_millis(delay)).await;
        out = protect_socket(path.as_ref(), fd).await;
        if out.is_ok() {
            break;
        }
    }
    out
}

/// Send data with file descriptors
pub async fn send_with_fd(stream: &UnixStream, buf: &[u8], fds: &[RawFd]) -> io::Result<usize> {
    future::poll_fn(|cx| {
        loop {
            ready!(stream.poll_write_ready(cx))?;

            match stream.send_with_fd(buf, fds) {
                Err(ref err) if err.kind() == ErrorKind::WouldBlock => {}
                x => return Poll::Ready(x),
            }
        }
    })
    .await
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use sendfd::RecvWithFd;
    use std::io::Write;
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::os::linux::net::SocketAddrExt;
    use std::os::unix::net::{SocketAddr, UnixListener};

    #[tokio::test]
    async fn abstract_protect_transfers_descriptor_and_checks_reply() {
        for reply in [0x01u8, 0xFFu8] {
            let name = format!("omni-protect-test-{}-{reply}", std::process::id());
            let addr = SocketAddr::from_abstract_name(&name).unwrap();
            let listener = UnixListener::bind_addr(&addr).unwrap();
            let server = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                let mut bytes = [255u8];
                let mut fds = [-1; 1];
                let (n, nfds) = stream.recv_with_fd(&mut bytes, &mut fds).unwrap();
                assert_eq!((n, nfds, bytes[0]), (1, 1, 0));
                // Own and close the received SCM_RIGHTS copy, not the sender's descriptor.
                let _received = unsafe { OwnedFd::from_raw_fd(fds[0]) };
                stream.write_all(&[reply]).unwrap();
            });
            let socket = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
            let result = protect_socket(format!("@{name}"), socket.as_raw_fd()).await;
            assert_eq!(result.is_ok(), reply != 0xFF);
            server.join().unwrap();
        }
    }
}
