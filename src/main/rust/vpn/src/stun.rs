use rand_core::{OsRng, RngCore};
use std::io::ErrorKind;
use std::net::{SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::Duration;

const SERVERS: [&str; 3] = [
    "stun.l.google.com:19302",
    "stun.cloudflare.com:3478",
    "stun1.l.google.com:19302",
];
const MAGIC: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];
const ATTEMPTS: usize = 3;
const WAIT: Duration = Duration::from_millis(700);

pub struct Probe {
    /// Our address as seen from the internet.
    pub mapped: Option<SocketAddr>,
    /// Server that answered first, reused to keep the NAT mapping alive.
    pub server: Option<SocketAddr>,
    /// True when two different servers saw two different ports (hole punching is unlikely
    /// to work); None when only one server answered.
    pub symmetric: Option<bool>,
    /// Later answers. A symmetric NAT reports a different port for each server.
    pub others: Vec<SocketAddr>,
}

pub fn binding_request() -> ([u8; 20], [u8; 12]) {
    let mut transaction = [0u8; 12];
    OsRng.fill_bytes(&mut transaction);
    let mut request = [0u8; 20];
    request[0..2].copy_from_slice(&[0x00, 0x01]);
    request[4..8].copy_from_slice(&MAGIC);
    request[8..20].copy_from_slice(&transaction);
    (request, transaction)
}

pub fn probe(socket: &UdpSocket) -> Probe {
    let mut answers: Vec<(SocketAddr, SocketAddr)> = Vec::new();
    for name in SERVERS {
        if answers.len() == 2 {
            break;
        }
        let Some(server) = name
            .to_socket_addrs()
            .ok()
            .and_then(|mut addrs| addrs.find(|a| a.is_ipv4()))
        else {
            continue;
        };
        if let Some(mapped) = query(socket, server) {
            answers.push((server, mapped));
        }
    }
    Probe {
        mapped: answers.first().map(|a| a.1),
        server: answers.first().map(|a| a.0),
        symmetric: (answers.len() == 2).then(|| answers[0].1.port() != answers[1].1.port()),
        others: answers.iter().skip(1).map(|a| a.1).collect(),
    }
}

#[cfg(test)]
const PUNCH_LIMIT: usize = 12;

/// Ports the peer should probe when each destination got its own external port.
/// The handshake is the next new destination after these probes, so the likely
/// port is one step past the last answer. Neighbours cover an extra datagram.
#[cfg(test)]
pub fn punch_ports(probe: &Probe) -> Vec<SocketAddr> {
    let Some(mapped) = probe.mapped else { return Vec::new() };
    let Some(second) = probe.others.iter().copied().find(|addr| addr.ip() == mapped.ip() && addr.port() != mapped.port()) else {
        return Vec::new();
    };
    let first = i32::from(mapped.port());
    let last = i32::from(second.port());
    let step = last - first;
    let mut ports = Vec::new();
    let mut push = |port: i32| {
        if ports.len() >= PUNCH_LIMIT {
            return;
        }
        if (1..65536).contains(&port) {
            let port = port as u16;
            if port != mapped.port() && !ports.contains(&port) {
                ports.push(port);
            }
        }
    };
    if step != 0 {
        let guess = last + step;
        push(guess);
        push(guess + step);
        push(guess + step * 2);
        push(guess + 1);
        push(guess - 1);
        push(guess + 2);
        push(guess - 2);
    }
    // A NAT that only steps by one still looks like a small gap when another
    // flow took a port between the two probes.
    if step.abs() <= 4 {
        for offset in 1..=6 {
            push(last + offset);
        }
    }
    ports.into_iter().map(|port| SocketAddr::new(mapped.ip(), port)).collect()
}

fn query(socket: &UdpSocket, server: SocketAddr) -> Option<SocketAddr> {
    let (request, transaction) = binding_request();
    socket.set_read_timeout(Some(WAIT)).ok()?;
    let mut buffer = [0u8; 512];
    for _ in 0..ATTEMPTS {
        socket.send_to(&request, server).ok()?;
        loop {
            match socket.recv_from(&mut buffer) {
                Ok((length, from)) if from == server => {
                    if let Some(mapped) = parse_response(&buffer[..length], &transaction) {
                        return Some(mapped);
                    }
                }
                Ok(_) => {}
                Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => break,
                Err(_) => break,
            }
        }
    }
    None
}

fn parse_response(data: &[u8], transaction: &[u8; 12]) -> Option<SocketAddr> {
    if data.len() < 20 || data[0..2] != [0x01, 0x01] || data[4..8] != MAGIC || data[8..20] != transaction[..] {
        return None;
    }
    let end = (20 + usize::from(u16::from_be_bytes([data[2], data[3]]))).min(data.len());
    let mut offset = 20;
    let mut plain: Option<SocketAddr> = None;
    while offset + 4 <= end {
        let kind = u16::from_be_bytes([data[offset], data[offset + 1]]);
        let length = usize::from(u16::from_be_bytes([data[offset + 2], data[offset + 3]]));
        let value = data.get(offset + 4..offset + 4 + length)?;
        if length >= 8 && value[1] == 1 {
            let port = u16::from_be_bytes([value[2], value[3]]);
            let octets = [value[4], value[5], value[6], value[7]];
            match kind {
                // XOR-MAPPED-ADDRESS
                0x0020 => {
                    let xored = [
                        octets[0] ^ MAGIC[0],
                        octets[1] ^ MAGIC[1],
                        octets[2] ^ MAGIC[2],
                        octets[3] ^ MAGIC[3],
                    ];
                    return Some(SocketAddr::from((xored, port ^ 0x2112)));
                }
                // MAPPED-ADDRESS, only for servers that do not send the XOR form
                0x0001 => plain = Some(SocketAddr::from((octets, port))),
                _ => {}
            }
        }
        offset += 4 + ((length + 3) & !3);
    }
    plain
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_xor_mapped_address() {
        let transaction = [7u8; 12];
        let mut response = vec![0x01, 0x01, 0x00, 0x0C, 0x21, 0x12, 0xA4, 0x42];
        response.extend_from_slice(&transaction);
        // 203.0.113.9:40000
        let port = 40000u16 ^ 0x2112;
        response.extend_from_slice(&[0x00, 0x20, 0x00, 0x08, 0x00, 0x01]);
        response.extend_from_slice(&port.to_be_bytes());
        response.extend_from_slice(&[203 ^ 0x21, 0 ^ 0x12, 113 ^ 0xA4, 9 ^ 0x42]);

        let mapped = parse_response(&response, &transaction).unwrap();
        assert_eq!(mapped, "203.0.113.9:40000".parse().unwrap());
        assert!(parse_response(&response, &[8u8; 12]).is_none());
    }

    fn probe_at(ports: &[u16]) -> Probe {
        let mapped = ports.first().map(|port| SocketAddr::from(([203, 0, 113, 9], *port)));
        Probe {
            mapped,
            server: None,
            symmetric: (ports.len() == 2).then(|| ports[0] != ports[1]),
            others: ports.iter().skip(1).map(|port| SocketAddr::from(([203, 0, 113, 9], *port))).collect(),
        }
    }

    #[test]
    fn same_port_needs_no_extra_punches() {
        assert!(punch_ports(&probe_at(&[40000, 40000])).is_empty());
        assert!(punch_ports(&probe_at(&[40000])).is_empty());
    }

    #[test]
    fn symmetric_nat_punches_the_next_allocation() {
        let ports = punch_ports(&probe_at(&[40000, 40001]));
        assert_eq!(ports[0].port(), 40002);
        assert!(ports.iter().any(|addr| addr.port() == 40003));
        assert!(ports.iter().all(|addr| addr.port() != 40000));
    }

    #[test]
    fn a_wide_gap_aims_at_the_same_stride() {
        let ports = punch_ports(&probe_at(&[40000, 40050]));
        assert_eq!(ports[0].port(), 40100);
        assert!(ports.iter().any(|addr| addr.port() == 40150));
        assert!(!ports.iter().any(|addr| addr.port() == 40051));
    }

    // Needs internet access: cargo test -- --ignored --nocapture
    #[test]
    #[ignore]
    fn live_servers_answer() {
        let socket = UdpSocket::bind("0.0.0.0:0").unwrap();
        let probe = probe(&socket);
        println!("mapped={:?} symmetric={:?} server={:?}", probe.mapped, probe.symmetric, probe.server);
        assert!(probe.mapped.is_some());
    }
}
