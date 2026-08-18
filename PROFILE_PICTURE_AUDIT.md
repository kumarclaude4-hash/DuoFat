# Profile Picture Completion Audit

This file records the profile-picture wiring gaps found after commit `f684659` and the files involved. It is retained as a durable regression and deployment reference.

## Confirmed gaps and intended fixes

| Status | Gap | Affected files | Acceptance criteria |
|---|---|---|---|
| Planned | Existing avatar is not restored after reinstall/new-device sign-in | `app/src/main/java/com/duoshield/app/ui/SettingsActivity.java` | Settings loads locally first, then reads `users/{uid}.photoUrl`, downloads a valid `b2:avatars/...` object, and refreshes the durable local cache. |
| Planned | Local avatar survives canonical local-data wipe | `app/src/main/java/com/duoshield/app/util/WipeHelper.java` | `files/own_avatar.jpg` and avatar temporary files are deleted by `eraseLocalData`. |
| Planned | Replacing a photo leaves the previous B2 object orphaned | `SettingsActivity.java`, existing delete support in `B2StorageHelper.java` | After successful publication, the prior owned avatar object is deleted best-effort. |
| Planned | Success is shown before Firestore publication completes | `SettingsActivity.java` | Success and durable local replacement happen only after the user document and partner propagation writes complete. Failed publication removes the newly uploaded object best-effort and preserves the old local state. |
| Planned | Arbitrary selected bytes are named and uploaded as JPEG | `SettingsActivity.java` | Input is decoded safely, EXIF-oriented, square-cropped, bounded, and encoded as actual JPEG before upload. |
| Planned | Selected images have no decode/dimension/upload-size limits | `SettingsActivity.java` | Bounds-first decode, downsampling, pixel limits, and final byte ceiling prevent unbounded memory/upload use. |
| Planned | Rotation and crop are not normalized | `SettingsActivity.java` | EXIF rotation/flip is honored and output is a centered square avatar. |
| Complete | Avatar key authorization lacked regression coverage | `server/lib/mediaScope.test.js` | Tests cover valid keys, malformed keys, owner-only write/delete, authenticated reads, and fail-closed input. |
| External deployment | Live server/Worker may predate avatar-key authorization | `server/index.js`, `server/lib/mediaScope.js`, `worker/src/index.js` | Both push server and Cloudflare Worker run avatar-aware code. Deployment is a separate explicit operation. |

## Data flow that must remain wired

1. Settings photo button opens the system content picker.
2. Selected image is normalized locally to bounded JPEG bytes.
3. Client uploads `avatars/<uid>_<millis>.jpg` through the media capability-token flow.
4. `users/{uid}.photoUrl` is updated with the `b2:` path.
5. Existing chats receive `partnerPhotoUrl_<uid>` so partner conversation avatars refresh.
6. Only after all publication writes succeed is the local avatar/cache replaced.
7. The superseded owned B2 avatar is deleted best-effort.
8. A new installation restores the avatar from `users/{uid}.photoUrl` through authenticated B2 loading.
9. Canonical wipe deletes local avatar data.

## Security invariants

- Avatar writes and deletes are restricted to the UID embedded in the key.
- Avatar reads require an authenticated caller.
- Only `b2:avatars/<currentUid>_<timestamp>.jpg` is eligible for superseded-object deletion.
- Publication failures never discard the previously working local avatar.
- Input decoding is bounded and invalid image content is rejected.

## Verification checklist

- [ ] Android debug Java compilation passes.
- [ ] Relevant Android tests pass or unrelated failures are recorded.
- [x] `server/lib/mediaScope.test.js` covers avatar authorization.
- [ ] Targeted media-scope tests pass.
- [ ] Manual device check: choose rotated PNG/HEIC/JPEG and observe a square correctly oriented avatar.
- [ ] Manual device check: reinstall/sign in and observe remote avatar restoration.
- [ ] Manual device check: replace avatar and confirm previous B2 object is removed.
- [ ] Manual device check: force publication failure and confirm old avatar remains.
- [ ] Manual device check: wipe/unpair and confirm no local avatar remains.
- [ ] Push server and Cloudflare Worker deployment confirmed separately.
