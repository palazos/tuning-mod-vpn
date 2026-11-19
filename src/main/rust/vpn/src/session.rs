use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use ed25519_dalek::VerifyingKey;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256, Sha512};
use x25519_dalek::{PublicKey, StaticSecret};

pub const SESSION_LEN: usize = 32;
pub const TO_HOST: u8 = 0;
#[cfg(test)]
pub const TO_GUEST: u8 = 1;

const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

/// The room identity is an Ed25519 key, so the Diffie-Hellman keys are derived
/// from it instead of publishing a second key in the announcement.
fn host_public(ed_public: &[u8; 32]) -> Option<PublicKey> {
    let key = VerifyingKey::from_bytes(ed_public).ok()?;
    Some(PublicKey::from(key.to_montgomery().to_bytes()))
}

fn host_secret(seed: &[u8; 32]) -> StaticSecret {
    let hash = Sha512::digest(seed);
    let mut bytes = [0u8; KEY_LEN];
    bytes.copy_from_slice(&hash[..KEY_LEN]);
    StaticSecret::from(bytes)
}

fn derive_session(shared: &[u8; 32], ephemeral: &[u8; 32], host: &[u8; 32]) -> [u8; SESSION_LEN] {
    let mut hasher = Sha256::new();
    hasher.update(b"winlator-vpn-session-v1");
    hasher.update(shared);
    hasher.update(ephemeral);
    hasher.update(host);
    hasher.finalize().into()
}

// One key per direction, so a message can never be replayed back to its sender.
fn direction_key(session: &[u8; SESSION_LEN], direction: u8) -> [u8; KEY_LEN] {
    let mut hasher = Sha256::new();
    hasher.update(b"winlator-vpn-direction-v1");
    hasher.update(session);
    hasher.update([direction]);
    hasher.finalize().into()
}

/// Output layout: nonce (12) || ciphertext || tag (16).
pub fn encrypt(session: &[u8; SESSION_LEN], direction: u8, plaintext: &[u8]) -> Option<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new((&direction_key(session, direction)).into());
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce), plaintext).ok()?;
    let mut out = nonce.to_vec();
    out.extend_from_slice(&ciphertext);
    Some(out)
}

pub fn decrypt(session: &[u8; SESSION_LEN], direction: u8, packet: &[u8]) -> Option<Vec<u8>> {
    if packet.len() < NONCE_LEN + TAG_LEN {
        return None;
    }
    let cipher = ChaCha20Poly1305::new((&direction_key(session, direction)).into());
    cipher
        .decrypt(Nonce::from_slice(&packet[..NONCE_LEN]), &packet[NONCE_LEN..])
        .ok()
}

/// Guest side of a join request. Returns the session secret and the packet
/// `ephemeral public key (32) || encrypt(TO_HOST)`.
pub fn seal_join(host_ed_public: &[u8; 32], plaintext: &[u8]) -> Option<([u8; SESSION_LEN], Vec<u8>)> {
    let host = host_public(host_ed_public)?;
    let mut bytes = [0u8; KEY_LEN];
    OsRng.fill_bytes(&mut bytes);
    let ephemeral = StaticSecret::from(bytes);
    let ephemeral_public = PublicKey::from(&ephemeral);

    let shared = ephemeral.diffie_hellman(&host);
    if !shared.was_contributory() {
        return None;
    }
    let session = derive_session(shared.as_bytes(), ephemeral_public.as_bytes(), host.as_bytes());
    let body = encrypt(&session, TO_HOST, plaintext)?;

    let mut packet = ephemeral_public.as_bytes().to_vec();
    packet.extend_from_slice(&body);
    Some((session, packet))
}

/// Host side: returns the session secret and the decrypted request.
pub fn open_join(host_seed: &[u8; 32], packet: &[u8]) -> Option<([u8; SESSION_LEN], Vec<u8>)> {
    if packet.len() < KEY_LEN {
        return None;
    }
    let ephemeral_bytes: [u8; KEY_LEN] = packet[..KEY_LEN].try_into().ok()?;
    let secret = host_secret(host_seed);
    let host = PublicKey::from(&secret);

    let shared = secret.diffie_hellman(&PublicKey::from(ephemeral_bytes));
    if !shared.was_contributory() {
        return None;
    }
    let session = derive_session(shared.as_bytes(), &ephemeral_bytes, host.as_bytes());
    let plaintext = decrypt(&session, TO_HOST, &packet[KEY_LEN..])?;
    Some((session, plaintext))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity;

    #[test]
    fn join_round_trip_and_replies() {
        let seed = identity::generate_seed();
        let public = identity::public_key(&seed);

        let (guest_session, packet) = seal_join(&public, b"join please").unwrap();
        let (host_session, request) = open_join(&seed, &packet).unwrap();
        assert_eq!(request, b"join please");
        assert_eq!(guest_session, host_session);

        let reply = encrypt(&host_session, TO_GUEST, b"welcome").unwrap();
        assert_eq!(decrypt(&guest_session, TO_GUEST, &reply).unwrap(), b"welcome");

        let ping = encrypt(&guest_session, TO_HOST, b"ping").unwrap();
        assert_eq!(decrypt(&host_session, TO_HOST, &ping).unwrap(), b"ping");
    }

    #[test]
    fn other_hosts_and_tampering_are_rejected() {
        let seed = identity::generate_seed();
        let public = identity::public_key(&seed);
        let (session, mut packet) = seal_join(&public, b"secret").unwrap();

        assert!(open_join(&identity::generate_seed(), &packet).is_none());

        let last = packet.len() - 1;
        packet[last] ^= 1;
        assert!(open_join(&seed, &packet).is_none());

        // A reply must not be accepted as a request, and vice versa.
        let reply = encrypt(&session, TO_GUEST, b"x").unwrap();
        assert!(decrypt(&session, TO_HOST, &reply).is_none());
        assert!(decrypt(&session, TO_GUEST, &reply[..10]).is_none());
    }
}
