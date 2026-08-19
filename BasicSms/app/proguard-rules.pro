# R8 keeps what it needs automatically for AndroidX/Room/Compose.
# Add app-specific keep rules below only if you hit a reflection issue.

# Keep our Room entities' field names (Room uses them; usually safe without this,
# but explicit is cheap insurance).
-keepclassmembers class dev.montb.basicsms.data.** { *; }
