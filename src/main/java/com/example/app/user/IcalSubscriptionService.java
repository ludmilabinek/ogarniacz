package com.example.app.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IcalSubscriptionService {

    private final AppUserRepository appUserRepository;
    private final IcalTokenGenerator tokenGenerator;

    public IcalSubscriptionService(AppUserRepository appUserRepository, IcalTokenGenerator tokenGenerator) {
        this.appUserRepository = appUserRepository;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public String getOrCreateToken(AppUser user) {
        AppUser locked = appUserRepository.findAndLockById(user.getId())
                .orElseThrow(() -> new IllegalStateException("AppUser not found: " + user.getId()));
        String existing = locked.getIcalToken();
        if (existing != null) {
            return existing;
        }
        String fresh = tokenGenerator.next();
        locked.setIcalToken(fresh);
        return fresh;
    }
}
