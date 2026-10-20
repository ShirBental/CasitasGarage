# Firestore conditional writes: correctness and security review

Reviewed 2026-10-20 for M1: `FirestoreSecurityStore` as of this commit, on `feat/firestore-persistence`.
Scope: the decisions that grant or end access. The design is in docs/FIRESTORE.md. This document checks it,
claim by claim, and says what each claim rests on.

## What the implementation relies on from Firestore

Only documented behaviour of Firestore Native mode, nothing particular to the emulator:

1. **A batched write is atomic.** One `Commit` RPC applies all its writes or none of them. (Firestore
   documentation, "Transactions and batched writes": a batch of writes "completes atomically".)
2. **Write preconditions are checked by the server, inside that atomic commit.** `update(ref, data,
   Precondition.updatedAt(t))` applies only if the document's `update_time` is still exactly `t`.
   `create(ref, data)` applies only if the document doesn't exist. If any precondition fails, the whole commit
   fails (`FAILED_PRECONDITION` or `ALREADY_EXISTS`) and nothing is written. These are the `current_document`
   field of the `Write` message in the Firestore v1 API.
3. **`update_time` changes on every write to a document.** So "unchanged since my read" is exactly "no write
   since my read". It is server-assigned, at microsecond precision, and the client sends back the value the
   server gave it.
4. **Reads are strongly consistent.** A `BatchGetDocuments` returns all requested documents as of one read
   time, and that read time reflects every commit acknowledged before it.

The emulator implements the same API and was used to *test* these properties. No guarantee comes from the
emulator's own lock manager: there are no transactions and no locks.

## Claims, and why they hold

**1. The read dependencies and write preconditions are enforced atomically.** Every decision is
`decided(...)`: read (one `BatchGetDocuments`), decide in code, commit one batch. Every document the decision
**read and depends on** is in the batch with a precondition:

| Decision | Documents read | Their condition in the batch |
|---|---|---|
| `consume` | invitation | `updatedAt` |
| `enroll` | invitation, member, passkey, device | invitation `updatedAt`; member touched with `updatedAt`; passkey `create` (new) or touched with `updatedAt` (existing); device `create` (absent) or `updatedAt` (present) |
| `revokeInvitation`, `revokeDevice`, `revokePasskey`, `releaseDevice`, `revokeMember`, `recordUse` | that document | `updatedAt` |
| `setUserHandle` | member, userHandle | member `updatedAt`; userHandle `create` |
| `createInvitation`, `seedMembers` | the document | `create` |

A refusal changes nothing, so its batch holds only its security event, with no condition. A refusal based on a
read that is now old is still correct, because every state change here moves forward and never back toward
allowing more.

**2. A concurrent change can't produce a stale authorization decision.** If anything the decision read was
written after the read, the batch fails as a whole and the decision is made again from a fresh read. Tested
deterministically: a decision is held between its read and its write while another client changes what it read,
then released (`FaultyFirestore.holdNext`). Six tests:

| Held decision | Change landed meanwhile | Result |
|---|---|---|
| enroll (new passkey) | member revoked | refused (`member-inactive`), no device, no passkey |
| enroll (existing passkey) | that passkey revoked | refused, no device |
| enroll | its invitation revoked | refused, no device; invitation stays `REVOKED` |
| enroll for Shir | the node enrolled for Hubby | refused; the node is Hubby's; Shir's passkey not stored |
| consume | invitation revoked; and separately, consumed by another node | refused both times; one `INVITATION_CONSUMED` event |
| recordUse (sign-in) | passkey revoked | refused, no `SIGNED_IN` event |

**Mutation check:** with the `updatedAt` preconditions removed, 5 of the 6 fail: the stale write lands. The
sixth is protected by `create()`, which the mutation left in place. So the tests detect the failure they are
about.

**3. Exactly one competing redemption succeeds.** `consume` is conditioned on the invitation's `update_time`.
Of N concurrent consumers that read the same `PENDING` document, the first commit changes `update_time`, and
every other commit fails its precondition. On re-reading, they see `CONSUMED` by another attempt and are
refused. Tested: 20 concurrent redemptions, exactly one winner, in 6 of 6 repeated runs, none failing
closed. Plus the interleaved test above.

**4. Enrollment retries are idempotent, including uncertain commit outcomes.** The time and every random
ID are fixed before the first attempt. When the outcome is unknown (an error after sending, a timeout), the
next attempt re-reads. If the commit had applied, it finds `enrolledAt` with its own `enrollmentId` and
credential, the device active for the member by this invite, and the passkey active. It answers success
and writes nothing. If not, it decides afresh. If no attempt succeeds in time, `enroll` reads back once more,
and if that read fails too, it refuses (fail closed; the state is whole either way). A repeat at any later
time is judged the same way: there is no idempotency window. Tested: lost commit answer, failures before sending,
lost answer then outage (read-back fails → refused, state complete), resends after 11 minutes.

**5. Retries don't duplicate security events.** An event's ID is fixed before the first attempt and written
with `set`, in the same batch as the change. Every attempt of one call writes the same document, and a batch
is all or nothing, so there is at most one event per call, and it describes the outcome that was committed. A
re-read that finds its own write (by `changeEventId`, request id or enrollment) writes nothing more. Tested:
one `INVITATION_CONSUMED` after a lost answer and after a 20-way race; one `DEVICE_ENROLLED` after lost
answers and repeated enrollments.

**6. Revocation stays effective across concurrent requests.** A revocation is a conditioned status change.
Every signed-in request reads member, passkey and device in one strongly consistent read (claim 4 above),
so the next request after a committed revocation sees it, from any server (tested from a second client). A
decision that read before the revocation can't write after it (claim 2). **Limitation, as with DynamoDB:** a
request whose access check passed a few milliseconds before the revocation committed may still finish.
Closing that for door commands is hardening H1 in the feasibility doc (re-check right before acting).

**7. Firestore failures fail closed.** Every read error, every timeout, and a decision not reached in
`ATTEMPTS` (6) tries are `StoreUnavailableException`, which the access layer turns into a refusal (tested
end to end in `FirestoreAccessFlowTest`: outages during a session, sign-in, redemption, enrollment and admin
revocation). Start-up fails without a readable `meta/schema` v1. An unknown token writes nothing. Identifiers
that can't be document IDs read as absent and are never written.

**8. No emulator-only guarantees.** See "What the implementation relies on" above. The emulator differs from
production in its lock manager (unused), IAM (unused here; gate G3), latency (benchmark labelled
non-production) and TTL (cleanup only). The client library's retry of a non-transactional `Commit` after an
ambiguous error is also safe in production: a resent conditioned batch fails its precondition if the first
was applied, and the next re-read finds the write. A resent refusal or event-only batch rewrites the same
document.

## Limitations and assumptions

- **Time bound.** A call ends within about twice the timeout at worst: no new read starts once the time is
  up, but one already started may take up to the timeout itself. (Found in this review. Before, re-reads
  weren't bounded by the deadline at all.)
- **Contention beyond two people.** Every contender in a race makes progress, because one commit always
  wins each round. A loser could still exhaust 6 attempts under sustained, many-way contention. It then
  fails closed. With two people and two doors this is theoretical. It never happened in testing.
- **Enrollment touches the member and an existing passkey** (`lastEnrollmentAt`) to carry their condition.
  So an enrollment and an unrelated change to the same member (a sign-in on another device updates the
  passkey, not the member) can make one of them decide again. That is correct, only occasionally slower.
- **`invitationById`** finds by a 32-bit invite ID. Two live invitations with the same ID are improbable,
  and the result is checked, but revoking *by ID alone* acts on the first match, as with DynamoDB. Revoking
  by link file (token hash) is exact.
- **Not recorded as security events:** refusals decided by the access check itself, before the store is
  asked to change anything (a revoked device's next request, an unknown node). They are logged by
  `security/access`. Follow-up: `SecurityStore.recordRefusal` in all three stores.
- **IAM** (no delete permission for the runtime account) can't be tested against the emulator. It is a
  provisioning check (gate G3). The code never deletes a document.

## Verdict

The conditional-write implementation meets every point of the M1 checklist, on documented Firestore
behaviour, with the limitations above. One defect was found and fixed in this review (the unbounded
re-read). No change of approach is needed.
