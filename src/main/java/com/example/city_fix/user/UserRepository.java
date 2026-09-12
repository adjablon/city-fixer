package com.example.city_fix.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Role filtering lives in the query so "admins are never listed" holds at the data layer
     * rather than depending on a template or a caller remembering to filter.
     */
    List<User> findByRoleInOrderByCreatedAtAsc(Collection<Role> roles);
}
