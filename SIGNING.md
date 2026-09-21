# Release signing setup

The release workflow requires these GitHub Actions repository secrets:

- `SIGNING_KEY`: Base64-encoded JKS/PKCS12 keystore.
- `ALIAS`: Key alias.
- `KEY_STORE_PASSWORD`: Keystore password.
- `KEY_PASSWORD`: Key password.

The workflow decodes the key only on pushes to `main`, signs every release APK with the same key, computes the SHA-256 signing certificate fingerprint, and publishes that fingerprint to `repo.json`.

Never commit the keystore or passwords to the repository. Keep the signing key unchanged; changing it breaks Android extension updates for existing installs.
