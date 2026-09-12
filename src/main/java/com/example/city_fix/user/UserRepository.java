package com.example.city_fix.user;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Role filtering lives in the query so "admins are never listed" holds at the data layer
     * rather than depending on a template or a caller remembering to filter.
     *
     * <p>Returns a projection rather than entities: the account list needs four columns, and
     * selecting whole {@code User} rows would pull every bcrypt hash out of the database to
     * render a page that never shows them. Paged because the resident table is the one table
     * in this app guaranteed to grow without bound.
     */
    Page<AccountSummary> findByRoleIn(Collection<Role> roles, Pageable pageable);

    /** Closed projection — Spring Data selects only these columns. */
    interface AccountSummary {
        Long getId();

        String getEmail();

        Role getRole();

        Boolean getActive();

        Instant getCreatedAt();
    }
}
