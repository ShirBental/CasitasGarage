# Casita on AWS: persistence plan

Status: **design, not built.** Today Casita keeps passkeys and device authorizations in owner-only JSON
files (`FilePasskeyStore`, `AccessStore`), activity in memory, and invites and sessions in memory.
This is the plan for replacing those with the decided AWS setup.

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
| Activity retention | 90 days |

DynamoDB is for simplicity and durability, and so the data doesn't depend on the instance's disk. It is
not for scale: there are a handful of people, devices and doors.

## Tables

There are four tables, each with a single purpose. There are no scans in the request path. Every
security decision uses a **strongly consistent `GetItem` on the base table**, never a GSI (GSIs are
eventually consistent) and never TTL.

### `casita-family-members`

| Key | |
|---|---|
| PK `memberId` (S) | `shir`, `hubby`: the names in `casita.security.family` today |

Attributes: `displayName`, `role` (`FAMILY`; room for `ADMIN` later), `status` (`ACTIVE` \| `REVOKED`),
`webauthnUserHandle` (B, the WebAuthn `user.id`), `createdAt`, `updatedAt`, `revokedAt`.

GSI `byUserHandle`: PK `webauthnUserHandle`, KEYS_ONLY. Spring's
`PublicKeyCredentialUserEntityRepository.findById(userHandle)` uses it during sign-in. Casita then
reads the member back with a consistent `GetItem`.

| Access pattern | How |
|---|---|
| Is this member active? (every request) | `GetItem memberId`, consistent |
| `findByUsername` | `GetItem memberId` |
| `findById(userHandle)` | Query `byUserHandle`, then `GetItem` |
| Admin: list members | `Scan` of a few items, admin-only and rare. (Alternative: query a fixed "family" partition.) |

`casita.security.family` stays the allow-list: a member must be both listed there and `ACTIVE`.

### `casita-passkeys`

| Key | |
|---|---|
| PK `credentialId` (S, base64url) | |

Attributes: `memberId`, `userHandle` (B), `publicKeyCose` (B), `signatureCount` (N),
`uvInitialized` (BOOL), `backupEligible` (BOOL), `backupState` (BOOL), `transports` (SS),
`attestationObject` and `attestationClientDataJson` (B, optional), `label`, `createdAt`, `lastUsedAt`,
`status` (`ACTIVE` \| `REVOKED`), `revokedAt`, `revokedReason`.

GSI `byMember`: PK `memberId`, SK `createdAt`, projecting `status`, `label`, `backupEligible` and
`backupState`.

| Access pattern | How |
|---|---|
| Sign-in: find the credential | `GetItem credentialId`, consistent; `REVOKED` is treated as absent |
| Every request: does the session's passkey still exist and belong to the member? | Same `GetItem` |
| After sign-in: update `signatureCount`, `lastUsedAt` | `UpdateItem` with condition `status = ACTIVE` |
| Registration: store a new passkey | `PutItem` with condition `attribute_not_exists(credentialId)`, only inside a live enrollment (as today) |
| `findByUserId` / `excludeCredentials` / admin list | Query `byMember`, keeping `ACTIVE` items |
| Revoke | `UpdateItem status = REVOKED` (kept for the record) |

### `casita-device-authorizations`

| Key | |
|---|---|
| PK `memberId` (S) | |
| SK `nodeId` (S) | Tailscale StableID |

Attributes: `deviceId` (`d-…`, for admin commands), `label`, `nodeName`, `enrolledCredentialId`,
`inviteId`, `authorizedAt`, `status` (`ACTIVE` \| `REVOKED`), `revokedAt`, `revokedReason`.

GSI `byDeviceId`: PK `deviceId`, KEYS_ONLY (so `casita-access revoke-device d-…` can find the item).

| Access pattern | How |
|---|---|
| Every request: is this node authorized for this member? | `GetItem (memberId, nodeId)`, consistent, `status = ACTIVE` |
| Enrollment: authorize the node | `PutItem` (re-authorizing a node replaces its item) |
| Admin: a member's devices | Query PK `memberId` |
| Revoke a device | Query `byDeviceId`, then `UpdateItem status = REVOKED` on the base key |
| Revoke a member | Query PK `memberId`, then update each item |

This matches today's `AccessStore`. It already keeps revoked devices as revoked rather than deleting them.

### `casita-activity-events`

| Key | |
|---|---|
| PK `doorId` (S) | `ford`, `toyota` |
| SK `eventKey` (S) | `<ISO-8601 UTC time>#<event id>`: sorts by time; the id makes it unique |

Attributes:

- `eventId`, `occurredAt`, `type`, `commandId`;
- `memberId`, `deviceId`, `nodeId` (none of these for an unexpected change);
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

`commandId` links each command's events, which is what an investigation needs: who asked, from which
device, in which mode, what Casita sent, and what the sensor showed afterwards.

| Access pattern | How |
|---|---|
| The app's history for a door | Query PK `doorId`, `eventKey > <now − 90 days>`, newest first, `Limit` |
| Recent across doors | The same query per door (two doors), merged |
| What one member did | Query `byMember` with the same 90-day lower bound |
| One command's story | Query PK `doorId`, `eventKey` from its request time, filtered on `commandId` |

**The 90 days are enforced by the query's key condition**, not by TTL. TTL deletion is asynchronous
and can lag by days, so an expired item may still be stored but is never returned. TTL is cleanup
only, and nothing in authorization reads activity.

## Invitations and sessions

- **Sessions** stay in memory on the single instance. A restart signs everyone out, and Face ID signs
  them back in. They move to a shared store only if a second instance is added.
- **Invites** are in memory today: only token hashes, voided by a restart. *Open: the request this
  plan answers was cut off at "You may retain pending invitati…".* If invites should survive restarts,
  they would go in a fifth table, `casita-invitations`:
  - PK `tokenHash`, with `inviteId`, `memberId`, `label`, `createdAt`, `expiresAt`, `status`
    (`PENDING` → `REDEEMED` → `ENROLLED` \| `REVOKED` \| `EXPIRED`) and `redeemedNodeId`;
  - redemption as one conditional `UpdateItem` (`status = PENDING AND expiresAt > :now`), which makes
    it single use even across instances;
  - expiry checked in code on every read, with TTL only for cleanup;
  - GSI `byInviteId` for revocation.

## Secrets (Parameter Store, `SecureString`)

| Parameter | Replaces |
|---|---|
| `/casita/admin-secret` | `<data-dir>/admin.secret` |
| `/casita/meross/email`, `/casita/meross/password` | `config/meross.local.json` / environment variables |

Casita reads these at start with `GetParameter` and `WithDecryption`, and never logs them. Passkeys
hold public keys only, so they stay in DynamoDB, not Parameter Store.

## IAM (instance profile)

- `dynamodb:GetItem`, `Query`, `PutItem`, `UpdateItem` on the four tables and their indexes;
  `Scan` on `casita-family-members` only. No `DeleteItem`: revocations are updates, and TTL removes
  old activity.
- `ssm:GetParameter` on `arn:aws:ssm:<region>:<account>:parameter/casita/*`, and `kms:Decrypt` on the
  key for those parameters.
- Nothing else. No AWS keys anywhere on the instance; the SDK's default chain finds the instance profile.

## Moving from files to DynamoDB

1. Put interfaces in front of what exists: Spring's two passkey repositories (already), a
   `DeviceAuthorizations` interface for `AccessStore`, and `ActivityLog` (already). The file versions
   stay for local development and tests.
2. Add DynamoDB implementations, tested against DynamoDB Local (or Testcontainers). They are chosen by
   `casita.storage=dynamodb`, and the files stay the default.
3. Activity: record the `type`s above in `GarageService`. Today it records one event per command, with
   its final outcome.
4. Secrets from Parameter Store, chosen by configuration in the same way.
5. A one-off import of `passkeys.json` and `devices.json`. Or re-enroll, which is simpler with two people.

Waiting on: the rest of the cut-off message (invites); the AWS account, region and table names; and
review of this plan.
