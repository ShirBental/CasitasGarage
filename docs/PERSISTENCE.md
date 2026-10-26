# Casita on AWS: persistence

> **Update:** device authorizations are now keyed by browser keys (`b-…`) rather than Tailscale StableIDs, and the
> network is public HTTPS behind Caddy (`docs/ACCESS.md`, `docs/PUBLIC_HTTPS.md`). The store's shape is unchanged.

What exists and what doesn't:

- **Built** (`casita.storage.type: dynamodb`, tested against DynamoDB Local):
  - the **security store**: members, passkeys, device authorizations and invitations;
  - the enrollment transaction.
- **Not built:** activity events (still in memory) and Parameter Store.
- **Default** (`casita.storage.type: file`): local development, one owner-only file, `data/security.json`.

**Nothing has been provisioned in AWS.**

## Decisions (given)

| | |
|---|---|
| Hosting | One EC2 instance to start |
| Database | DynamoDB, on-demand capacity, Point-in-Time Recovery on every table |
| Secrets | SSM Parameter Store, `SecureString` |
| AWS credentials | The EC2 instance profile only; no keys in config, files or environment |
| Network | Tailscale only: Casita on 127.0.0.1, `tailscale serve` in front, no inbound security-group rules |
| Sign-in | WebAuthn passkeys |
| Device authorization | Passkey identity plus an authorized Tailscale node (`docs/ACCESS.md`) |
| Invitations | DynamoDB; token hashes only; 5 minutes; single use; records kept 30 days after expiry (TTL) |
| Activity retention | 90 days |
| Outages | **Fail closed.** If DynamoDB or Tailscale verification is unavailable, nothing is allowed |

DynamoDB is for simplicity and durability, and so the data doesn't depend on the instance's disk. It is
not for scale: there are a handful of people, devices and doors.

## Rules every table follows

1. **Every access decision reads base tables with strong consistency.** It uses `GetItem` with
   `ConsistentRead`, or `TransactGetItems`, which is serializable. **A GSI never decides anything.**
   GSIs only *find* keys, for listings, for admin commands addressed by a short id, and for cleanup.
   Whatever an index returns is read again from the base table before anything is decided.
2. **TTL decides nothing.** Expiry is checked in code on every read and in every write condition.
   TTL only removes old records, possibly days late.
3. **Nothing is deleted to revoke.** Revocation is a conditional status update, so a revoked
   record can't come back by accident, including when a later write is replayed.
4. **No secrets in DynamoDB.** It holds invitation token *hashes*, passkey *public* keys, and no session ids.
5. **Never mixed.** Either all the security tables (members, passkeys, devices, invitations and Shortcut tokens) are
   in DynamoDB, or all are in the one file. (Firestore, docs/FIRESTORE.md, follows the same rule.) The enrollment
   transaction (below) spans all four. Splitting them between DynamoDB and files would break its
   atomicity, so `casita.storage.type` switches all four together.

## Tables

The names are `<prefix>members`, `<prefix>passkeys`, `<prefix>devices`, `<prefix>invitations` and
`<prefix>activity`, with `casita.storage.table-prefix` defaulting to `casita-`.

### `casita-members`

| Key | |
|---|---|
| PK `memberId` (S) | `shir`, `hubby` |

Attributes: `status` (`ACTIVE` \| `REVOKED`), `userHandle` (B, the WebAuthn `user.id`),
`createdAt`, `updatedAt`, `revokedAt`.

GSI `byUserHandle`: PK `userHandle`, KEYS_ONLY. It is used only to turn the user handle in a sign-in
assertion into a member id. That member is then read from the base table, and its `userHandle` must
match.

- **Seeding:** at start, Casita runs `PutItem` for each name in `casita.security.family`, with the
  condition `attribute_not_exists(memberId)`. This never revives a revoked member. Access needs both:
  the name is listed in `family`, and the item is `ACTIVE`.
- **Revoking a member** is one `UpdateItem` on this base item: `status = REVOKED`, on condition
  `attribute_exists(memberId)`. That alone ends every session of theirs on its next request (see
  "Every request"). Revoking their devices, passkeys and invitations follows as separate **cleanup**.
  Cleanup finds them through GSIs and can lag or fail partway without opening anything, because the
  member item already refuses everything.

### `casita-passkeys`

| Key | |
|---|---|
| PK `credentialId` (S, base64url) | |

Attributes: `memberId`, `userHandle` (B), `publicKeyCose` (B), `signatureCount` (N),
`uvInitialized`, `backupEligible`, `backupState` (BOOL), `transports` (SS), `attestationObject` and
`attestationClientDataJson` (B), `label`, `createdAt`, `lastUsedAt`, `status` (`ACTIVE` \| `REVOKED`),
`revokedAt`, `enrolledNodeId`, `inviteId`.

GSI `byMember`: PK `memberId`, SK `createdAt`, KEYS_ONLY. It serves `excludeCredentials` when a
passkey is being set up, admin listings, and cleanup.

- **Created only by the enrollment transaction.** No other code path writes a new passkey.
- **After a sign-in:** `UpdateItem` of `signatureCount` and `lastUsedAt`, on condition
  `status = ACTIVE AND memberId = :m`.
- **Revoking:** `UpdateItem status = REVOKED`, on condition `status = ACTIVE`.

### `casita-devices` (device authorizations)

| Key | |
|---|---|
| PK `nodeId` (S) | The Tailscale StableID. **One item per node, for at most one member** |

Attributes: `memberId`, `label`, `nodeName`, `credentialId` (the passkey used to enroll), `inviteId`,
`status` (`ACTIVE` \| `REVOKED` \| `RELEASED`), `authorizedAt`, `revokedAt`, `releasedAt`.

GSI `byMember`: PK `memberId`, KEYS_ONLY. It serves admin listings and cleanup.

Because the node is the key, a node can't hold two authorizations. The enrollment transaction's write
to this table succeeds only on condition:

```
attribute_not_exists(nodeId)                          -- a node never authorized
OR (#status = :revoked AND memberId = :member)        -- re-enrolling a revoked node for the same member
OR #status = :released                                -- an admin released it (see below)
```

So:

- an **ACTIVE** node can't be overwritten by a second enrollment, even one for the same member;
- a node **can't be silently reassigned** to another member. A revoked node stays tied to its
  member until the admin runs `casita-access release-device <node>`. That is an `UpdateItem
  status = RELEASED`, on condition `status = REVOKED`, and only then can any member's invite
  enroll the node.

Redemption also checks the node first (a consistent `GetItem`) and refuses to consume an invite for a
node that is already ACTIVE. That spares the invite and avoids leaving an orphan passkey on the phone.
The transaction condition is still what guarantees uniqueness.

### `casita-invitations`

| Key | |
|---|---|
| PK `tokenHash` (S) | Hex SHA-256 of the 256-bit random token. Redemption starts from the token, so the hash must be the base-table key: single use is then one conditional write on it |

Attributes: `inviteId` (`i-…`), `memberId`, `label`, `status` (`PENDING` \| `CONSUMED` \| `REVOKED`),
`createdAt`, `expiresAt`, and:

- set when consumed: `consumedAt`, `consumeRequestId`, `enrollmentId`, `nodeId`, `nodeName`;
- set when enrollment completes: `enrolledAt`, `enrolledCredentialId`;
- set when revoked: `revokedAt`;
- `ttl` (N, epoch seconds, `expiresAt` + 30 days), set once at creation.

GSIs: `byInviteId` (PK `inviteId`, KEYS_ONLY) for revoking by id, and `byMember` (PK `memberId`,
SK `createdAt`, KEYS_ONLY) for listings and cleanup.

The token itself is never stored or logged; it exists only in the link file on the server. Plain
SHA-256 is enough because the token has 256 random bits, so there is nothing to guess.

#### States

```
                  create: expiresAt = now + 5 min, ttl = expiresAt + 30 days
                                  │
                               PENDING ───────── revoke ─────────▶ REVOKED
                                  │                                   ▲
   consume (verified node, before expiresAt; node not already ACTIVE) │
                                  ▼                                   │
                               CONSUMED ── revoke (only while enrolledAt is unset) ──┘
                                  │
                                  └── enrollment transaction: sets enrolledAt (no further changes)
```

- **Expired** isn't a stored state. It is decided in code on every read (`now >= expiresAt`), and
  every write condition requires `expiresAt > :now`. An expired invite is dead even while it is
  still stored.
- **The enrollment must finish before the invite's `expiresAt`.** No write changes `expiresAt` or
  `ttl` after creation, so nothing can extend an invite's life.
- **A consumed invite whose phone never finishes** times out at `expiresAt`. No device is created,
  and the admin sends a new invite.

#### Consume: single use, idempotent only for its own attempt

Consuming happens on `POST /login/ott`, only after the device has been verified. Before the first
attempt, Casita generates two random 128-bit values:

- `consumeRequestId`, which never leaves the server: it isn't in the response, the session or the
  logs;
- `enrollmentId`, which goes into the server-side session only.

Both are reused only to retry **this same attempt**, whether the SDK retries on its own or Casita
retries once explicitly after an ambiguous error.

```
UpdateItem  Key: tokenHash
  SET #status = :consumed, consumedAt = if_not_exists(consumedAt, :now),
      consumeRequestId = :rid, enrollmentId = :eid, nodeId = :node, nodeName = :nodeName
  CONDITION attribute_exists(tokenHash) AND expiresAt > :now AND (
        #status = :pending
     OR (#status = :consumed AND consumeRequestId = :rid AND enrollmentId = :eid AND nodeId = :node
         AND attribute_not_exists(enrolledAt)))
  ReturnValues: ALL_NEW
```

- **Concurrent attempts:** exactly one finds `PENDING`; all the others fail the condition.
- **A retry of the same attempt** passes only if it carries the same `consumeRequestId`, the same
  `enrollmentId` *and* the same node, and only while the invitation hasn't enrolled a device. It
  writes the same values again, keeps the original `consumedAt`, and touches neither `expiresAt` nor
  `ttl`. It can't replace the `enrollmentId` or node, and it can't revive an invite that has been
  revoked (wrong status) or has expired (`expiresAt`).
- **Any condition failure** answers the same generic "that didn't work", whether the token is
  unknown, used, expired or revoked.

#### Revoke

The admin's link file holds the token, so revoking normally goes **by the token's hash**: one
conditional write on the base table, with no index involved. An invitation created a moment ago is
found too.

```
UpdateItem  Key: tokenHash
  SET #status = :revoked, revokedAt = :now
  CONDITION inviteId = :id AND (#status = :pending OR (#status = :consumed AND attribute_not_exists(enrolledAt)))
```

When the condition refuses, a consistent read of the same item says why:

| Outcome | HTTP | Meaning |
|---|---|---|
| `REVOKED` | 200 `"revoked": true` | It is revoked now |
| `ALREADY_REVOKED` | 200 `"revoked": true` | It was revoked already |
| `ALREADY_ENROLLED` | 409 `"revoked": false` | It already enrolled its device: revoke that device instead |
| `NOT_FOUND` | 404 `"revoked": false` | No such invitation, or none with this id under this hash |
| store unreachable | 503 | The change may not have happened; check before relying on it |

Only without the link file does the id go through `byInviteId`. An index can lag a just-created
invitation, so a miss there is `NOT_FOUND` with "run it again", never a success. The link file is
deleted only when the token can't be used any more (revoked, or already enrolled). On any other
answer it stays, so the admin can retry. `casita-access` prints `FAILED` and exits 1 on every
answer other than success.

### `casita-activity` (not built yet)

| Key | |
|---|---|
| PK `doorId` (S) | `ford`, `toyota` |
| SK `eventKey` (S) | `<ISO-8601 UTC time>#<event id>`: sorts by time; the id makes it unique |

Attributes:

- `eventId`, `occurredAt`, `type`, `commandId`;
- `memberId`, `nodeId` (neither for an unexpected change);
- `action` (`OPEN` \| `CLOSE` \| `RESUME`), `commandMode` (`simulated` \| `off` \| `dry-run` \| `live`);
- `executionStatus`, `observedState`, `failureKind` (a category, never a payload or credential);
- `expiresAt` (N, epoch seconds, `occurredAt + 90 days`): the **TTL attribute**.

GSI `byMember`: PK `memberId`, SK `eventKey`. It is sparse: it holds only events that have a member.

`type` keeps a command apart from what was observed:

| `type` | Written when | Means |
|---|---|---|
| `COMMAND_REQUESTED` | A family member asks | Someone asked. Not that anything moved |
| `COMMAND_SENT` / `COMMAND_REFUSED` | `GarageService` decides | Sent to the opener (or simulated, or a dry run), or refused (`BUSY`, `HELD`, `ALREADY`, mode `off`…) |
| `COMMAND_CONFIRMED` | The sensor shows the requested state | The **only** event the app reports as "opened" / "closed" |
| `COMMAND_UNCONFIRMED` | The sensor didn't confirm in time (`STUCK`, `LOST`) | Needs a person to check; holds the door as today |
| `STATE_CHANGED` | The sensor changes with no command in flight | Unexpected door activity (a wall button, a remote) |

| Access pattern | How |
|---|---|
| The app's history for a door | Query PK `doorId`, `eventKey > <now − 90 days>`, newest first, `Limit` |
| Recent across doors | The same query per door, merged |
| What one member did | Query `byMember` with the same 90-day lower bound |
| One command's story | Query PK `doorId` from its request time, filtered on `commandId` |

The 90 days are enforced by the query's key condition, not by TTL. Activity isn't part of any
access decision, so it can move to DynamoDB later on its own without touching the security
transaction.

## Every request

Each check is **one `TransactGetItems`** (a consistent snapshot) of base-table items. On any error,
the request is refused (fail closed).

| Session | Reads | Allowed only if |
|---|---|---|
| Passkey sign-in | `members[m]`, `passkeys[credential]`, `devices[node]` | member `ACTIVE` and in `family`; passkey `ACTIVE` with `memberId = m`; device `ACTIVE` with `memberId = m`; the node is the session's node |
| Enrollment | `invitations[tokenHash]`, `members[m]` | invitation `CONSUMED`, its `enrollmentId` and `nodeId` equal to the session's, `now < expiresAt`, `enrolledAt` unset; member `ACTIVE`; the node is the session's node |

The node comes from `tailscale serve` plus `tailscale whois`. If that can't be verified, the request
is refused before DynamoDB is asked anything.

## The enrollment transaction

This is the only way a device becomes authorized, and the only way a passkey is created. It runs
**after** the passkey has been verified by Spring Security, in one of two places:

- registration: the attestation, with `userVerification: required`. The transaction runs inside
  `UserCredentialRepository.save` for the new credential;
- sign-in with the member's existing synced passkey: the assertion, with `userVerification:
  required`. The transaction runs in the sign-in success handler.

In both cases the request's node has just been verified again and must equal the invite's node.

```
TransactWriteItems  ClientRequestToken = enrollmentId
  1. Update invitations[tokenHash]
       SET enrolledAt = :now, enrolledCredentialId = :credential
       CONDITION #status = :consumed AND enrollmentId = :eid AND nodeId = :node AND memberId = :m
                 AND expiresAt > :now AND attribute_not_exists(enrolledAt)
  2. ConditionCheck members[m]          #status = :active
  3a. Put passkeys[credential]          (new passkey)  CONDITION attribute_not_exists(credentialId)
  3b. ConditionCheck passkeys[credential] (existing)   #status = :active AND memberId = :m
  4. Put devices[node]   memberId = :m, status = ACTIVE, label, inviteId, credentialId, authorizedAt
       CONDITION attribute_not_exists(nodeId) OR (#status = :revoked AND memberId = :m) OR #status = :released
```

**Either all of it is written, or none of it.** So none of these can happen:

- a passkey without its device authorization;
- an authorization without a consumed invite;
- two authorizations from one invite;
- an invite marked enrolled without a device.

Because of condition 1, an invite can complete exactly one enrollment, and because of condition 4, a
node can't be taken over.

### When the response is lost

The SDK retries a timeout or a 5xx with the same `ClientRequestToken`. Within DynamoDB's 10-minute
idempotency window, a repeat of a transaction that succeeded returns success without writing again.

If Casita still doesn't know the outcome (the SDK gave up, or a retry was cancelled because the
first attempt had already succeeded), it **does not write again**. It reads the durable state with
one `TransactGetItems` and decides:

| What it reads | Outcome |
|---|---|
| Invitation `enrolledAt` set, with this `enrollmentId` and credential; device `ACTIVE` with `memberId = m` and this `inviteId`; passkey `ACTIVE` with `memberId = m` | **Succeeded**: the device is authorized (once) |
| Anything else | **Failed**: atomicity means nothing was written; the person sees "that didn't work" |
| The read fails as well | **Refused** (fail closed). The durable state is complete or absent, never partial. If it was complete, the next Face ID sign-in on that phone works; if not, the admin sends a new invite |

**After the idempotency window** a resend could no longer be recognized. Casita never resends,
though, and the window is longer than an invite's life anyway. A resend more than 10 minutes later
would fail on `expiresAt > :now`, and an earlier one on `attribute_not_exists(enrolledAt)` or the
device condition. Either way it is refused, not duplicated. No path writes `expiresAt` or `ttl`, so
recovery can't extend an invite.

## Restarts

Sessions (and so enrollment sessions) are in memory on the single instance: a restart signs
everyone out. Invitations, passkeys, devices and members are in DynamoDB:

- a PENDING invite still works after a restart;
- a CONSUMED one whose enrollment session was lost can't be resumed, because its `enrollmentId`
  was only in that session. It expires, and the admin sends a new invite;
- revocations stay revoked.

## Secrets (Parameter Store, `SecureString`): not built yet

| Parameter | Replaces |
|---|---|
| `/casita/admin-secret` | `<data-dir>/admin.secret` |
| `/casita/meross/email`, `/casita/meross/password` | `config/meross.local.json` / environment variables |

### `casita-shortcut-tokens`

| Key | |
|---|---|
| PK `tokenHash` (S) | Hex SHA-256 of the token (`casita_st_` + 256 random bits; docs/SHORTCUTS.md). A Shortcut request starts from the token, so the hash is the base-table key |

Attributes: `tokenId` (`st-` + 48 random bits), `memberId`, `label`, `door`, `action`, `status` (`ACTIVE` \| `REVOKED`),
`createdAt`, `expiresAt`, `revokedAt`. Indexes (KEYS_ONLY, finding only): `byTokenId`, `byMember`. Creation is one
conditional put (`attribute_not_exists(tokenHash)`); revocation a conditional status update. No TTL: revoked and
expired tokens stay listed. An AWS deployment needs this table before it runs a release with Shortcut tokens.

## IAM (instance profile), least privilege

| Table | Actions |
|---|---|
| members | `GetItem`, `PutItem` (seeding), `UpdateItem`, `Query` (index), `ConditionCheckItem` |
| passkeys | `GetItem`, `PutItem` and `ConditionCheckItem` (inside the transaction), `UpdateItem`, `Query` (index) |
| devices | `GetItem`, `PutItem` (inside the transaction), `UpdateItem`, `Query` (index) |
| invitations | `GetItem`, `PutItem`, `UpdateItem`, `Query` (indexes) |
| shortcut-tokens | `GetItem`, `PutItem`, `UpdateItem`, `Query` (indexes) |
| all five | `DescribeTable` (the start-up check), `TransactGetItems`/`TransactWriteItems` (granted through the item actions they contain) |

There's no `DeleteItem` and no `Scan`; `ssm:GetParameter` on `/casita/*` and `kms:Decrypt` come
when Parameter Store does. Tables are created by infrastructure code reviewed separately; Casita
can't create them, and it fails at start-up if they are missing.

## Testing, offline

- **DynamoDB Local runs inside the test JVM** (`DynamoDBEmbedded`): no Docker, no port, no AWS
  account. It supports conditional writes, transactions and `ClientRequestToken` idempotency. It
  doesn't run TTL, which shows nothing depends on it. The tests create their tables from the same
  definitions as `DynamoTables`.
- **One contract suite** (`SecurityStoreContract`) runs against both the file store and the
  DynamoDB store.
- **The flow tests** (`AccessFlowTest`) run through the real filter chain against both stores.
- **Fault injection** on the DynamoDB client covers: an error before sending; success with the
  response lost; a cancelled retry after success; the recovery read failing too; and an outage.

## Migration sequence

1. **Done:**
   - the `SecurityStore` interface, its file implementation (local development) and its DynamoDB
     implementation;
   - `casita.storage.type` switches all four security tables together;
   - the start-up check that the tables exist.
2. Infrastructure code for the five tables, with PITR, TTL on `invitations.ttl` and `activity.expiresAt`,
   and the IAM role: reviewed, not applied.
3. Parameter Store for the admin and Meross secrets.
4. Activity events in DynamoDB, with the event types above.
5. On EC2: start with `casita.storage.type: dynamodb`, enroll each device with a new invite (two people, so
   no data import), then retire `data/`.
