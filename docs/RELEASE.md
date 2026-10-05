# Release and in-app updates

Masroof releases and nightly builds are published via **GitHub Actions** using **PR slash commands** on merged pull requests or manual workflow dispatch. You do **not** need to manually edit `app/build.gradle.kts` or open version-bump PRs.

The app checks for updates using your personal GitHub token and installs APKs directly without Google Play.

## Development and publishing workflow

```text
PR → CI checks pass → Review & Approve → Merge to main → /nightly or /release
```

`/release` and `/nightly` wait if main CI is still running. Publishing still requires `unit-test` and `static-analysis` to succeed.

After your PR is merged, publish by commenting on the merged PR:

### 1. Test build (Nightly)
Comment on the merged PR:
```text
/nightly
```
- Creates a GitHub **Pre-release** tagged `v<next-version>-nightly.<n>` (e.g. `v0.3.18-nightly.1`).
- Bumps global `versionCode` monotonically.
- Will **not** prompt regular stable users to update.

### 2. Stable release (Patch)
Comment on the merged PR:
```text
/release
```
- Or `/release patch`.
- Calculates the next semantic patch release (e.g. `v0.3.17` $\rightarrow$ `v0.3.18`).
- Bumps global `versionCode` monotonically.
- Creates an official GitHub Release and signed APK.
- Prompted to stable users via in-app update.

### 3. Minor and major releases
Comment on the merged PR:
```text
/release minor
```
- Bumps the minor segment (e.g. `v0.3.17` $\rightarrow$ `v0.4.0`).

```text
/release major
```
- Bumps the major segment (e.g. `v0.3.17` $\rightarrow$ `v1.0.0`).

---

## Secondary option: Manual workflow dispatch

You can also trigger builds directly from the GitHub Actions UI:
1. Go to **Actions → Release → Run workflow**.
2. Select **Release type**: `release` or `nightly`.
3. Select **Version bump**: `patch`, `minor`, or `major`.
4. (Optional) Provide a target commit ref or branch (defaults to `main`).
5. (Optional) Set **Skip green CI gate** only for emergencies.
6. Click **Run workflow**.

Release verifies the target commit has a successful **CI** check, then builds the signed APK. It does **not** re-run the full test suite.

---

## Feedback on PRs

When you post a slash command on a merged PR:
1. **Validation & Acceptance**: The workflow checks your permissions and PR merge status, then reacts with `👀` and posts a status acknowledgement.
2. **Rejection**: If the command is posted on an unmerged PR or by an unauthorized user, the workflow reacts with `-1` and posts an explanation comment.
3. **Publishing Success**: Once the build, signing, and verification succeed, the workflow reacts with `🚀` and posts a comment with the new version, `versionCode`, release notes link, and APK filename.

---

## One-time setup

### 1. Create a release keystore (local, once)

```bash
keytool -genkey -v -keystore release.keystore -alias masroof \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `release.keystore` and passwords safe. You need the **same keystore** for every release so in-app updates work.

### 2. Add GitHub Actions secrets

**Option A — automated (recommended)**

From the repo root, with [GitHub CLI](https://cli.github.com/) logged in as the repo owner:

```bash
./scripts/generate-release-keystore.sh   # skip if you already have release.keystore
./scripts/upload-release-secrets.sh
```

This creates `release.keystore`, `keystore.properties`, and uploads four secrets to GitHub Actions:
- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

**Option B — manual**

In repository **Settings → Secrets and variables → Actions**, add the four secrets above.

### 3. Create a GitHub read-only token (for your phone)

1. GitHub → **Settings → Developer settings → Personal access tokens**
2. Create a **fine-grained token** with **Contents: Read-only** on the `Masroof` repository only.
3. On the phone: **Settings → About → App update** → paste token → **Save token**.

The token is stored in app-private storage on the device and is never committed to the repo.

---

## Updating the app on your phone

1. Open Masroof → **Settings → About**.
2. Choose your **update channel**:
   - **Stable**: official releases only.
   - **Nightly**: latest test or stable build (highest `versionCode`).
3. Ensure your GitHub token is saved.
4. Tap **Check for updates** (or wait for an automatic check on first launch after onboarding).
5. **Download update** → **Install update**.
6. If prompted, allow **Install unknown apps** for Masroof.

If you are running a nightly build, **About** shows a **نسخة تجريبية** badge (Arabic) / **Nightly** badge (English).

---

## Local release build (optional)

```bash
cp keystore.properties.example keystore.properties
# fill in keystore.properties
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
