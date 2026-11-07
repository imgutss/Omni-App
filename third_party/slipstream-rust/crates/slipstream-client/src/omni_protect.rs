//! FD-control transport for Omni's executable Android lifecycle.
//!
//! The Java side uses the same SCM_RIGHTS protocol as the other native DNS
//! engines. The original JNI protection path remains available to library
//! embedders when this environment variable is absent.

use std::io;
use std::mem;
use std::os::unix::io::RawFd;
use std::ptr;

const ENV_NAME: &str = "OMNI_FD_CONTROL_UNIX_SOCKET";

pub(crate) fn configured() -> bool {
    std::env::var_os(ENV_NAME).is_some_and(|value| !value.is_empty())
}

pub(crate) fn protect_socket(fd: RawFd) -> bool {
    let Some(address) = std::env::var_os(ENV_NAME) else {
        return false;
    };
    let address = address.to_string_lossy();
    match protect_fd(&address, fd) {
        Ok(()) => true,
        Err(error) => {
            tracing::error!("Omni FD-control failed: {}", error);
            false
        }
    }
}

fn protect_fd(address: &str, fd: RawFd) -> io::Result<()> {
    let (sockaddr, address_len) = socket_address(address)?;
    let socket = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if socket < 0 {
        return Err(io::Error::last_os_error());
    }
    let guard = FdGuard(socket);
    let timeout = libc::timeval {
        tv_sec: 5,
        tv_usec: 0,
    };
    for option in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
        let result = unsafe {
            libc::setsockopt(
                guard.0,
                libc::SOL_SOCKET,
                option,
                &timeout as *const _ as *const libc::c_void,
                mem::size_of_val(&timeout) as libc::socklen_t,
            )
        };
        if result != 0 {
            return Err(io::Error::last_os_error());
        }
    }
    let connected = unsafe {
        libc::connect(
            guard.0,
            &sockaddr as *const _ as *const libc::sockaddr,
            address_len,
        )
    };
    if connected != 0 {
        return Err(io::Error::last_os_error());
    }
    send_descriptor(guard.0, fd)?;
    let mut status = 0u8;
    let received = unsafe { libc::recv(guard.0, &mut status as *mut _ as *mut libc::c_void, 1, 0) };
    if received != 1 {
        return Err(io::Error::last_os_error());
    }
    if status != 1 {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            "Android rejected socket protection",
        ));
    }
    Ok(())
}

fn socket_address(address: &str) -> io::Result<(libc::sockaddr_un, libc::socklen_t)> {
    if address.is_empty() || address.as_bytes().contains(&0) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid FD-control address",
        ));
    }
    let mut sockaddr: libc::sockaddr_un = unsafe { mem::zeroed() };
    sockaddr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let bytes = address.as_bytes();
    let abstract_socket = bytes[0] == b'@';
    let source = if abstract_socket { &bytes[1..] } else { bytes };
    let required = source.len() + 1;
    if source.is_empty() || required > sockaddr.sun_path.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "FD-control address is too long",
        ));
    }
    let path_offset = if abstract_socket { 1 } else { 0 };
    unsafe {
        ptr::copy_nonoverlapping(
            source.as_ptr(),
            sockaddr.sun_path.as_mut_ptr().add(path_offset) as *mut u8,
            source.len(),
        );
    }
    // Linux abstract names begin with a NUL and have no trailing NUL. File
    // names retain the zeroed trailing byte supplied by mem::zeroed above.
    let base = mem::size_of::<libc::sa_family_t>();
    let name_len = source.len() + if abstract_socket { 1 } else { 1 };
    Ok((sockaddr, (base + name_len) as libc::socklen_t))
}

fn send_descriptor(socket: RawFd, fd: RawFd) -> io::Result<()> {
    let mut payload = [1u8];
    let mut iov = libc::iovec {
        iov_base: payload.as_mut_ptr() as *mut libc::c_void,
        iov_len: payload.len(),
    };
    let mut control =
        vec![0u8; unsafe { libc::CMSG_SPACE(mem::size_of::<RawFd>() as u32) } as usize];
    let mut message: libc::msghdr = unsafe { mem::zeroed() };
    message.msg_iov = &mut iov;
    message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    message.msg_controllen = control.len() as _;
    unsafe {
        let header = libc::CMSG_FIRSTHDR(&message);
        if header.is_null() {
            return Err(io::Error::new(
                io::ErrorKind::Other,
                "cannot allocate SCM_RIGHTS header",
            ));
        }
        (*header).cmsg_level = libc::SOL_SOCKET;
        (*header).cmsg_type = libc::SCM_RIGHTS;
        (*header).cmsg_len = libc::CMSG_LEN(mem::size_of::<RawFd>() as u32) as _;
        ptr::write(libc::CMSG_DATA(header) as *mut RawFd, fd);
        if libc::sendmsg(socket, &message, 0) != 1 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

struct FdGuard(RawFd);

impl Drop for FdGuard {
    fn drop(&mut self) {
        unsafe { libc::close(self.0) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abstract_address_has_leading_nul_and_exact_length() {
        let (address, length) = socket_address("@omni-test").expect("valid abstract address");
        let base = mem::size_of::<libc::sa_family_t>();
        assert_eq!(length as usize, base + 1 + "omni-test".len());
        let path =
            unsafe { std::slice::from_raw_parts(address.sun_path.as_ptr() as *const u8, 10) };
        assert_eq!(&path[..10], b"\0omni-test");
    }

    #[test]
    fn malformed_addresses_are_rejected() {
        assert!(socket_address("").is_err());
        assert!(socket_address("@").is_err());
        assert!(socket_address(&format!("@{}", "x".repeat(200))).is_err());
    }
}
