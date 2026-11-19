//! Asks the home router to forward a UDP port to us, which makes the device reachable even
//! behind a strict NAT. Tries UPnP IGD, then PCP, then NAT-PMP. Everything is best effort
//! and time boxed.
//!
//! The router is looked up through the gateway address the caller gives us. Multicast
//! discovery alone is not enough: once the room's TUN is up its routes capture multicast
//! traffic, so an SSDP search never leaves the device.

use rand_core::{OsRng, RngCore};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};

const SEARCH_TIMEOUT: Duration = Duration::from_millis(1500);
const HTTP_TIMEOUT: Duration = Duration::from_secs(2);
const LEASE_SECS: u32 = 3600;
const DESCRIPTION: &str = "Winlator Rooms";
const PORT_TRIES: u16 = 3;
const SSDP_PORT: u16 = 1900;
const PCP_PORT: u16 = 5351;
const PCP_LEN: usize = 60;
const GATEWAY_WAIT: Duration = Duration::from_millis(300);
const GATEWAY_TRIES: usize = 2;

fn pin(fd: i32, network: u64) {
    let _ = crate::engine::pin_fd(fd, network);
}

fn bind_udp(network: u64) -> Option<UdpSocket> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        pin(socket.as_raw_fd(), network);
    }
    Some(socket)
}

#[derive(Clone)]
enum Method {
    Upnp { control: Url, service: String, internal_ip: Ipv4Addr },
    Pcp { target: SocketAddr, internal_ip: Ipv4Addr, nonce: [u8; 12] },
    NatPmp { target: SocketAddr },
}

#[derive(Clone)]
pub struct Mapping {
    method: Method,
    pub external_port: u16,
    /// Address of the router on the internet side, when it is a public one.
    pub external_ip: Option<Ipv4Addr>,
    internal_port: u16,
    // Physical network the router is reached on. The room VPN must not capture these packets.
    network: u64,
}

#[derive(Clone)]
struct Url {
    host: String,
    port: u16,
    path: String,
}

impl Url {
    fn parse(text: &str) -> Option<Url> {
        let rest = text.trim().strip_prefix("http://")?;
        let (authority, path) = match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, "/"),
        };
        let (host, port) = match authority.rsplit_once(':') {
            Some((host, port)) => (host, port.parse().ok()?),
            None => (authority, 80),
        };
        Some(Url { host: host.to_string(), port, path: path.to_string() })
    }

    fn resolve(&self, reference: &str) -> Option<Url> {
        if reference.starts_with("http://") {
            return Url::parse(reference);
        }
        let path = if reference.starts_with('/') {
            reference.to_string()
        } else {
            let base = self.path.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("");
            format!("{base}/{reference}")
        };
        Some(Url { host: self.host.clone(), port: self.port, path })
    }
}

/// `gateway` is the router of the physical network, when the caller knows it.
/// `network` is the Android network that reaches it. Zero leaves the socket unpinned.
pub fn map(internal_port: u16, gateway: Option<Ipv4Addr>, network: u64) -> Option<Mapping> {
    map_upnp(internal_port, gateway, network).or_else(|| {
        let gateway = gateway?;
        map_pcp(internal_port, SocketAddr::from((gateway, PCP_PORT)), network)
            .or_else(|| map_nat_pmp(internal_port, SocketAddr::from((gateway, PCP_PORT)), network))
    })
}

fn map_upnp(internal_port: u16, gateway: Option<Ipv4Addr>, network: u64) -> Option<Mapping> {
    let (location, router) = discover(gateway, network)?;
    let description_url = Url::parse(&location)?;
    let internal_ip = local_address_towards(router, network)?;
    let description = http(&description_url, "GET", &[], "", network)?;
    let (service, control) = find_service(&description, &description_url)?;

    for offset in 0..PORT_TRIES {
        let external_port = internal_port.checked_add(offset)?;
        let added = add_mapping(&control, &service, external_port, internal_port, internal_ip, LEASE_SECS, network)
            // Some routers only accept permanent mappings.
            || add_mapping(&control, &service, external_port, internal_port, internal_ip, 0, network);
        if added {
            let external_ip = soap(&control, &service, "GetExternalIPAddress", "", network)
                .and_then(|body| tag(&body, "NewExternalIPAddress").map(str::to_string))
                .and_then(|text| text.trim().parse::<Ipv4Addr>().ok())
                .filter(|ip| is_public(*ip));
            return Some(Mapping {
                method: Method::Upnp { control, service, internal_ip },
                external_port,
                external_ip,
                internal_port,
                network,
            });
        }
    }
    None
}

fn map_pcp(internal_port: u16, target: SocketAddr, network: u64) -> Option<Mapping> {
    let internal_ip = local_address_towards(target, network)?;
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let (external_port, external_ip) = pcp_request(target, internal_ip, &nonce, internal_port, internal_port, LEASE_SECS, network)?;
    Some(Mapping {
        method: Method::Pcp { target, internal_ip, nonce },
        external_port,
        external_ip,
        internal_port,
        network,
    })
}

fn map_nat_pmp(internal_port: u16, target: SocketAddr, network: u64) -> Option<Mapping> {
    let external_ip = nat_pmp_external_ip(target, network)?;
    let external_port = nat_pmp_request(target, internal_port, internal_port, LEASE_SECS, network)?;
    Some(Mapping {
        method: Method::NatPmp { target },
        external_port,
        external_ip: Some(external_ip).filter(|ip| is_public(*ip)),
        internal_port,
        network,
    })
}

impl Mapping {
    pub fn renew(&self) -> bool {
        match &self.method {
            Method::Upnp { control, service, internal_ip } => {
                add_mapping(control, service, self.external_port, self.internal_port, *internal_ip, LEASE_SECS, self.network)
            }
            Method::Pcp { target, internal_ip, nonce } => pcp_request(
                *target,
                *internal_ip,
                nonce,
                self.internal_port,
                self.external_port,
                LEASE_SECS,
                self.network,
            )
            .is_some(),
            Method::NatPmp { target } => {
                nat_pmp_request(*target, self.internal_port, self.external_port, LEASE_SECS, self.network).is_some()
            }
        }
    }

    pub fn remove(&self) {
        match &self.method {
            Method::Upnp { control, service, .. } => {
                let args = format!(
                    "<NewRemoteHost></NewRemoteHost><NewExternalPort>{}</NewExternalPort><NewProtocol>UDP</NewProtocol>",
                    self.external_port
                );
                let _ = soap(control, service, "DeletePortMapping", &args, self.network);
            }
            Method::Pcp { target, internal_ip, nonce } => {
                let _ = pcp_request(*target, *internal_ip, nonce, self.internal_port, self.external_port, 0, self.network);
            }
            Method::NatPmp { target } => {
                let _ = nat_pmp_request(*target, self.internal_port, 0, 0, self.network);
            }
        }
    }
}

// One request to the router's PCP / NAT-PMP port; returns the first datagram `accept` likes.
fn gateway_exchange(target: SocketAddr, request: &[u8], network: u64, accept: impl Fn(&[u8]) -> bool) -> Option<Vec<u8>> {
    let socket = bind_udp(network)?;
    socket.connect(target).ok()?;
    socket.set_read_timeout(Some(GATEWAY_WAIT)).ok()?;
    let mut buffer = [0u8; 128];
    for _ in 0..GATEWAY_TRIES {
        socket.send(request).ok()?;
        let deadline = Instant::now() + GATEWAY_WAIT;
        while Instant::now() < deadline {
            match socket.recv(&mut buffer) {
                Ok(length) if accept(&buffer[..length]) => return Some(buffer[..length].to_vec()),
                Ok(_) => {}
                Err(_) => break,
            }
        }
    }
    None
}

fn pcp_packet(internal_ip: Ipv4Addr, nonce: &[u8; 12], internal_port: u16, suggested_port: u16, lifetime: u32) -> [u8; PCP_LEN] {
    let mut packet = [0u8; PCP_LEN];
    packet[0] = 2;
    packet[1] = 1;
    packet[4..8].copy_from_slice(&lifetime.to_be_bytes());
    // The client address travels as an IPv4-mapped IPv6 address.
    packet[18..20].copy_from_slice(&[0xFF, 0xFF]);
    packet[20..24].copy_from_slice(&internal_ip.octets());
    packet[24..36].copy_from_slice(nonce);
    packet[36] = 17;
    packet[40..42].copy_from_slice(&internal_port.to_be_bytes());
    packet[42..44].copy_from_slice(&suggested_port.to_be_bytes());
    packet
}

fn parse_pcp(reply: &[u8]) -> Option<(u16, Option<Ipv4Addr>)> {
    if reply.get(3) != Some(&0) {
        return None;
    }
    let port = u16::from_be_bytes([reply[42], reply[43]]);
    let ip = Ipv4Addr::new(reply[56], reply[57], reply[58], reply[59]);
    (port != 0).then(|| (port, Some(ip).filter(|ip| is_public(*ip))))
}

fn pcp_request(
    target: SocketAddr,
    internal_ip: Ipv4Addr,
    nonce: &[u8; 12],
    internal_port: u16,
    suggested_port: u16,
    lifetime: u32,
    network: u64,
) -> Option<(u16, Option<Ipv4Addr>)> {
    let request = pcp_packet(internal_ip, nonce, internal_port, suggested_port, lifetime);
    let reply = gateway_exchange(target, &request, network, |r| {
        r.len() >= PCP_LEN && r[0] == 2 && r[1] == 0x81 && r[24..36] == nonce[..]
    })?;
    parse_pcp(&reply)
}

fn nat_pmp_external_ip(target: SocketAddr, network: u64) -> Option<Ipv4Addr> {
    let reply = gateway_exchange(target, &[0, 0], network, |r| r.len() >= 12 && r[0] == 0 && r[1] == 128)?;
    (reply[2..4] == [0, 0]).then(|| Ipv4Addr::new(reply[8], reply[9], reply[10], reply[11]))
}

fn nat_pmp_request(target: SocketAddr, internal_port: u16, suggested_port: u16, lifetime: u32, network: u64) -> Option<u16> {
    let mut request = [0u8; 12];
    request[1] = 1;
    request[4..6].copy_from_slice(&internal_port.to_be_bytes());
    request[6..8].copy_from_slice(&suggested_port.to_be_bytes());
    request[8..12].copy_from_slice(&lifetime.to_be_bytes());
    let reply = gateway_exchange(target, &request, network, |r| r.len() >= 16 && r[0] == 0 && r[1] == 129)?;
    let port = u16::from_be_bytes([reply[10], reply[11]]);
    (reply[2..4] == [0, 0] && (port != 0 || lifetime == 0)).then_some(port)
}

fn is_public(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    let carrier_grade = octets[0] == 100 && (64..128).contains(&octets[1]);
    !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() || carrier_grade)
}

fn discover(gateway: Option<Ipv4Addr>, network: u64) -> Option<(String, SocketAddr)> {
    let socket = bind_udp(network)?;
    socket.set_read_timeout(Some(Duration::from_millis(300))).ok()?;
    let request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n\
                   ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
    let multicast = SocketAddr::from((Ipv4Addr::new(239, 255, 255, 250), SSDP_PORT));
    // Asking the gateway directly comes first; multicast is only a fallback for routers that
    // ignore unicast searches (and for callers that do not know the gateway).
    let targets = match gateway {
        Some(ip) => {
            let direct = SocketAddr::from((ip, SSDP_PORT));
            vec![direct, multicast, direct]
        }
        None => vec![multicast, multicast],
    };
    let started = Instant::now();
    let deadline = started + SEARCH_TIMEOUT;
    let mut buffer = [0u8; 2048];
    let mut sent = 0;
    while Instant::now() < deadline {
        let due = (started.elapsed().as_millis() / 500) as usize + 1;
        while sent < due.min(targets.len()) {
            let _ = socket.send_to(request.as_bytes(), targets[sent]);
            sent += 1;
        }
        if let Ok((length, from)) = socket.recv_from(&mut buffer) {
            let text = String::from_utf8_lossy(&buffer[..length]);
            let location = text.lines().find_map(|line| {
                let (name, value) = line.split_once(':')?;
                name.trim().eq_ignore_ascii_case("location").then(|| value.trim().to_string())
            });
            if let Some(location) = location {
                return Some((location, from));
            }
        }
    }
    None
}

// Connecting a UDP socket sends nothing; it only asks the OS which local address would be used.
fn local_address_towards(router: SocketAddr, network: u64) -> Option<Ipv4Addr> {
    let socket = bind_udp(network)?;
    socket.connect(router).ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(ip) => Some(ip),
        _ => None,
    }
}

fn find_service(description: &str, base: &Url) -> Option<(String, Url)> {
    let mut rest = description;
    while let Some(start) = rest.find("<serviceType>") {
        rest = &rest[start..];
        let service = tag(rest, "serviceType")?.trim().to_string();
        let next = rest[1..].find("<serviceType>").map(|i| i + 1).unwrap_or(rest.len());
        if service.contains("WANIPConnection") || service.contains("WANPPPConnection") {
            if let Some(control) = tag(&rest[..next], "controlURL").and_then(|c| base.resolve(c.trim())) {
                return Some((service, control));
            }
        }
        rest = &rest[next..];
    }
    None
}

fn tag<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    let open = format!("<{name}>");
    let close = format!("</{name}>");
    let start = text.find(&open)? + open.len();
    let end = text[start..].find(&close)? + start;
    Some(&text[start..end])
}

fn add_mapping(
    control: &Url,
    service: &str,
    external_port: u16,
    internal_port: u16,
    internal_ip: Ipv4Addr,
    lease: u32,
    network: u64,
) -> bool {
    let args = format!(
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>{external_port}</NewExternalPort>\
         <NewProtocol>UDP</NewProtocol><NewInternalPort>{internal_port}</NewInternalPort>\
         <NewInternalClient>{internal_ip}</NewInternalClient><NewEnabled>1</NewEnabled>\
         <NewPortMappingDescription>{DESCRIPTION}</NewPortMappingDescription>\
         <NewLeaseDuration>{lease}</NewLeaseDuration>"
    );
    soap(control, service, "AddPortMapping", &args, network).is_some()
}

fn soap(control: &Url, service: &str, action: &str, arguments: &str, network: u64) -> Option<String> {
    let body = format!(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>\
         <u:{action} xmlns:u=\"{service}\">{arguments}</u:{action}></s:Body></s:Envelope>"
    );
    let headers = [
        ("Content-Type", "text/xml; charset=\"utf-8\"".to_string()),
        ("SOAPAction", format!("\"{service}#{action}\"")),
    ];
    http(control, "POST", &headers, &body, network)
}

// HTTP/1.0 so the router closes the connection and we can simply read until the end.
// Returns the body of a 200 answer.
fn http(url: &Url, method: &str, headers: &[(&str, String)], body: &str, network: u64) -> Option<String> {
    let address = (url.host.as_str(), url.port).to_socket_addrs().ok()?.next()?;
    // Unicast to the router's LAN address is outside the room routes. The search socket is
    // what has to be pinned, because that one sends multicast.
    let _ = network;
    let mut stream = TcpStream::connect_timeout(&address, HTTP_TIMEOUT).ok()?;
    stream.set_read_timeout(Some(HTTP_TIMEOUT)).ok()?;
    stream.set_write_timeout(Some(HTTP_TIMEOUT)).ok()?;

    let mut request = format!("{method} {} HTTP/1.0\r\nHost: {}:{}\r\n", url.path, url.host, url.port);
    for (name, value) in headers {
        request.push_str(&format!("{name}: {value}\r\n"));
    }
    if method == "POST" {
        request.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    request.push_str("\r\n");
    request.push_str(body);
    stream.write_all(request.as_bytes()).ok()?;

    let mut response = Vec::new();
    let _ = stream.take(256 * 1024).read_to_end(&mut response);
    let text = String::from_utf8_lossy(&response).into_owned();
    let status_ok = text.lines().next().map(|line| line.contains(" 200")).unwrap_or(false);
    if !status_ok {
        return None;
    }
    Some(text.split_once("\r\n\r\n").map(|(_, body)| body.to_string()).unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    const DESCRIPTION_XML: &str = "<root><device><serviceList>\
        <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>\
        <controlURL>/l3f</controlURL></service></serviceList>\
        <deviceList><device><deviceList><device><serviceList>\
        <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>\
        <controlURL>/ctl/IPConn</controlURL></service></serviceList></device></deviceList></device></deviceList>\
        </device></root>";

    #[test]
    fn finds_the_wan_service_and_resolves_urls() {
        let base = Url::parse("http://192.168.1.1:5000/rootDesc.xml").unwrap();
        let (service, control) = find_service(DESCRIPTION_XML, &base).unwrap();
        assert!(service.contains("WANIPConnection"));
        assert_eq!((control.host.as_str(), control.port, control.path.as_str()), ("192.168.1.1", 5000, "/ctl/IPConn"));

        let relative = base.resolve("ctl/x").unwrap();
        assert_eq!(relative.path, "/ctl/x");
    }

    #[test]
    fn only_public_addresses_count() {
        assert!(is_public("186.48.96.226".parse().unwrap()));
        assert!(!is_public("192.168.0.5".parse().unwrap()));
        assert!(!is_public("100.72.0.1".parse().unwrap()));
    }

    // A router that answers PCP / NAT-PMP requests once, on loopback.
    fn fake_gateway(answer: impl Fn(&[u8]) -> Vec<u8> + Send + 'static) -> SocketAddr {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let address = socket.local_addr().unwrap();
        std::thread::spawn(move || {
            let mut buffer = [0u8; 128];
            while let Ok((length, from)) = socket.recv_from(&mut buffer) {
                let _ = socket.send_to(&answer(&buffer[..length]), from);
            }
        });
        address
    }

    #[test]
    fn pcp_mapping_is_requested_and_parsed() {
        let target = fake_gateway(|request| {
            assert_eq!(request.len(), PCP_LEN);
            assert_eq!((request[0], request[1]), (2, 1));
            let mut reply = vec![0u8; PCP_LEN];
            reply[0] = 2;
            reply[1] = 0x81;
            reply[24..36].copy_from_slice(&request[24..36]);
            reply[42..44].copy_from_slice(&50000u16.to_be_bytes());
            reply[54..56].copy_from_slice(&[0xFF, 0xFF]);
            reply[56..60].copy_from_slice(&[203, 0, 113, 9]);
            reply
        });
        let mapping = map_pcp(40000, target, 0).expect("mapping");
        assert_eq!(mapping.external_port, 50000);
        assert_eq!(mapping.external_ip, Some("203.0.113.9".parse().unwrap()));
    }

    #[test]
    fn nat_pmp_mapping_is_requested_and_parsed() {
        let target = fake_gateway(|request| {
            if request.len() == 2 {
                vec![0, 128, 0, 0, 0, 0, 0, 1, 203, 0, 113, 9]
            } else {
                let mut reply = vec![0, 129, 0, 0, 0, 0, 0, 1];
                reply.extend_from_slice(&request[4..6]);
                reply.extend_from_slice(&41000u16.to_be_bytes());
                reply.extend_from_slice(&3600u32.to_be_bytes());
                reply
            }
        });
        let mapping = map_nat_pmp(40000, target, 0).expect("mapping");
        assert_eq!(mapping.external_port, 41000);
        assert_eq!(mapping.external_ip, Some("203.0.113.9".parse().unwrap()));
    }

    #[test]
    fn silent_gateway_gives_no_mapping() {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let target = socket.local_addr().unwrap();
        assert!(map_nat_pmp(40000, target, 0).is_none());
        assert!(map_pcp(40000, target, 0).is_none());
    }

    // Read-only check against the real router: cargo test live_router -- --ignored --nocapture
    #[test]
    #[ignore]
    fn live_router_is_discovered() {
        let (location, router) = discover(None, 0).expect("no UPnP router answered");
        let url = Url::parse(&location).unwrap();
        let description = http(&url, "GET", &[], "", 0).expect("description");
        let (service, control) = find_service(&description, &url).expect("WAN service");
        let external = soap(&control, &service, "GetExternalIPAddress", "", 0);
        println!("router={router} service={service} control={}:{}{}", control.host, control.port, control.path);
        println!("external={:?}", external.as_deref().and_then(|b| tag(b, "NewExternalIPAddress")));
    }
}
