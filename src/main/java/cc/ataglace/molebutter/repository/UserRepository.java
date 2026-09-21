package cc.ataglace.molebutter.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserStatus;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByNameAndPhoneNumber(String name, String phoneNumber);
    List<User> findAllByStatus(UserStatus status, Sort sort);
}
