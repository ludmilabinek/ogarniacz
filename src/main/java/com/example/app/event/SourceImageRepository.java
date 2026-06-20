package com.example.app.event;

import com.example.app.user.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SourceImageRepository extends JpaRepository<SourceImage, UUID> {

    Optional<SourceImage> findByIdAndUser(UUID id, AppUser user);
}
