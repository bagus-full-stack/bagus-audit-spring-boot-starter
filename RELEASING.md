# Releasing to Maven Central

A release is a tag. Pushing `vX.Y.Z` runs `.github/workflows/release.yml`, which sets the version from the tag,
runs the tests, signs the jars with GPG and **uploads** them to the Central Portal. Nothing goes public until you
click *Publish* there (`autoPublish` is off), so the first release can be checked before it is final.

The repository itself stays on `X.Y.Z-SNAPSHOT`; only the CI build sees the release version.

## One-time setup (needs your accounts, so it is not automated)

1. **Central Portal account** at <https://central.sonatype.com>.
2. **Verify the namespace** `io.github.bagus-full-stack`. For a `io.github.<user>` namespace the portal asks you to
   create a temporary public repository with a name it gives you. Do that from the GitHub account that owns it.
3. **Generate a user token** in the portal (*Account → Generate User Token*). You get a username and a password.
4. **Create a GPG key** and publish its public part to a key server (Central checks signatures against it):
   ```bash
   gpg --full-generate-key                                   # RSA 4096, with a passphrase
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>                 # this text is GPG_PRIVATE_KEY
   ```
5. **Add four GitHub secrets** (*Settings → Secrets and variables → Actions*):

   | Secret | Value |
   |---|---|
   | `CENTRAL_USERNAME` | token username from step 3 |
   | `CENTRAL_PASSWORD` | token password from step 3 |
   | `GPG_PRIVATE_KEY` | the armored private key from step 4 |
   | `GPG_PASSPHRASE` | its passphrase |

## Each release

1. Make sure `main` is green (CI and native build).
2. In `CHANGELOG.md`, move the entries under a new `## [X.Y.Z] — date` heading.
3. Tag and push:
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
4. When the workflow is green, open <https://central.sonatype.com/publishing/deployments>, check the deployment
   (four components: `audit-spring-boot-starter-parent`, `audit-autoconfigure`, `audit-starter`, `audit-starter-jpa`)
   and press *Publish*.
5. The artifacts appear on Maven Central after the portal validates them (usually within minutes to an hour).
6. Update the version in the README install snippet.

A published version can never be changed or deleted from Central. If the upload is wrong, drop the deployment in the
portal before publishing and tag again with a new version.

## What was checked, and what was not

Checked locally: the `release` profile builds sources and javadoc jars for the three libraries, excludes the sample,
produces a bundle of the four expected components, and signs every file (tested with a throwaway key on Linux).

Not checked: the upload itself, the namespace verification and the real key server, because they need your accounts.
The first tag is the real test.
