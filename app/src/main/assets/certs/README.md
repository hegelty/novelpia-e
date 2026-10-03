# Bundled certificate

`NativeHttp` ships with **one** Amazon certificate under
`app/src/main/assets/certs/` as an *additional* trust anchor for connections to
`novelpia.com`:

| File | Subject | SHA-256 fingerprint (DER) | Source (official) |
| --- | --- | --- | --- |
| `AmazonRootCA1.pem` | `CN=Amazon Root CA 1, O=Amazon, C=US` | `8E:CD:E6:88:4F:3D:87:B1:12:5B:A3:1A:C3:FC:B1:3D:70:16:DE:7F:57:CC:90:4F:E1:CB:97:C6:AE:98:19:6E` | `https://www.amazontrust.com/repository/AmazonRootCA1.pem` |

The leaf certificate presented by `novelpia.com` is signed by the
`Amazon RSA 2048 M01` intermediate, which chains up to this root. `NativeHttp`
never trusts the anchor blindly: hostname verification still uses the platform
verifier, the bundled root is loaded into its own `KeyStore` and validated by
the standard PKIX provider (expiry, signatures, path constraints), and it is
only consulted after the system trust store rejects a chain. No intermediate
certificate is bundled as a trust anchor (see `docs/TLS.md`).

Do not modify or add files here without updating `docs/TLS.md`.
