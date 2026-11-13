# Release builds shrink and optimize with R8 (plan 7.1). Libraries bring their own keep rules
# (Room, WorkManager, kotlinx.serialization, Compose); add an app rule here only for a real failure.

# Names stay as written. WorkManager stores worker class names from earlier builds, and logs name
# exception classes; the source is public, so renaming would hide nothing.
-dontobfuscate
