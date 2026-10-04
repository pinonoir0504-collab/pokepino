# ポケピーノ

Android build repository for Pokepino.


## v1.0.0

- Combines the original 1,410-record catalog with a generated historical reference catalog.
- Stores compact offline visual signatures instead of bundling source reference photos.
- Uses conservative two-stage recognition: Pokemon species model plus figure-level visual matching.
- Groups visually indistinguishable reissues and asks the user to choose the release instead of guessing.
- Validates catalog integrity before every APK build.
