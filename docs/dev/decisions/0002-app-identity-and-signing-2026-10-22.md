# App identity and signing (2026-10-22)

- The Android application id stays `io.github.meshlive.app` (the `io.github.<organisation>.<app>` convention, backed by the
  MeshLive GitHub organisation; iOS uses the same id). Changing it would make a different app that cannot update existing
  installs.
- One signing key for every source: the existing GitHub release key is uploaded to Google Play as the app signing key
  ("use my own key" when the app is created), with a separate upload key for Play uploads. GitHub, Obtainium and Play
  installs can then replace each other without uninstalling. F-Droid builds keep their own key unless they become
  reproducible builds verified against ours.
- The privacy policy is published at https://meshlive.github.io/privacy/ (repository MeshLive/MeshLive.github.io), a link
  that does not depend on the app repository layout.
- Google Play child safety: the standards page is https://meshlive.github.io/child-safety/; in the app, Settings >
  Contact us and Report > Tell MeshLive open an email draft to the public contact address meshlive.dev@gmail.com.
