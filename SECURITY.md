# Security policy

## Report a vulnerability privately

Email **pandora.appbox@proton.me** with “Syncclip security” in the subject. Do not use public issues for an unpatched vulnerability. Include the affected version/platform, prerequisites, a minimal reproduction using synthetic data, and the impact you observed. Never send a real pairing link, signing key, access token or private clipboard contents. Contact us first if sensitive evidence needs a protected transfer channel.

We aim to acknowledge reports within seven days and coordinate a fix and disclosure with the reporter. This is a maintenance target, not a guaranteed response time. Please give us time to investigate before publishing exploit details. There is no paid bounty program.

## Supported versions

Security fixes target the latest published stable release. Update all paired clients and the relay/proxy together when a security release requires it. Older versions do not receive a separate maintenance branch. Self-hosters must also maintain their operating system, Docker and HTTPS proxy.

## Security boundaries

Syncclip is a single trusted device group. All devices share a content-encryption secret, but each authenticates to the relay using a separate random credential. Disconnecting a device revokes that credential, closes its active sockets, and invalidates all outstanding pairing invitations. The initial server invitation is consumed by the first enrollment and is never reopened by removing devices. Later invitations are single-use and expire in ten minutes. A disconnected device cannot enroll again without a new invitation. Saved content and encryption keys already held by that device cannot be remotely erased; rotate the group encryption secret and re-pair remaining devices if the secret itself may be compromised. Keep Android release signing keys and server provisioning files outside source control.

Content is encrypted before leaving the sender and sent through certificate-verified HTTPS. Encryption does not protect against a compromised paired device or malicious host administrator, hide traffic metadata, or erase external saved copies/backups. Automatic Linux clipboard sharing includes copied secrets while enabled. See the [privacy policy](PRIVACY.md) and [server guide](docs/HOSTING.md) for data handling and deployment requirements.

Reports involving authentication bypass, key or plaintext disclosure, cross-application access, unsafe file handling, relay isolation or dependency vulnerabilities are welcome. Please test against a local instance you control. Do not probe other people's devices or deployments or run denial-of-service tests against the maintainer's infrastructure.

## Release integrity

Use APKs and packages from the project's release pages and check `SHA256SUMS`. Android updates must retain the existing signing certificate. The expected SHA-256 certificate fingerprint is:

```text
aab4ccf9ccc71f938e22c0c2d58a84a977d415a085040d02f6c24718e18cc0bb
```

A checksum on the same download site detects mismatched files; it is not independent proof against a compromised publisher account. APK signatures preserve update identity. Never publish the signing keystore or its password.
