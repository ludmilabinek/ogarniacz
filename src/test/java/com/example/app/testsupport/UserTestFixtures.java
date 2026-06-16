package com.example.app.testsupport;

import com.example.app.user.AppUser;
import com.example.app.user.AppUserRepository;
import com.example.app.user.IcalSubscriptionService;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class UserTestFixtures {

	public static final String DEFAULT_PASSWORD = "verylongpassword12";

	private UserTestFixtures() {}

	public static AppUser saveUser(AppUserRepository repo, PasswordEncoder encoder, String email) {
		return repo.save(new AppUser(email, encoder.encode(DEFAULT_PASSWORD)));
	}

	public record SeededUser(AppUser user, String token) {}

	public static SeededUser seedUserWithToken(
			AppUserRepository repo,
			PasswordEncoder encoder,
			IcalSubscriptionService subscriptionService,
			String email) {
		AppUser user = saveUser(repo, encoder, email);
		String token = subscriptionService.getOrCreateToken(user);
		AppUser refreshed = repo.findById(user.getId()).orElseThrow();
		return new SeededUser(refreshed, token);
	}
}
