# The security store in Firestore

Status: **built and tested against the Firestore emulator** (`casita.storage.type: firestore`). Not
provisioned anywhere, not deployed. The DynamoDB store (docs/PERSISTENCE.md) is unchanged and kept.

The rules of docs/PERSISTENCE.md hold as written: reads that decide access are strongly consistent,
nothing is deleted to revoke, the store's own cleanup (TTL) decides nothing, an unreachable store refuses
everything, and members, passkeys, devices and invitations are never split between stores. Only the store
changes. The design and its reasoning are in `docs/GCP_FREE_TIER_FEASIBILITY.md` (sections 4 and 5).

## Settings

```yaml
casita:
  storage:
    type: firestore
    firestore:
      project-id: casita-prod   # empty: ADC's own project (on Compute Engine, the VM's)
      timeout: 5s               # the longest any Firestore call may take, retries included, before refusing
```

- **Database:** always `(default)`, Native mode. Only `(default)` gets the free quota, so there is no
  setting for it.
- **Credentials:** Application Default Credentials only. On Compute Engine that is the VM's attached
  service account through the metadata server. There is no setting for a key file, and none is needed.
- **Emulator:** `casita.storage.firestore.emulator-host` (tests and local benchmarks only) must be on this
  machine (`127.0.0.1`, `localhost` or `[::1]`). If the library's own `FIRESTORE_EMULATOR_HOST`
  variable is set without that setting, Casita refuses to start: the variable alone would silently swap
  the real database for an unauthenticated emulator.
- **Start-up:** `meta/schema` must exist with `schemaVersion: 1`. A new collection that older code never reads
  (`shortcutTokens`) doesn't change the version, and needs no provisioning: Firestore creates it on the first write. If it's missing, a different version, or
  unreadable, Casita doesn't start. Casita never writes it: provisioning does.

## Data model

One `(default)` database. Every collection is top level. Every document ID has a prefix: Firestore
reserves IDs matching `__.*__`, and a base64url credential ID could be one. Identifiers that can't be a
document ID (a `/`, an empty string, more than 700 characters, anything outside `[A-Za-z0-9._-]`) are
never stored. A read of one answers "absent" and a write is refused, so a crafted value can't reach
another collection's document.

| Collection / ID | Fields |
|---|---|
| `members/m_{memberId}` | `memberId`, `status`, `userHandle`, `createdAt`, `updatedAt`, `revokedAt`, `changeEventId` |
| `userHandles/u_{b64url(handle)}` | `memberId`, `createdAt`. Created in the same batch that gives the member the handle, so a handle has one owner |
| `passkeys/c_{credentialId}` | `memberId`, `userHandle`, `publicKeyCose` (bytes), `signatureCount`, `uvInitialized`, `backupEligible`, `backupState`, `transports`, `attestationObject`, `attestationClientDataJson`, `label`, `createdAt`, `lastUsedAt`, `status`, `revokedAt`, `enrolledNodeId`, `inviteId`, `changeEventId` |
| `devices/n_{deviceId}` (a browser key `b-…`; older documents: a Tailscale StableID) | `nodeId`, `memberId`, `label`, `nodeName`, `credentialId`, `inviteId`, `status`, `authorizedAt`, `revokedAt`, `releasedAt`, `changeEventId` |
| `invitations/h_{sha256hex(token)}` | `tokenHash`, `inviteId`, `memberId`, `label`, `status`, `createdAt`, `expiresAt`, `consumedAt`, `consumeRequestId`, `enrollmentId`, `nodeId`, `nodeName`, `enrolledAt`, `enrolledCredentialId`, `revokedAt`, `ttl`, `changeEventId` |
| `shortcutTokens/t_{sha256hex(token)}` | `tokenHash`, `tokenId`, `memberId`, `label`, `door`, `action`, `status` (`ACTIVE` \| `REVOKED`), `createdAt`, `expiresAt`, `revokedAt`, `changeEventId` (docs/SHORTCUTS.md). No TTL: revoked and expired tokens stay listed |
| `securityEvents/e_{random 128 bits}` | `type`, `at`, `outcome`, `reason`, `memberId`, `nodeId`, `inviteId`, `credentialId`, `shortcutTokenId`, `expiresAt` |
| `meta/schema` | `schemaVersion`. Written by provisioning only |

**Nothing secret is in an ID or a field.** Neither an invitation token nor a Shortcut token reaches Firestore: only its SHA-256
(256 random bits, so the hash can't be enumerated). Event IDs are random and say nothing about the event.
Not stored: tokens, session IDs, IP addresses, user agents, anything about Meross.

`changeEventId` is the ID of the security event written with the document's last change. It is how a
decision made again recognizes its own earlier commit (below). `lastEnrollmentAt` on members and passkeys
exists only to carry an enrollment's condition on them.

**Indexes:** only Firestore's automatic single-field indexes (`memberId`, `inviteId`, `tokenId`, `securityEvents.at`).
Exempt the byte fields (`publicKeyCose`, `attestationObject`, `attestationClientDataJson`) from indexing at
provisioning: nothing queries them.

**TTL policies** (cleanup only, decided by nothing): `invitations.ttl` (`expiresAt` + 30 days) and
`securityEvents.expiresAt` (`at` + 400 days).

## Decisions: compare-and-set, not transactions

Every decision is a **compare-and-set**: read the documents it depends on (one strongly consistent
`BatchGetDocuments`), decide in code, then commit **one atomic batch** in which every document the decision
read carries a precondition:

- a document that was there: `updateTime` unchanged since the read;
- a document that was absent and must stay so until now (a new passkey, a new node, a new invitation):
  `create()`, which fails if it exists;
- a document the decision depends on but doesn't change (the member, an existing passkey, at enrollment):
  touched (`lastEnrollmentAt`) with the `updateTime` condition, so a revocation that lands first makes
  the batch fail.

If anything changed in between, **nothing** in the batch is written, and the decision is made again from a
fresh read. Exactly one of any number of concurrent writers changes a document. This is DynamoDB's
conditional write (`ConditionExpression`, `TransactWriteItems`), and it takes no locks.

**Why not `runTransaction`.** The first version used read-write transactions, as the feasibility doc
planned. The emulator tests showed what read locks do under contention: two attempts each hold a read lock
the other needs to commit, and they wait for each other until they time out. In 5 repeated runs, a 20-way
redemption of one invitation once ended with **no** winner (all 20 failed closed), and two enrollments of
one node once **both** failed. Nothing was ever wrongly granted, but a double-tap shouldn't fail. Production
Firestore resolves lock conflicts differently (wound-wait), but that can't be checked locally. Compare-and-set
doesn't depend on the lock manager. After the change: 6 of 6 repeated runs, every race exactly one winner,
none failed closed. The suite also runs 3× faster.

**Deciding again is always safe**, because every decision is idempotent from stored state. The time and every
random ID (`changeEventId`, the event's ID) are fixed **before** the first attempt. A batch whose commit
answer was lost may have been applied, so the next read may find this call's own write:

| Operation | Deciding again, it finds… | …and answers |
|---|---|---|
| `consume` | `CONSUMED` with its own `consumeRequestId`, `enrollmentId` and node, not enrolled | the same invitation; writes nothing |
| `enroll` | `enrolledAt` set, with its own `enrollmentId` and credential, device and passkey active for the member | success; writes nothing |
| `revokeInvitation`, `revokeDevice`, `revokePasskey`, `releaseDevice`, `recordUse` | its own `changeEventId` | `REVOKED` / `true`, not "already revoked" |
| `createInvitation` | the invitation with its own `changeEventId` | success |
| `setUserHandle`, `revokeMember` | its own values | the same answer |

Security events are written with `set` on an ID fixed before the first attempt, so an event written again
is the same document, once. There is **no idempotency token and no time window**: a repeated enrollment
at any time is judged by what is stored. (DynamoDB's 10-minute `ClientRequestToken` window doesn't exist here.)

**Bounded.** At most 6 decisions per call, all within `timeout`, with a short random pause between
attempts so contenders don't collide again in step.

**Fail closed.** A read that fails, or no decision within the timeout, is a `StoreUnavailableException`,
and the request is refused. One exception: `enroll` with an unknown outcome reads invitation, device and
passkey back. Complete → success. Anything else, or the read fails → refused. The state is whole either
way, and a later sign-in finds it.

**Reads.** `accessView` (every signed-in request) and `enrollmentView` are one `BatchGetDocuments`: the
documents as of one read time, strongly consistent, **one RPC, 3 (or 2) document reads**. Listing queries
only find documents. The fields that decide (`memberId`, `status`) are checked on each document.

## Security events

Every change to access writes a `securityEvents` document **in the same batch** as the change: both or
neither. Types: `INVITATION_CREATED`, `INVITATION_CONSUMED`, `INVITATION_REFUSED` (expired, revoked or
already used: a replayed link), `DEVICE_ENROLLED`, `ENROLLMENT_REFUSED` (with a reason category),
`SIGNED_IN`, `PASSKEY_REVOKED`, `DEVICE_REVOKED`, `DEVICE_RELEASED`, `MEMBER_REVOKED`, `SHORTCUT_TOKEN_CREATED`,
`SHORTCUT_TOKEN_REVOKED` (with `shortcutTokenId`, never the token or its hash). A Shortcut request that uses a token
writes nothing here (it is logged, and a command appears in the activity log), so a busy Shortcut costs reads only. An unknown token
writes nothing, so guessing costs no writes.

Not recorded here: requests refused by the access check itself (a revoked device's next request, an
unknown node). Those are decided in `security/access` (PR #8) and logged there. Recording them needs a
small interface addition (`SecurityStore.recordRefusal`) in all three stores, and is a follow-up.

## IAM (gate G3 of the feasibility doc)

The runtime service account needs `datastore.entities.get`, `list`, `create`, `update` and
`datastore.databases.getMetadata`. It needs **no `delete`**: nothing in this code deletes a document. Shortcut tokens
need nothing more (the same four entity permissions, on one more collection). The
emulator doesn't enforce IAM, so this is a provisioning check, not a test. No service-account keys. Org policy
`iam.disableServiceAccountKeyCreation` on the project. Security Rules: `allow read, write: if false;`.

## Tests

`FirestoreSecurityStoreTest` runs the whole `SecurityStoreContract`, the same contract the file and
DynamoDB stores run, plus Firestore's own cases. `FirestoreAccessFlowTest` runs the whole
`AccessFlowContract` (real WebAuthn registration and sign-in, replayed and forged assertions, every
revocation), plus outages. Both use **Google's Firestore emulator jar** (the one firebase-tools runs),
started on `127.0.0.1` and a free port, never a fixed one. Each store gets its own project, so tests never
share documents. Faults are injected at the gRPC layer **under** the real client library
(`FaultyFirestore`): failures before sending, applied-but-lost answers, `ABORTED` contention, and outages.
The library's own retries then behave as they would against Google.

**The emulator jar:** `cloud-firestore-emulator-v1.22.0.jar`, 136,707,194 bytes, from Google's bucket that
firebase-tools downloads it from,
`https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar`.
SHA-256 `9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c` (it also matches the bucket's own
MD5, `XXTMa+6eGFYNLq4sZED9Ew==`). It needs only Java 21.

**Locally:**

```bash
curl -fo ~/cloud-firestore-emulator-v1.22.0.jar \
  https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar
echo "9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c  $HOME/cloud-firestore-emulator-v1.22.0.jar" | shasum -a 256 -c -
mvn test -Dfirestore.emulator.jar=$HOME/cloud-firestore-emulator-v1.22.0.jar -Dfirestore.emulator.required=true
# or, with an emulator already running (never on 8080): mvn test -Dfirestore.emulator.host=127.0.0.1:PORT
```

Without either property, the Firestore tests are **skipped** (surefire counts them as 0 tests run), and the rest
of the suite runs as before. `-Dfirestore.emulator.required=true` makes a missing emulator a failure.

**CI** (`.github/workflows/ci.yml`): caches the jar by version, verifies its SHA-256 (a mismatch fails the job),
and runs `mvn verify -Dfirestore.emulator.jar=… -Dfirestore.emulator.required=true` with
`FIRESTORE_EMULATOR_HOST` and `GOOGLE_APPLICATION_CREDENTIALS` unset. The tests start the emulator on
127.0.0.1 and a free port, in a fresh project per store. Then `scripts/ci/check-test-report.py` fails the job
if any test was skipped other than the opt-in Meross live tests, or if fewer than 60 / 50 Firestore store /
access-flow tests ran. No Google account and no credentials exist in CI: nothing can reach a real project.

**What the emulator can't show:** IAM, real network latency, Firestore's production lock manager (the
emulator's contention behaviour is close, but not the same), TTL, PITR, and quotas. Latency measured
against it is a local, non-production estimate.
