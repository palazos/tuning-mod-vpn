use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use rand_core::{OsRng, RngCore};

const BUF_SIZE: usize = 2048;

fn new_pair() -> (Tunn, Tunn) {
    let a_secret = StaticSecret::random_from_rng(OsRng);
    let b_secret = StaticSecret::random_from_rng(OsRng);
    let a_public = PublicKey::from(&a_secret);
    let b_public = PublicKey::from(&b_secret);

    let a = Tunn::new(a_secret, b_public, None, None, OsRng.next_u32(), None);
    let b = Tunn::new(b_secret, a_public, None, None, OsRng.next_u32(), None);
    (a, b)
}

fn network_bytes(result: TunnResult, step: &str) -> Result<Vec<u8>, String> {
    match result {
        TunnResult::WriteToNetwork(data) => Ok(data.to_vec()),
        TunnResult::Err(e) => Err(format!("{step}: {e:?}")),
        _ => Err(format!("{step}: unexpected result")),
    }
}

// Minimal valid IPv4/UDP packet; the checksum is not validated by the tunnel.
fn sample_packet() -> Vec<u8> {
    let payload = [0xde, 0xad, 0xbe, 0xef];
    let total = (20 + payload.len()) as u16;
    let mut p = vec![0x45, 0x00];
    p.extend_from_slice(&total.to_be_bytes());
    p.extend_from_slice(&[0, 0, 0, 0, 64, 17, 0, 0]);
    p.extend_from_slice(&[10, 77, 0, 2]);
    p.extend_from_slice(&[10, 77, 0, 3]);
    p.extend_from_slice(&payload);
    p
}

/// Full handshake plus one encrypted data packet between two in-memory tunnels.
pub fn run() -> Result<String, String> {
    let (mut a, mut b) = new_pair();
    let mut buf = [0u8; BUF_SIZE];

    let init = network_bytes(a.format_handshake_initiation(&mut buf, false), "initiation")?;
    let resp = network_bytes(b.decapsulate(None, &init, &mut buf), "response")?;
    let keepalive = network_bytes(a.decapsulate(None, &resp, &mut buf), "keepalive")?;
    match b.decapsulate(None, &keepalive, &mut buf) {
        TunnResult::Done => {}
        _ => return Err("handshake did not complete".into()),
    }

    let packet = sample_packet();
    let encrypted = network_bytes(a.encapsulate(&packet, &mut buf), "encapsulate")?;
    let mut out = [0u8; BUF_SIZE];
    match b.decapsulate(None, &encrypted, &mut out) {
        TunnResult::WriteToTunnelV4(plain, _) if plain == packet.as_slice() => Ok(format!(
            "handshake ok, {} bytes -> {} bytes encrypted",
            packet.len(),
            encrypted.len()
        )),
        TunnResult::WriteToTunnelV4(..) => Err("decrypted packet differs".into()),
        TunnResult::Err(e) => Err(format!("decapsulate: {e:?}")),
        _ => Err("decapsulate: unexpected result".into()),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn handshake_and_roundtrip() {
        let summary = super::run().expect("self test failed");
        println!("{summary}");
    }
}
