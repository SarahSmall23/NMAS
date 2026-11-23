package za.co.nmas.nmas.service;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.AppUser;
import za.co.nmas.nmas.repository.AppUserRepository;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public UserDetailsServiceImpl(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account found for: " + email));

        // Defensive normalization: Spring's hasRole("DOCTOR") requires the
        // granted authority to be exactly "ROLE_DOCTOR". If the role was
        // ever stored with different casing or stray whitespace (e.g. from
        // a manual DB edit or an older code path), every role check in
        // SecurityConfig would silently fail and the user would get 403 on
        // every page that isn't explicitly permitAll(). Normalizing here
        // means it always matches regardless of how it was stored.
        String normalizedRole = user.getRole() == null ? "" : user.getRole().trim().toUpperCase();
        if (normalizedRole.isEmpty()) {
            throw new UsernameNotFoundException("Account " + email + " has no role assigned — contact an administrator.");
        }

        return User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .roles(normalizedRole)
                .disabled(user.isSuspended())
                .build();
    }
}
