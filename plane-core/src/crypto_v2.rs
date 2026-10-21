//! Versioned records; layout and KDF are specified in docs/design/crypto-v2.md.
use crate::error::{CoreError, Result};
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::Aes256Gcm;
use chacha20poly1305::ChaCha20Poly1305;
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;
use std::sync::Mutex;

const HEADER: usize = 50;
const TAG: usize = 16;
const MAX_PLAINTEXT: usize = 8 * 1024 * 1024 + 1024;
const MAX_RECORDS: u64 = 1 << 32;
const MAX_BYTES: u64 = 64 * 1024 * 1024 * 1024;

pub(super) struct RecordCipher {
    suite: u8,
    master: [u8; 32],
    direction: u8,
    send: Mutex<SendState>,
    receive: Mutex<ReceiveState>,
}

struct SendState {
    salt: [u8; 32],
    key: [u8; 32],
    sequence: u64,
    bytes: u64,
}

#[derive(Default)]
struct ReceiveState {
    salt: Option<[u8; 32]>,
    key: [u8; 32],
    sequence: u64,
    bytes: u64,
}

fn error(message: &str) -> CoreError {
    CoreError::Crypto(message.to_owned())
}

fn derive(master: &[u8], salt: &[u8], suite: u8, direction: u8) -> [u8; 32] {
    let mut info = b"simpleplane/aead/v2/".to_vec();
    info.extend_from_slice(&[suite, direction]);
    let mut key = [0u8; 32];
    Hkdf::<Sha256>::new(Some(salt), master)
        .expand(&info, &mut key)
        .expect("32-byte HKDF output is valid");
    key
}

fn nonce(sequence: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&sequence.to_be_bytes());
    nonce
}

fn crypt(
    encrypt: bool,
    suite: u8,
    key: &[u8; 32],
    nonce: &[u8; 12],
    aad: &[u8],
    input: &[u8],
) -> Result<Vec<u8>> {
    let payload = Payload { msg: input, aad };
    let result = match suite {
        1 => {
            let cipher = ChaCha20Poly1305::new(key.into());
            if encrypt {
                cipher.encrypt(nonce.into(), payload)
            } else {
                cipher.decrypt(nonce.into(), payload)
            }
        }
        2 => {
            let cipher = Aes256Gcm::new(key.into());
            if encrypt {
                cipher.encrypt(nonce.into(), payload)
            } else {
                cipher.decrypt(nonce.into(), payload)
            }
        }
        _ => return Err(error("Unknown AEAD suite")),
    };
    result.map_err(|_| error("AEAD operation failed"))
}

impl RecordCipher {
    pub(super) fn new(suite: u8, raw_key: &[u8], server: bool) -> Result<Self> {
        let master: [u8; 32] = raw_key
            .try_into()
            .map_err(|_| error("AEAD v2 requires an exact 32-byte high-entropy shared key"))?;
        let direction = u8::from(server);
        let mut salt = [0u8; 32];
        rand::rngs::OsRng
            .try_fill_bytes(&mut salt)
            .map_err(|_| error("OS random source failed"))?;
        let key = derive(&master, &salt, suite, direction);
        Ok(Self {
            suite,
            master,
            direction,
            send: Mutex::new(SendState {
                salt,
                key,
                sequence: 0,
                bytes: 0,
            }),
            receive: Mutex::new(ReceiveState::default()),
        })
    }

    pub(super) fn fresh_session(&self) -> Result<Self> {
        Self::new(self.suite, &self.master, self.direction == 1)
    }

    pub(super) fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        if plaintext.len() > MAX_PLAINTEXT {
            return Err(error("Invalid AEAD plaintext length"));
        }
        let mut state = self
            .send
            .lock()
            .map_err(|_| error("AEAD send state poisoned"))?;
        if state.sequence >= MAX_RECORDS || state.bytes + plaintext.len() as u64 > MAX_BYTES {
            return Err(error("AEAD stream key limit reached; open a new stream"));
        }
        let sequence = state.sequence;
        state.sequence += 1;
        state.bytes += plaintext.len() as u64;
        let mut record = Vec::with_capacity(HEADER + plaintext.len() + TAG);
        record.extend_from_slice(b"SPC2");
        record.extend_from_slice(&[self.suite, self.direction]);
        record.extend_from_slice(&state.salt);
        record.extend_from_slice(&sequence.to_be_bytes());
        record.extend_from_slice(&(plaintext.len() as u32).to_be_bytes());
        let body = crypt(
            true,
            self.suite,
            &state.key,
            &nonce(sequence),
            &record,
            plaintext,
        )?;
        record.extend_from_slice(&body);
        Ok(record)
    }

    pub(super) fn decrypt(&self, record: &[u8]) -> Result<Vec<u8>> {
        if record.len() < HEADER + TAG || record.len() > HEADER + MAX_PLAINTEXT + TAG {
            return Err(error("Invalid AEAD record length"));
        }
        if &record[..4] != b"SPC2" || record[4] != self.suite || record[5] != 1 - self.direction {
            return Err(error(
                "AEAD version, suite or direction mismatch (no fallback)",
            ));
        }
        let salt: [u8; 32] = record[6..38].try_into().unwrap();
        let sequence = u64::from_be_bytes(record[38..46].try_into().unwrap());
        let length = u32::from_be_bytes(record[46..50].try_into().unwrap()) as usize;
        let mut state = self
            .receive
            .lock()
            .map_err(|_| error("AEAD receive state poisoned"))?;
        if length != record.len() - HEADER - TAG
            || sequence != state.sequence
            || state.salt.is_some_and(|old| old != salt)
            || sequence >= MAX_RECORDS
            || state.bytes + length as u64 > MAX_BYTES
        {
            return Err(error(
                "Invalid AEAD length, stream identity, sequence or key limit",
            ));
        }
        let key = if state.salt.is_none() {
            derive(&self.master, &salt, self.suite, 1 - self.direction)
        } else {
            state.key
        };
        let plaintext = crypt(
            false,
            self.suite,
            &key,
            &nonce(sequence),
            &record[..HEADER],
            &record[HEADER..],
        )?;
        state.key = key;
        state.salt = Some(salt);
        state.sequence += 1;
        state.bytes += length as u64;
        Ok(plaintext)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .as_chunks::<2>()
            .0
            .iter()
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect()
    }

    #[test]
    fn independent_vectors_encrypt_and_decrypt_exactly() {
        let mut senders = HashMap::new();
        let mut receivers = HashMap::new();
        let mut count = 0;
        for line in include_str!("../../docs/design/crypto-v2-vectors.tsv")
            .lines()
            .filter(|line| !line.starts_with('#'))
        {
            let f: Vec<_> = line.split('\t').collect();
            let suite = f[0].parse::<u8>().unwrap();
            let direction = f[1].parse::<u8>().unwrap();
            let sender = senders.entry((suite, direction)).or_insert_with(|| {
                let cipher = RecordCipher::new(suite, &hex(f[3]), direction == 1).unwrap();
                {
                    let mut state = cipher.send.lock().unwrap();
                    state.salt.copy_from_slice(&hex(f[4]));
                    state.key = derive(&hex(f[3]), &state.salt, suite, direction);
                    assert_eq!(state.key.as_slice(), hex(f[5]));
                }
                cipher
            });
            let receiver = receivers
                .entry((suite, direction))
                .or_insert_with(|| RecordCipher::new(suite, &hex(f[3]), direction == 0).unwrap());
            assert_eq!(sender.encrypt(&hex(f[6])).unwrap(), hex(f[7]));
            assert_eq!(receiver.decrypt(&hex(f[7])).unwrap(), hex(f[6]));
            count += 1;
        }
        assert_eq!(count, 40);
    }

    #[test]
    fn all_bytes_authenticated_and_rejected_records_do_not_change_state() {
        for suite in [1, 2] {
            let sender = RecordCipher::new(suite, &[0x42; 32], false).unwrap();
            let receiver = RecordCipher::new(suite, &[0x42; 32], true).unwrap();
            let record = sender.encrypt(&[0xab; 80]).unwrap();
            for i in 0..record.len() {
                let mut changed = record.clone();
                changed[i] ^= 1;
                assert!(
                    receiver.decrypt(&changed).is_err(),
                    "suite {suite}, offset {i}"
                );
            }
            for length in 0..record.len() {
                assert!(receiver.decrypt(&record[..length]).is_err());
            }
            assert_eq!(receiver.decrypt(&record).unwrap(), [0xab; 80]);
            assert!(receiver.decrypt(&record).is_err());
            assert!(sender.decrypt(&record).is_err());
        }
    }

    #[test]
    fn boundary_lengths_and_stream_isolation() {
        for suite in [1, 2] {
            let client = RecordCipher::new(suite, &[0x42; 32], false).unwrap();
            let server = RecordCipher::new(suite, &[0x42; 32], true).unwrap();
            for length in [
                0,
                1,
                15,
                16,
                17,
                31,
                32,
                33,
                63,
                64,
                65,
                16383,
                16384,
                16385,
                2 * 1024 * 1024,
            ] {
                let plain: Vec<u8> = (0..length).map(|i| (i % 251) as u8).collect();
                assert_eq!(
                    server.decrypt(&client.encrypt(&plain).unwrap()).unwrap(),
                    plain
                );
                assert_eq!(
                    client.decrypt(&server.encrypt(&plain).unwrap()).unwrap(),
                    plain
                );
            }
            let first = client.encrypt(b"first").unwrap();
            let second = client.encrypt(b"second").unwrap();
            assert!(server.decrypt(&second).is_err());
            assert!(RecordCipher::new(suite, &[0x43; 32], true)
                .unwrap()
                .decrypt(&first)
                .is_err());
            assert_eq!(server.decrypt(&first).unwrap(), b"first");
            assert!(server
                .decrypt(&client.fresh_session().unwrap().encrypt(b"foreign").unwrap())
                .is_err());
            assert_eq!(server.decrypt(&second).unwrap(), b"second");
            assert!(client.encrypt(&vec![0; MAX_PLAINTEXT + 1]).is_err());
        }
    }

    #[test]
    fn key_limits_and_concurrent_counters() {
        use std::sync::Arc;
        let client = Arc::new(RecordCipher::new(1, &[0x42; 32], false).unwrap());
        let tasks: Vec<_> = (0..64)
            .map(|_| {
                let cipher = Arc::clone(&client);
                std::thread::spawn(move || cipher.encrypt(b"parallel").unwrap())
            })
            .collect();
        let mut records: Vec<_> = tasks.into_iter().map(|task| task.join().unwrap()).collect();
        records.sort_by_key(|record| u64::from_be_bytes(record[38..46].try_into().unwrap()));
        let server = RecordCipher::new(1, &[0x42; 32], true).unwrap();
        for record in records {
            assert_eq!(server.decrypt(&record).unwrap(), b"parallel");
        }
        client.send.lock().unwrap().sequence = MAX_RECORDS;
        assert!(client.encrypt(b"").is_err());
        let client = RecordCipher::new(1, &[0x42; 32], false).unwrap();
        client.send.lock().unwrap().bytes = MAX_BYTES;
        assert!(client.encrypt(b"x").is_err());
    }

    #[test]
    fn rfc5869_known_answer() {
        let mut out = [0; 42];
        Hkdf::<Sha256>::new(Some(&hex("000102030405060708090a0b0c")), &[0x0b; 22])
            .expand(&hex("f0f1f2f3f4f5f6f7f8f9"), &mut out)
            .unwrap();
        assert_eq!(out.as_slice(), hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
    }
}
