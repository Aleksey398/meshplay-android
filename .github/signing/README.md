# MeshPlay release signing

`meshplay-release.p12` is the fallback release keystore used by `.github/workflows/meshplay-ci.yml`
when the private `KEYSTORE_*` secrets are not configured on the repository. It exists because
Android refuses to install unsigned APKs — without it every GitHub Release would ship
`-release-unsigned.apk` files that fail with "App not installed / package is invalid".

Properties (also written into `keystore.properties` by the workflow):

- store type: `PKCS12`
- alias: `meshplay-release`
- password: `meshplay-release-2026`

The keystore is therefore **public** (same model as the checked-in debug key in
`androidApp/config/debug.keystore`). That is acceptable for sideload distribution of this fork,
but if you want a private release identity, rotate it:

1. Generate a new keystore, e.g.
   `keytool -genkeypair -v -keystore private.p12 -storetype PKCS12 -alias meshplay-release -keyalg RSA -keysize 2048 -validity 10000`.
2. Add repository secrets (Settings → Secrets and variables → Actions):
   `KEYSTORE_BASE64` (base64 of the file, e.g. `base64 -w0 private.p12`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. The workflow always prefers the `KEYSTORE_*` secrets over this file; no workflow edit needed.

Users upgrading from a build signed with a different key (debug keystore, the older public
release key) must uninstall the previous app first — Android rejects signature-mismatched
updates.
