use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

const ROOM_ID_LEN: usize = 9;
// No 0/O/1/I so ids are easy to read aloud.
const ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

pub fn generate_seed() -> [u8; 32] {
    let mut seed = [0u8; 32];
    OsRng.fill_bytes(&mut seed);
    seed
}

pub fn public_key(seed: &[u8; 32]) -> [u8; 32] {
    SigningKey::from_bytes(seed).verifying_key().to_bytes()
}

pub fn sign(seed: &[u8; 32], message: &[u8]) -> [u8; 64] {
    SigningKey::from_bytes(seed).sign(message).to_bytes()
}

pub fn verify(public: &[u8; 32], message: &[u8], signature: &[u8; 64]) -> bool {
    match VerifyingKey::from_bytes(public) {
        Ok(key) => key.verify(message, &Signature::from_bytes(signature)).is_ok(),
        Err(_) => false,
    }
}

/// The id is derived from the key (45 bits of its hash), so nobody else can
/// publish under an existing room id without brute forcing a matching key.
pub fn room_id(public: &[u8; 32]) -> String {
    let digest = Sha256::digest(public);
    let bits = digest[..6].iter().fold(0u64, |acc, byte| (acc << 8) | u64::from(*byte));
    (0..ROOM_ID_LEN)
        .map(|i| ALPHABET[((bits >> (43 - i * 5)) & 31) as usize] as char)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_and_verify() {
        let seed = generate_seed();
        let public = public_key(&seed);
        let signature = sign(&seed, b"room announcement");
        assert!(verify(&public, b"room announcement", &signature));
    }

    #[test]
    fn rejects_tampered_message_and_wrong_key() {
        let seed = generate_seed();
        let public = public_key(&seed);
        let signature = sign(&seed, b"players=3");
        assert!(!verify(&public, b"players=4", &signature));

        let other = public_key(&generate_seed());
        assert!(!verify(&other, b"players=3", &signature));
    }

    #[test]
    fn room_id_is_stable_and_well_formed() {
        let public = public_key(&generate_seed());
        let id = room_id(&public);
        assert_eq!(id, room_id(&public));
        assert_eq!(id.len(), ROOM_ID_LEN);
        assert!(id.bytes().all(|b| ALPHABET.contains(&b)));
        assert_ne!(id, room_id(&public_key(&generate_seed())));
    }
}
