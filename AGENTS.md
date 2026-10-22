# Persistent project requirements

The owner cancelled full environment backups on 2026-10-23. Do not create or
upload workspace, SDK, emulator, source or input archives for recovery. Back up
only signing keys and their required keystore password/alias, using encrypted
7z under 密钥备份. Keep the archive password private, outside GitHub and archive.
Use 维护/backup_signing_key.py, or the equivalent local maintenance script.
Preserve the current signing key so later builds can update installed variants.
APK distribution and ordinary source/version history may continue; old large
环境备份 files must not be reintroduced, including in reachable Git history.
Never commit plaintext private keys/passwords/unrelated credentials.
Preserve existing installed app identities and user video files. Exact duplicate
cleanup requires fresh full SHA-256, keeper protection and explicit confirmation.
Visual matches are hints only. Android URI sharing cannot transparently replace
arbitrary legacy paths. No account or video permission bypass.
Candidates may be uploaded for owner testing. State actual verification limits.
