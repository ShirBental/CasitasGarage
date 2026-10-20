package home.casita.security;

import java.util.List;
import java.util.Optional;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import home.casita.security.access.CredentialEvents;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.Member;
import home.casita.security.store.SecurityStore.Passkey;

/**
 * Spring Security's two WebAuthn repositories, over the security store. Only active members and active
 * passkeys are visible, so a revoked one can't sign in. A <b>new</b> passkey is stored only by the enrollment
 * transaction ({@link CredentialEvents}); a <b>used</b> one gets its counter updated. Deleting through Spring
 * (its passkey-management endpoint) is refused: revocation is for the server-side admin.
 */
public class StorePasskeys implements PublicKeyCredentialUserEntityRepository, UserCredentialRepository {

    private final SecurityStore store;
    private volatile CredentialEvents events;

    public StorePasskeys(SecurityStore store) {
        this.store = store;
    }

    public void setEvents(CredentialEvents events) {
        this.events = events;
    }

    /* ---------- WebAuthn users: one per active member ---------- */

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        return store.memberByUserHandle(id).filter(Member::active).map(StorePasskeys::entity).orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return store.member(username).filter(m -> m.active() && m.userHandle() != null).map(StorePasskeys::entity).orElse(null);
    }

    /** Spring gives a member a WebAuthn user handle the first time they set up a passkey. */
    @Override
    public void save(PublicKeyCredentialUserEntity entity) {
        if (!store.setUserHandle(entity.getName(), entity.getId())) {
            throw new IllegalStateException("Not an active member, or they already have a different user handle");
        }
    }

    /** Both of Spring's repositories have {@code delete(Bytes)}; neither is allowed here. */
    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException("Passkeys and members are revoked by the server-side admin");
    }

    /* ---------- passkeys ---------- */

    @Override
    public void save(CredentialRecord record) {
        CredentialEvents hooks = events;
        if (hooks == null) throw new IllegalStateException("Passkey storage isn't wired");
        Optional<Passkey> existing = store.passkey(record.getCredentialId());
        if (existing.isEmpty()) {
            hooks.newCredential(record); // the enrollment transaction stores it, or this throws
            return;
        }
        if (!store.recordUse(record, existing.get().memberId())) throw new IllegalStateException("Passkey revoked");
        hooks.credentialUsed(record);
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return store.passkey(credentialId).filter(p -> p.status() == SecurityStore.Status.ACTIVE).map(Passkey::record).orElse(null);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return store.memberByUserHandle(userId).map(m -> store.passkeysOf(m.id()).stream().map(Passkey::record).toList()).orElse(List.of());
    }

    private static PublicKeyCredentialUserEntity entity(Member m) {
        return ImmutablePublicKeyCredentialUserEntity.builder().name(m.id()).id(m.userHandle()).displayName(m.id()).build();
    }
}
