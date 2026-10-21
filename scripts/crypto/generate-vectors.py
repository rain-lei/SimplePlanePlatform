"""Independent OpenSSL-backed fixtures. Dev dependency: cryptography; never run in tests."""
import hashlib
import hmac
from pathlib import Path
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM, ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

ROOT = Path(__file__).resolve().parents[2]
master = bytes(range(32))
lines = ["# suite\tdirection\tsequence\tmaster_hex\tsalt_hex\tkey_hex\tplaintext_hex\trecord_hex"]
for suite in (1, 2):
    for direction in (0, 1):
        salt = bytes([suite, direction]) + bytes(range(30))
        info = b"simpleplane/aead/v2/" + bytes([suite, direction])
        key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(master)
        aead = (ChaCha20Poly1305 if suite == 1 else AESGCM)(key)
        for sequence, length in enumerate((0, 1, 15, 16, 17, 31, 32, 33, 65, 200)):
            plain = bytes((i * 31 + length) % 256 for i in range(length))
            header = b"SPC2" + bytes([suite, direction]) + salt + sequence.to_bytes(8, "big") + length.to_bytes(4, "big")
            nonce = bytes(4) + sequence.to_bytes(8, "big")
            record = header + aead.encrypt(nonce, plain, header)
            lines.append("\t".join(map(str, (suite, direction, sequence, master.hex(), salt.hex(), key.hex(), plain.hex(), record.hex()))))
(ROOT / "docs/design/crypto-v2-vectors.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")

# Independent reference for the unchanged custom AES-CTR/HMAC legacy envelope.
iv = bytes(range(16))
plain = b"independent legacy AES-CTR-HMAC vector"
enc_key = hashlib.sha256(master + b"ENC").digest()
mac_key = hashlib.sha256(master + b"MAC").digest()
encryptor = Cipher(algorithms.AES(enc_key), modes.CTR(iv)).encryptor()
body = iv + encryptor.update(plain) + encryptor.finalize()
record = body + hmac.new(mac_key, body, hashlib.sha256).digest()
(ROOT / "docs/design/crypto-legacy-ctr-vector.tsv").write_text(
    "# master_hex\tplaintext_hex\trecord_hex\n" + "\t".join((master.hex(), plain.hex(), record.hex())) + "\n", encoding="utf-8")
