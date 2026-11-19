use std::io;

/// Owned duplicate of the descriptor Android gave us. Closing the original does not
/// close the interface while this exists, so it must be dropped when the room ends.
pub struct TunFd(i32);

impl TunFd {
    pub fn duplicate(fd: i32) -> Option<TunFd> {
        let copy = unsafe { libc::dup(fd) };
        (copy >= 0).then_some(TunFd(copy))
    }

    /// Waits up to `timeout_ms` for a packet. Ok(0) means nothing arrived.
    pub fn read(&self, buffer: &mut [u8], timeout_ms: i32) -> io::Result<usize> {
        let mut poll = libc::pollfd { fd: self.0, events: libc::POLLIN, revents: 0 };
        let ready = unsafe { libc::poll(&mut poll, 1, timeout_ms) };
        if ready < 0 {
            let error = io::Error::last_os_error();
            return if error.kind() == io::ErrorKind::Interrupted { Ok(0) } else { Err(error) };
        }
        if ready == 0 {
            return Ok(0);
        }
        if poll.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
            return Err(io::Error::new(io::ErrorKind::BrokenPipe, "tun closed"));
        }
        let length = unsafe { libc::read(self.0, buffer.as_mut_ptr().cast(), buffer.len()) };
        if length < 0 {
            let error = io::Error::last_os_error();
            return match error.kind() {
                io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock => Ok(0),
                _ => Err(error),
            };
        }
        Ok(length as usize)
    }

    pub fn write(&self, packet: &[u8]) {
        unsafe { libc::write(self.0, packet.as_ptr().cast(), packet.len()) };
    }
}

impl Drop for TunFd {
    fn drop(&mut self) {
        unsafe { libc::close(self.0) };
    }
}
