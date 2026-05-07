# Security Policy

## Supported versions

| Version        | Supported |
|----------------|-----------|
| Latest release | ✓          |
| Older versions | ✗          |

## Reporting a vulnerability

Open a [GitHub Security Advisory](https://github.com/Kitty-Hivens/Aura-Launcher/security/advisories/new) or email the maintainer directly.

Please include:
- Affected version
- Steps to reproduce
- Potential impact

## Known design decisions

A few things that look like vulnerabilities but are intentional:

**SSL bypass** — The launcher can connect to `smartycraft.ru` with certificate verification disabled after explicit user confirmation. This exists because the server's SSL certificate has been known to expire. The bypass is entirely opt-in and clearly labelled in the UI.

**AES-256-GCM credential encryption** — Credentials are encrypted with a key derived from machine-specific properties (username, home dir, OS name) via PBKDF2. This is not OS Keyring/TPM-backed and does not protect against local-privilege-escalation attacks on the same machine. It prevents casual file-copy credential theft.

**SOCKS proxy hardcoded** — The proxy credentials in `Network.Proxy` are the game server's public SOCKS proxy, not private secrets.
