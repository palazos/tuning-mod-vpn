//! Userspace WireGuard hub-and-spoke: one UDP socket shared by every peer, one TUN.
//!
//! The host is the hub. Guests only ever talk to the host, which forwards packets
//! between guests and copies broadcasts/multicasts to everybody.

use boringtun::noise::{Packet, Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use rand_core::{OsRng, RngCore};
use std::io::ErrorKind;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::stun;
use crate::upnp;

const MAX_PACKET: usize = 2048;
// Real WireGuard messages start with a byte in 1..=4, so this never collides with them.
const PUNCH: [u8; 8] = *b"\xFFWLPUNCH";
const KEEPALIVE_SECS: u16 = 15;
const INIT_RETRY: Duration = Duration::from_secs(1);
const PROBE_EVERY: Duration = Duration::from_millis(500);
const TICK: Duration = Duration::from_millis(250);
const STUN_KEEPALIVE: Duration = Duration::from_secs(15);
const UPNP_RENEW: Duration = Duration::from_secs(20 * 60);
const IGMP: u8 = 2;
const PING_EVERY: Duration = Duration::from_secs(2);
// A tiny UDP packet the two engines exchange through the tunnel to measure its round trip.
const PROBE_TAG: [u8; 4] = *b"WLRT";
const PROBE_PORT: u16 = 47001;
const PROBE_LEN: usize = 20 + 8 + 4 + 1 + 8;
const PROBE_PING: u8 = 0;
const PROBE_PONG: u8 = 1;
const SUBNET_BROADCAST: Ipv4Addr = Ipv4Addr::new(10, 77, 0, 255);
// IANA dynamic/private range. Android hands these out as ephemeral UDP ports.
const EPHEMERAL_START: u16 = 32768;
const EPHEMERAL_END: u16 = 60999;

// Port of the socket we just closed. A mobile NAT keeps that flow alive with no UDP
// shutdown, so binding it again makes the next STUN test look symmetric and the
// handshake toward the host never comes back.
static LAST_LOCAL_PORT: AtomicU16 = AtomicU16::new(0);

pub type Sink = Arc<dyn Fn(&[u8]) + Send + Sync>;

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

fn ephemeral_port(except: u16) -> u16 {
    let mut bytes = [0u8; 2];
    OsRng.fill_bytes(&mut bytes);
    let span = EPHEMERAL_END - EPHEMERAL_START + 1;
    let port = EPHEMERAL_START + (u16::from_be_bytes(bytes) % span);
    if port != except {
        port
    } else if port == EPHEMERAL_END {
        EPHEMERAL_START
    } else {
        port + 1
    }
}

// A new local port, never the one the previous session used.
fn bind_fresh() -> Result<UdpSocket, String> {
    let previous = LAST_LOCAL_PORT.load(Ordering::Relaxed);
    let socket = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| format!("bind: {e}"))?;
    let port = socket.local_addr().map_err(|e| e.to_string())?.port();
    if previous == 0 || port != previous {
        LAST_LOCAL_PORT.store(port, Ordering::Relaxed);
        return Ok(socket);
    }
    drop(socket);
    for _ in 0..16 {
        let candidate = ephemeral_port(previous);
        if let Ok(socket) = UdpSocket::bind(("0.0.0.0", candidate)) {
            LAST_LOCAL_PORT.store(candidate, Ordering::Relaxed);
            return Ok(socket);
        }
    }
    let socket = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| format!("bind: {e}"))?;
    let port = socket.local_addr().map_err(|e| e.to_string())?.port();
    LAST_LOCAL_PORT.store(port, Ordering::Relaxed);
    Ok(socket)
}

pub(crate) fn pin_fd(fd: i32, network_handle: u64) -> bool {
    if network_handle == 0 {
        return false;
    }
    #[cfg(target_os = "android")]
    {
        return set_socket_network(network_handle, fd);
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = fd;
        false
    }
}

fn pin_socket_to_network(socket: &UdpSocket, network_handle: u64) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        return pin_fd(socket.as_raw_fd(), network_handle);
    }
    #[cfg(not(unix))]
    {
        let _ = (socket, network_handle);
        false
    }
}

#[cfg(target_os = "android")]
fn set_socket_network(network_handle: u64, fd: i32) -> bool {
    use std::sync::OnceLock;
    static SET: OnceLock<usize> = OnceLock::new();
    // Kept open for the process. Closing it would unmap the function.
    let ptr = *SET.get_or_init(|| unsafe {
        let handle = libc::dlopen(b"libandroid.so\0".as_ptr() as *const libc::c_char, libc::RTLD_NOW);
        if handle.is_null() {
            return 0;
        }
        libc::dlsym(handle, b"android_setsocknetwork\0".as_ptr() as *const libc::c_char) as usize
    });
    if ptr == 0 {
        return false;
    }
    let set: unsafe extern "C" fn(u64, i32) -> i32 = unsafe { std::mem::transmute(ptr) };
    unsafe { set(network_handle, fd) == 0 }
}

struct Peer {
    index: u32,
    address: Ipv4Addr,
    tunn: Tunn,
    // The side that starts the handshake; the other side only opens its NAT.
    initiator: bool,
    // Last address a valid packet came from; None until the first exchange.
    endpoint: Option<SocketAddr>,
    candidates: Vec<SocketAddr>,
    // 0 is the room socket. Any other value is a socket opened for this peer alone,
    // so a new guest does not reuse a public port the others are still using.
    slot: u8,
    last_init: Instant,
    last_probe: Instant,
    last_ping: Instant,
    rtt_us: Option<u64>,
    tx: u64,
    rx: u64,
}

struct Shared {
    peers: Vec<Peer>,
    next_index: u32,
    is_host: bool,
    local: Ipv4Addr,
    sink: Option<Sink>,
}

// A second socket for one joining guest. Existing peers stay on the room socket.
struct Door {
    id: u8,
    socket: Arc<UdpSocket>,
    stun_server: Option<SocketAddr>,
    running: Arc<AtomicBool>,
    upnp: Option<upnp::Mapping>,
}

struct Core {
    socket: UdpSocket,
    doors: Mutex<Vec<Door>>,
    shared: Mutex<Shared>,
    running: AtomicBool,
    stun_server: Option<SocketAddr>,
    started: Instant,
    upnp: Mutex<Option<upnp::Mapping>>,
}

pub struct PeerStatus {
    pub address: Ipv4Addr,
    pub established: bool,
    pub endpoint: Option<SocketAddr>,
    pub tx: u64,
    pub rx: u64,
    pub rtt_us: Option<u64>,
}

pub struct Opened {
    pub port: u16,
    pub mapped: Option<SocketAddr>,
    pub symmetric: Option<bool>,
    /// Public address reachable through a port forwarded on the router.
    pub forwarded: Option<SocketAddr>,
    /// Every address the STUN probes reported, for the log.
    pub probes: Vec<SocketAddr>,
    /// Left empty. Guessed ports are not announced; a new socket is opened instead.
    pub predict: Vec<SocketAddr>,
}

pub struct Engine {
    core: Arc<Core>,
    private: [u8; 32],
    public: [u8; 32],
    opened: Opened,
    // The socket was pinned to the physical network, so the VPN must not rewrite that.
    network_bound: bool,
    threads: Mutex<Vec<JoinHandle<()>>>,
    door_threads: Mutex<Vec<(u8, JoinHandle<()>)>>,
    #[cfg(unix)]
    tun_thread: Mutex<Option<(Arc<AtomicBool>, JoinHandle<()>)>>,
}

impl Engine {
    /// Binds a UDP socket and learns its public address through STUN (blocking).
    /// `gateway` is the router of the physical network, used to open a port on it.
    /// `network_handle` is an Android `Network` handle; 0 leaves the socket unpinned.
    pub fn open(gateway: Option<Ipv4Addr>, network_handle: u64) -> Result<Engine, String> {
        let socket = bind_fresh()?;
        // Pin before the first packet, or STUN and the handshake leave through whatever
        // network the previous VPN left as the process default.
        let network_bound = pin_socket_to_network(&socket, network_handle);
        let probe = stun::probe(&socket);
        let port = socket.local_addr().map_err(|e| e.to_string())?.port();
        let mut engine = Self::with_socket(socket, probe, upnp::map(port, gateway, network_handle))?;
        engine.network_bound = network_bound;
        Ok(engine)
    }

    fn with_socket(socket: UdpSocket, probe: stun::Probe, mapping: Option<upnp::Mapping>) -> Result<Engine, String> {
        socket
            .set_read_timeout(Some(Duration::from_millis(200)))
            .map_err(|e| format!("timeout: {e}"))?;
        let port = socket.local_addr().map_err(|e| e.to_string())?.port();

        let secret = StaticSecret::random_from_rng(OsRng);
        let private = secret.to_bytes();
        let public = PublicKey::from(&secret).to_bytes();

        let forwarded = mapping
            .as_ref()
            .and_then(|m| m.external_ip.map(|ip| SocketAddr::from((ip, m.external_port))));
        let mut probes = probe.mapped.into_iter().collect::<Vec<_>>();
        probes.extend(probe.others.iter().copied());

        let core = Arc::new(Core {
            socket,
            doors: Mutex::new(Vec::new()),
            shared: Mutex::new(Shared {
                peers: Vec::new(),
                next_index: 1,
                is_host: false,
                local: Ipv4Addr::UNSPECIFIED,
                sink: None,
            }),
            running: AtomicBool::new(true),
            stun_server: probe.server,
            started: Instant::now(),
            upnp: Mutex::new(mapping),
        });

        let receiver = {
            let core = core.clone();
            thread::Builder::new()
                .name("vpn-udp".into())
                .spawn(move || core.receive_loop())
                .map_err(|e| e.to_string())?
        };
        let timer = {
            let core = core.clone();
            thread::Builder::new()
                .name("vpn-timers".into())
                .spawn(move || core.timer_loop())
                .map_err(|e| e.to_string())?
        };

        Ok(Engine {
            core,
            private,
            public,
            opened: Opened { port, mapped: probe.mapped, symmetric: probe.symmetric, forwarded, probes, predict: Vec::new() },
            network_bound: false,
            threads: Mutex::new(vec![receiver, timer]),
            door_threads: Mutex::new(Vec::new()),
            #[cfg(unix)]
            tun_thread: Mutex::new(None),
        })
    }

    pub fn opened(&self) -> &Opened {
        &self.opened
    }

    pub fn network_bound(&self) -> bool {
        self.network_bound
    }

    pub fn socket_fd(&self) -> i32 {
        #[cfg(unix)]
        {
            use std::os::unix::io::AsRawFd;
            self.core.socket.as_raw_fd()
        }
        #[cfg(not(unix))]
        {
            let _ = self;
            -1
        }
    }

    pub fn public_key(&self) -> [u8; 32] {
        self.public
    }

    pub fn configure(&self, is_host: bool, local: Ipv4Addr, sink: Option<Sink>) {
        let mut shared = lock(&self.core.shared);
        shared.is_host = is_host;
        shared.local = local;
        shared.sink = sink;
    }

    /// Replaces any previous peer with the same address.
    /// `slot` 0 uses the room socket. Any other slot is a door opened for this peer.
    pub fn add_peer(&self, public: [u8; 32], address: Ipv4Addr, candidates: Vec<SocketAddr>, initiator: bool, slot: u8) {
        let mut shared = lock(&self.core.shared);
        shared.peers.retain(|p| p.address != address);
        let index = shared.next_index & 0x00FF_FFFF;
        shared.next_index = shared.next_index.wrapping_add(1);

        let tunn = Tunn::new(
            StaticSecret::from(self.private),
            PublicKey::from(public),
            None,
            Some(KEEPALIVE_SECS),
            index,
            None,
        );
        let past = Instant::now() - Duration::from_secs(10);
        shared.peers.push(Peer {
            index,
            address,
            tunn,
            initiator,
            endpoint: None,
            candidates,
            slot,
            last_init: past,
            last_probe: past,
            last_ping: past,
            rtt_us: None,
            tx: 0,
            rx: 0,
        });
    }

    pub fn remove_peer(&self, address: Ipv4Addr) {
        lock(&self.core.shared).peers.retain(|p| p.address != address);
    }

    pub fn status(&self) -> Vec<PeerStatus> {
        lock(&self.core.shared)
            .peers
            .iter()
            .map(|p| PeerStatus {
                address: p.address,
                established: p.tunn.stats().0.is_some(),
                endpoint: p.endpoint,
                tx: p.tx,
                rx: p.rx,
                rtt_us: p.rtt_us,
            })
            .collect()
    }

    /// Feeds a packet read from the TUN into the engine.
    #[cfg(test)]
    pub fn inject(&self, packet: &[u8]) {
        self.core.handle_tun_packet(packet);
    }

    /// Takes over a TUN descriptor (duplicated, so the caller keeps its own).
    #[cfg(unix)]
    pub fn attach_tun(&self, fd: i32, is_host: bool, local: Ipv4Addr) -> bool {
        use crate::tun::TunFd;

        self.detach_tun();
        let Some(tun) = TunFd::duplicate(fd) else { return false };
        let tun = Arc::new(tun);

        let writer = tun.clone();
        self.configure(is_host, local, Some(Arc::new(move |packet: &[u8]| writer.write(packet))));

        let run = Arc::new(AtomicBool::new(true));
        let spawned = {
            let core = self.core.clone();
            let run = run.clone();
            thread::Builder::new().name("vpn-tun".into()).spawn(move || {
                let mut buffer = [0u8; MAX_PACKET];
                while run.load(Ordering::Relaxed) && core.running.load(Ordering::Relaxed) {
                    match tun.read(&mut buffer, 200) {
                        Ok(0) => {}
                        Ok(length) => core.handle_tun_packet(&buffer[..length]),
                        Err(_) => break,
                    }
                }
            })
        };
        match spawned {
            Ok(handle) => {
                *lock(&self.tun_thread) = Some((run, handle));
                true
            }
            Err(_) => {
                self.configure(is_host, local, None);
                false
            }
        }
    }

    #[cfg(unix)]
    pub fn detach_tun(&self) {
        let Some((run, handle)) = lock(&self.tun_thread).take() else { return };
        run.store(false, Ordering::Relaxed);
        lock(&self.core.shared).sink = None;
        let _ = handle.join();
    }

    /// A new public port for one guest, leaving everyone already connected on the room socket.
    pub fn open_door(&self, gateway: Option<Ipv4Addr>, network_handle: u64) -> Result<(u8, Opened, bool), String> {
        if !self.core.running.load(Ordering::Relaxed) {
            return Err("closed".into());
        }
        let id = {
            let doors = lock(&self.core.doors);
            if doors.len() >= 24 {
                return Err("too many doors".into());
            }
            (1u8..=64).find(|candidate| doors.iter().all(|door| door.id != *candidate)).ok_or("no door id")?
        };
        let socket = bind_fresh()?;
        // Same pin as the room socket: the VPN is already up while a later guest joins.
        let pinned = pin_socket_to_network(&socket, network_handle);
        let probe = stun::probe(&socket);
        socket.set_read_timeout(Some(Duration::from_millis(200))).map_err(|e| format!("timeout: {e}"))?;
        let port = socket.local_addr().map_err(|e| e.to_string())?.port();
        let mut probes = probe.mapped.into_iter().collect::<Vec<_>>();
        probes.extend(probe.others.iter().copied());
        // The VPN is already up, so this socket has to be pinned or the router never sees the request.
        let mapping = upnp::map(port, gateway, network_handle);
        let forwarded = mapping.as_ref().and_then(|m| m.external_ip.map(|ip| SocketAddr::from((ip, m.external_port))));
        let opened = Opened {
            port,
            mapped: probe.mapped,
            symmetric: probe.symmetric,
            forwarded,
            probes,
            predict: Vec::new(),
        };
        let running = Arc::new(AtomicBool::new(true));
        let socket = Arc::new(socket);
        let core = self.core.clone();
        let recv_socket = socket.clone();
        let recv_running = running.clone();
        let handle = thread::Builder::new()
            .name(format!("vpn-door-{id}"))
            .spawn(move || core.recv_socket(&recv_socket, id, &recv_running))
            .map_err(|e| e.to_string())?;
        lock(&self.core.doors).push(Door { id, socket, stun_server: probe.server, running, upnp: mapping });
        lock(&self.door_threads).push((id, handle));
        Ok((id, opened, pinned))
    }

    pub fn close_door(&self, id: u8) {
        if id == 0 {
            return;
        }
        let door = {
            let mut doors = lock(&self.core.doors);
            doors.iter().position(|door| door.id == id).map(|pos| doors.swap_remove(pos))
        };
        let Some(door) = door else { return };
        door.running.store(false, Ordering::Relaxed);
        let handle = {
            let mut threads = lock(&self.door_threads);
            threads.iter().position(|(door_id, _)| *door_id == id).map(|pos| threads.swap_remove(pos).1)
        };
        if let Some(handle) = handle {
            let _ = handle.join();
        }
        if let Some(mapping) = door.upnp {
            mapping.remove();
        }
    }

    pub fn close(&self) {
        self.core.running.store(false, Ordering::Relaxed);
        for door in lock(&self.core.doors).iter() {
            door.running.store(false, Ordering::Relaxed);
        }
        #[cfg(unix)]
        self.detach_tun();
        for handle in lock(&self.threads).drain(..) {
            let _ = handle.join();
        }
        for (_, handle) in lock(&self.door_threads).drain(..) {
            let _ = handle.join();
        }
        for door in lock(&self.core.doors).drain(..) {
            if let Some(mapping) = door.upnp {
                mapping.remove();
            }
        }
        if let Some(mapping) = lock(&self.core.upnp).take() {
            mapping.remove();
        }
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.close();
    }
}

fn probe_packet(source: Ipv4Addr, destination: Ipv4Addr, kind: u8, id: u64) -> Vec<u8> {
    let mut packet = vec![0u8; PROBE_LEN];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(PROBE_LEN as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = 17;
    packet[12..16].copy_from_slice(&source.octets());
    packet[16..20].copy_from_slice(&destination.octets());
    let mut sum: u32 = packet[..20].chunks(2).map(|c| u32::from(u16::from_be_bytes([c[0], c[1]]))).sum();
    while sum > 0xFFFF {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    packet[10..12].copy_from_slice(&(!(sum as u16)).to_be_bytes());
    packet[20..22].copy_from_slice(&PROBE_PORT.to_be_bytes());
    packet[22..24].copy_from_slice(&PROBE_PORT.to_be_bytes());
    packet[24..26].copy_from_slice(&((PROBE_LEN - 20) as u16).to_be_bytes());
    packet[28..32].copy_from_slice(&PROBE_TAG);
    packet[32] = kind;
    packet[33..41].copy_from_slice(&id.to_be_bytes());
    packet
}

fn parse_probe(packet: &[u8]) -> Option<(u8, u64)> {
    if packet.len() != PROBE_LEN || packet[9] != 17 || packet[28..32] != PROBE_TAG {
        return None;
    }
    Some((packet[32], u64::from_be_bytes(packet[33..41].try_into().ok()?)))
}

fn ipv4_dst(packet: &[u8]) -> Option<Ipv4Addr> {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return None;
    }
    Some(Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]))
}

fn is_group(address: Ipv4Addr) -> bool {
    address.is_multicast() || address.is_broadcast() || address == SUBNET_BROADCAST
}

impl Core {
    fn send_on(&self, slot: u8, data: &[u8], destination: SocketAddr) {
        if slot == 0 {
            let _ = self.socket.send_to(data, destination);
            return;
        }
        let doors = lock(&self.doors);
        if let Some(door) = doors.iter().find(|door| door.id == slot) {
            let _ = door.socket.send_to(data, destination);
        }
    }

    fn send_raw(&self, peer: &Peer, data: &[u8]) {
        match peer.endpoint {
            Some(endpoint) => self.send_on(peer.slot, data, endpoint),
            // Before the first answer we do not know which candidate works, so try all.
            None => {
                for candidate in &peer.candidates {
                    self.send_on(peer.slot, data, *candidate);
                }
            }
        }
    }

    fn send_plain(&self, peer: &mut Peer, plain: &[u8]) {
        if plain.len() > MAX_PACKET - 64 {
            return;
        }
        let mut buffer = [0u8; MAX_PACKET];
        if let TunnResult::WriteToNetwork(out) = peer.tunn.encapsulate(plain, &mut buffer) {
            self.send_raw(peer, out);
            peer.tx += plain.len() as u64;
        }
    }

    fn handle_tun_packet(&self, packet: &[u8]) {
        let Some(destination) = ipv4_dst(packet) else { return };
        // Membership reports of the multicast route are not for the other players.
        if packet[9] == IGMP {
            return;
        }
        let mut shared = lock(&self.shared);
        if shared.is_host {
            if is_group(destination) {
                for peer in shared.peers.iter_mut() {
                    self.send_plain(peer, packet);
                }
            } else if let Some(peer) = shared.peers.iter_mut().find(|p| p.address == destination) {
                self.send_plain(peer, packet);
            }
        } else if let Some(hub) = shared.peers.first_mut() {
            // Everything goes through the host, which forwards it.
            self.send_plain(hub, packet);
        }
    }

    fn receive_loop(&self) {
        self.recv_socket(&self.socket, 0, &self.running);
    }

    fn recv_socket(&self, socket: &UdpSocket, slot: u8, running: &AtomicBool) {
        let mut buffer = [0u8; MAX_PACKET];
        while self.running.load(Ordering::Relaxed) && running.load(Ordering::Relaxed) {
            match socket.recv_from(&mut buffer) {
                Ok((length, source)) => self.on_datagram(&buffer[..length], source, slot),
                Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
                Err(_) => thread::sleep(Duration::from_millis(50)),
            }
        }
    }

    fn timer_loop(&self) {
        let mut last_stun = Instant::now();
        let mut last_renew = Instant::now();
        while self.running.load(Ordering::Relaxed) {
            thread::sleep(TICK);
            let now = Instant::now();
            self.tick(now);
            if now.duration_since(last_renew) >= UPNP_RENEW {
                last_renew = now;
                if let Some(mapping) = lock(&self.upnp).clone() {
                    mapping.renew();
                }
                let mappings: Vec<_> = lock(&self.doors).iter().filter_map(|door| door.upnp.clone()).collect();
                for mapping in mappings {
                    mapping.renew();
                }
            }
            if now.duration_since(last_stun) >= STUN_KEEPALIVE {
                last_stun = now;
                // Keeps the NAT mapping we advertised alive until the peers connect.
                let request = stun::binding_request().0;
                if let Some(server) = self.stun_server {
                    let _ = self.socket.send_to(&request, server);
                }
                let extra: Vec<_> = lock(&self.doors)
                    .iter()
                    .filter_map(|door| door.stun_server.map(|server| (door.socket.clone(), server)))
                    .collect();
                for (socket, server) in extra {
                    let _ = socket.send_to(&stun::binding_request().0, server);
                }
            }
        }
    }

    fn tick(&self, now: Instant) {
        let mut buffer = [0u8; MAX_PACKET];
        let mut shared = lock(&self.shared);
        let local = shared.local;
        for peer in shared.peers.iter_mut() {
            if let TunnResult::WriteToNetwork(out) = peer.tunn.update_timers(&mut buffer) {
                self.send_raw(peer, out);
            }
            if peer.tunn.stats().0.is_some() {
                if !local.is_unspecified() && now.duration_since(peer.last_ping) >= PING_EVERY {
                    peer.last_ping = now;
                    let id = self.started.elapsed().as_micros() as u64;
                    self.send_plain(peer, &probe_packet(local, peer.address, PROBE_PING, id));
                }
                continue;
            }
            if peer.initiator {
                if now.duration_since(peer.last_init) >= INIT_RETRY {
                    peer.last_init = now;
                    if let TunnResult::WriteToNetwork(out) = peer.tunn.format_handshake_initiation(&mut buffer, true) {
                        self.send_raw(peer, out);
                    }
                }
            } else if now.duration_since(peer.last_probe) >= PROBE_EVERY {
                // Sending first opens a hole in our NAT for the initiator's packets.
                peer.last_probe = now;
                let slot = peer.slot;
                for candidate in &peer.candidates {
                    self.send_on(slot, &PUNCH, *candidate);
                }
            }
        }
    }

    fn on_datagram(&self, data: &[u8], source: SocketAddr, slot: u8) {
        // A probe is enough to learn the port the carrier actually used.
        self.note_source(source, slot);
        if data.first() == Some(&PUNCH[0]) {
            return;
        }
        // Initiations do not say who they are from, so every peer gets a try.
        let receiver = match Tunn::parse_incoming_packet(data) {
            Ok(Packet::HandshakeInit(_)) => None,
            Ok(Packet::HandshakeResponse(p)) => Some(p.receiver_idx),
            Ok(Packet::PacketCookieReply(p)) => Some(p.receiver_idx),
            Ok(Packet::PacketData(p)) => Some(p.receiver_idx),
            Err(_) => return,
        };

        let mut shared = lock(&self.shared);
        let (is_host, local) = (shared.is_host, shared.local);
        let order: Vec<usize> = match receiver {
            Some(id) => shared.peers.iter().position(|p| p.index == id >> 8).into_iter().collect(),
            None => (0..shared.peers.len()).collect(),
        };
        for position in order {
            let mut plains = Vec::new();
            if self.decapsulate(&mut shared.peers[position], data, source, slot, is_host, local, &mut plains) {
                for plain in plains {
                    self.route_incoming(&mut shared, position, &plain);
                }
                return;
            }
        }
    }

    // Returns true when the packet was authentic and produced something to act on.
    fn note_source(&self, source: SocketAddr, slot: u8) {
        let mut shared = lock(&self.shared);
        let mut found = None;
        for (index, peer) in shared.peers.iter().enumerate() {
            let same_ip = peer.endpoint.is_some_and(|endpoint| endpoint.ip() == source.ip())
                || peer.candidates.iter().any(|candidate| candidate.ip() == source.ip());
            if !same_ip {
                continue;
            }
            if peer.slot == slot {
                found = Some(index);
                break;
            }
            if found.is_none() {
                found = Some(index);
            }
        }
        if let Some(index) = found {
            shared.peers[index].endpoint = Some(source);
        }
    }

    fn decapsulate(
        &self,
        peer: &mut Peer,
        data: &[u8],
        source: SocketAddr,
        slot: u8,
        is_host: bool,
        local: Ipv4Addr,
        plains: &mut Vec<Vec<u8>>,
    ) -> bool {
        let mut buffer = [0u8; MAX_PACKET];
        let mut input = data;
        let mut accepted = false;
        loop {
            match peer.tunn.decapsulate(Some(source.ip()), input, &mut buffer) {
                TunnResult::WriteToNetwork(out) => {
                    self.send_on(slot, out, source);
                    accepted = true;
                }
                TunnResult::WriteToTunnelV4(plain, from) => {
                    accepted = true;
                    peer.rx += plain.len() as u64;
                    // A guest may only use its own address; the hub may relay anyone in the subnet.
                    let allowed = if is_host {
                        from == peer.address
                    } else {
                        from.octets()[..3] == [10, 77, 0] && from != local
                    };
                    if allowed {
                        plains.push(plain.to_vec());
                    }
                    break;
                }
                TunnResult::WriteToTunnelV6(..) => {
                    accepted = true;
                    break;
                }
                TunnResult::Done | TunnResult::Err(_) => break,
            }
            // Drain anything the tunnel queued while the handshake was pending.
            input = &[];
        }
        if accepted {
            peer.endpoint = Some(source);
        }
        accepted
    }

    fn on_probe(&self, shared: &mut Shared, from: usize, kind: u8, id: u64) {
        let local = shared.local;
        let Some(peer) = shared.peers.get_mut(from) else { return };
        if kind == PROBE_PING {
            self.send_plain(peer, &probe_packet(local, peer.address, PROBE_PONG, id));
        } else {
            let now = self.started.elapsed().as_micros() as u64;
            peer.rtt_us = now.checked_sub(id);
        }
    }

    fn route_incoming(&self, shared: &mut Shared, from: usize, plain: &[u8]) {
        let Some(destination) = ipv4_dst(plain) else { return };
        if let Some((kind, id)) = parse_probe(plain) {
            if destination == shared.local {
                self.on_probe(shared, from, kind, id);
            }
            return;
        }
        let for_us = destination == shared.local || is_group(destination);
        if for_us {
            if let Some(sink) = &shared.sink {
                sink(plain);
            }
        }
        if !shared.is_host || destination == shared.local {
            return;
        }
        if is_group(destination) {
            for (position, peer) in shared.peers.iter_mut().enumerate() {
                if position != from {
                    self.send_plain(peer, plain);
                }
            }
        } else if let Some(peer) = shared.peers.iter_mut().find(|p| p.address == destination) {
            self.send_plain(peer, plain);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::{channel, Receiver};

    fn node(is_host: bool, local: Ipv4Addr) -> (Engine, Receiver<Vec<u8>>, SocketAddr) {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let address = socket.local_addr().unwrap();
        let probe = stun::Probe { mapped: None, server: None, symmetric: None, others: Vec::new() };
        let engine = Engine::with_socket(socket, probe, None).unwrap();
        let (sender, receiver) = channel();
        let sink: Sink = Arc::new(move |packet: &[u8]| {
            let _ = sender.send(packet.to_vec());
        });
        engine.configure(is_host, local, Some(sink));
        (engine, receiver, address)
    }

    fn packet(source: Ipv4Addr, destination: Ipv4Addr, payload: &[u8]) -> Vec<u8> {
        let total = (20 + payload.len()) as u16;
        let mut p = vec![0x45, 0x00];
        p.extend_from_slice(&total.to_be_bytes());
        p.extend_from_slice(&[0, 0, 0, 0, 64, 17, 0, 0]);
        p.extend_from_slice(&source.octets());
        p.extend_from_slice(&destination.octets());
        p.extend_from_slice(payload);
        p
    }

    fn wait_established(engine: &Engine) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            let status = engine.status();
            if !status.is_empty() && status.iter().all(|p| p.established) {
                return;
            }
            thread::sleep(Duration::from_millis(100));
        }
        panic!("tunnel did not come up");
    }

    #[test]
    fn hub_forwards_between_guests_and_replicates_broadcasts() {
        let host_ip = Ipv4Addr::new(10, 77, 0, 1);
        let one_ip = Ipv4Addr::new(10, 77, 0, 2);
        let two_ip = Ipv4Addr::new(10, 77, 0, 3);

        let (host, host_rx, host_addr) = node(true, host_ip);
        let (one, one_rx, one_addr) = node(false, one_ip);
        let (two, two_rx, two_addr) = node(false, two_ip);

        host.add_peer(one.public_key(), one_ip, vec![one_addr], false, 0);
        host.add_peer(two.public_key(), two_ip, vec![two_addr], false, 0);
        one.add_peer(host.public_key(), host_ip, vec![host_addr], true, 0);
        two.add_peer(host.public_key(), host_ip, vec![host_addr], true, 0);
        wait_established(&host);
        wait_established(&one);
        wait_established(&two);

        // The engines measure the tunnel round trip on their own.
        let deadline = Instant::now() + Duration::from_secs(6);
        while Instant::now() < deadline && !one.status().iter().all(|p| p.rtt_us.is_some()) {
            thread::sleep(Duration::from_millis(100));
        }
        let rtt = one.status()[0].rtt_us.expect("no round trip measured");
        assert!(rtt < 1_000_000);
        assert!(host_rx.recv_timeout(Duration::from_millis(100)).is_err(), "probes must not reach the interface");

        let timeout = Duration::from_secs(3);

        // Guest to host.
        let to_host = packet(one_ip, host_ip, b"hello host");
        one.inject(&to_host);
        assert_eq!(host_rx.recv_timeout(timeout).unwrap(), to_host);

        // Guest to guest, through the hub only.
        let to_two = packet(one_ip, two_ip, b"hello two");
        one.inject(&to_two);
        assert_eq!(two_rx.recv_timeout(timeout).unwrap(), to_two);
        assert!(host_rx.recv_timeout(Duration::from_millis(300)).is_err());

        // Host to guest.
        let to_one = packet(host_ip, one_ip, b"hello one");
        host.inject(&to_one);
        assert_eq!(one_rx.recv_timeout(timeout).unwrap(), to_one);

        // Broadcast from a guest reaches the host and the other guest, not the sender.
        let broadcast = packet(one_ip, SUBNET_BROADCAST, b"anyone?");
        one.inject(&broadcast);
        assert_eq!(host_rx.recv_timeout(timeout).unwrap(), broadcast);
        assert_eq!(two_rx.recv_timeout(timeout).unwrap(), broadcast);
        assert!(one_rx.recv_timeout(Duration::from_millis(300)).is_err());

        // A guest cannot claim somebody else's address.
        let spoofed = packet(two_ip, host_ip, b"spoof");
        one.inject(&spoofed);
        assert!(host_rx.recv_timeout(Duration::from_millis(500)).is_err());
    }

    #[test]
    fn removed_peer_is_forgotten() {
        let (host, _rx, _) = node(true, Ipv4Addr::new(10, 77, 0, 1));
        let address = Ipv4Addr::new(10, 77, 0, 2);
        host.add_peer([9u8; 32], address, Vec::new(), false, 0);
        assert_eq!(host.status().len(), 1);
        host.remove_peer(address);
        assert!(host.status().is_empty());
    }

    #[test]
    fn ephemeral_port_skips_the_previous_one() {
        for except in [0u16, EPHEMERAL_START, 40_000, EPHEMERAL_END] {
            for _ in 0..40 {
                let port = ephemeral_port(except);
                assert_ne!(port, except);
                assert!((EPHEMERAL_START..=EPHEMERAL_END).contains(&port));
            }
        }
    }

    #[test]
    fn a_new_socket_does_not_reuse_the_previous_local_port() {
        let first = bind_fresh().unwrap();
        let first_port = first.local_addr().unwrap().port();
        drop(first);
        let second = bind_fresh().unwrap();
        assert_ne!(second.local_addr().unwrap().port(), first_port);
    }
}
