# Persistent project requirements

After each new Android build or material source change, create and upload a new
encrypted 7z recovery snapshot of the workspace under 环境备份. Run
维护/backup_environment.py from the restored workspace, or the equivalent script
in /workspace/shared-download-integration/maintenance/backup_environment.py.
Keep older snapshots. Include source, inputs, outputs, signing material, tools,
SDK and emulator data. Exclude platform credential mounts and the upload staging
checkout to prevent recursive inclusion of previous backups.

Encrypt filenames as well as contents. The password belongs outside both the
archive and GitHub in a private directory, and must be handed to the owner through
this private conversation. Never commit plaintext keys, passwords or unrelated
credentials. The repository is public. Mark a snapshot complete only after every
volume is pushed; do not claim partially uploaded snapshots are recoverable.

Preserve old installed app identities and existing video files. Imported old
videos have unknown original source fingerprints; do not promise that their first
new submission can be blocked by URL. Do not add account or video permission bypass.
APK candidates may be supplied for owner testing, but upload success and static
audits do not establish functional readiness. The owner waived download-back
verification for the current distribution; do not claim that it occurred.
