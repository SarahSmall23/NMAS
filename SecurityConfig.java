package za.co.nmas.nmas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class SecurityConfig {

    private final LoginFailureHandler loginFailureHandler;

    public SecurityConfig(LoginFailureHandler loginFailureHandler) {
        this.loginFailureHandler = loginFailureHandler;
    }

    /**
     * True if this looks like a fetch()/XHR call expecting JSON, rather than
     * a normal browser page navigation. Previously only URLs starting with
     * "/api/" were treated this way, so a 401/403 on any other endpoint
     * (e.g. /prescriptions/save-bulk, called via fetch() from inside the
     * video consultation) would silently redirect to an HTML login/access-
     * denied page. fetch() follows that redirect and gets back HTML with a
     * 200 status, so res.json() throws — which is exactly what surfaced to
     * doctors as "Network error — please try again." when their session
     * expired mid-call.
     */
    private static boolean isAjaxRequest(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        String requestedWith = request.getHeader("X-Requested-With");
        return uri.startsWith("/api/")
                || "XMLHttpRequest".equals(requestedWith)
                || (accept != null && accept.contains("application/json"));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .headers(headers -> headers
                        .cacheControl(cache -> {}) // keep Spring Security's default Cache-Control: no-store headers
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (isAjaxRequest(request)) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        new ObjectMapper().writeValueAsString(Map.of(
                                                "error", "Unauthorized",
                                                "message", "Your session has expired. Please log in again."
                                        )));
                            } else {
                                response.sendRedirect("/login");
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            // Logged to the server console so we can see exactly which
                            // URL + role combination was denied, instead of guessing.
                            var authentication = org.springframework.security.core.context.SecurityContextHolder
                                    .getContext().getAuthentication();
                            String user = authentication != null ? authentication.getName() : "anonymous";
                            String authorities = authentication != null ? authentication.getAuthorities().toString() : "[]";
                            System.err.println("[NMAS Access Denied] user=" + user
                                    + " authorities=" + authorities
                                    + " uri=" + request.getRequestURI());

                            if (isAjaxRequest(request)) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.getWriter().write(
                                        new ObjectMapper().writeValueAsString(Map.of(
                                                "error", "Forbidden",
                                                "message", "You do not have permission to access this resource."
                                        )));
                            } else {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.sendRedirect("/access-denied");
                            }
                        })
                )

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/",
                                "/login",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/forgot-password",
                                "/forgot-password/verify",
                                "/forgot-password/reset",
                                "/consultation/**",
                                "/api/patients/autocomplete",
                                "/access-denied",
                                "/error"
                        ).permitAll()

                        // AIDEN chatbot — patients must be able to call this
                        .requestMatchers("/api/ai/**").hasAnyRole("PATIENT", "DOCTOR", "NURSE", "FACILITY_ADMIN", "SYSTEM_ADMIN", "RECEPTIONIST", "PHARMACIST")

                        // Notification bell — doctor and patient only (not pharmacist)
                        .requestMatchers("/api/notifications/**").hasAnyRole("PATIENT", "DOCTOR", "FACILITY_ADMIN", "RECEPTIONIST", "NURSE", "PHARMACIST")

                        // Registering patients is reception's job; the nurse can look patients up but not register them
                        .requestMatchers("/patients/register").hasAnyRole("RECEPTIONIST", "FACILITY_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers("/patients").hasAnyRole("RECEPTIONIST", "NURSE", "FACILITY_ADMIN", "SYSTEM_ADMIN")

                        // Only the system admin registers facilities; a facility admin registers its own doctors and staff.
                        .requestMatchers("/facilities/register").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/doctors/register").hasAnyRole("FACILITY_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers("/doctors").hasAnyRole("RECEPTIONIST", "NURSE", "FACILITY_ADMIN", "SYSTEM_ADMIN")

                        // Walk-in flow: reception registers the visit, nurse records vitals, doctor consults
                        .requestMatchers("/reception/**").hasRole("RECEPTIONIST")
                        .requestMatchers("/nurse/**").hasRole("NURSE")
                        .requestMatchers("/doctor/queue/**").hasRole("DOCTOR")
                        .requestMatchers("/doctor/check-in", "/doctor/check-out", "/doctor/signature/**").hasRole("DOCTOR")

                        // Directory of registered accounts (own facility for facility admins, everyone for system admin)
                        .requestMatchers("/admin/users/**").hasAnyRole("FACILITY_ADMIN", "SYSTEM_ADMIN")
                        // Facility-scoped staff overview + cross-facility access log
                        .requestMatchers("/facility/**").hasAnyRole("FACILITY_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers("/staff/register", "/staff/delete/**",
                                "/staff/suspend/**", "/staff/unsuspend/**").hasAnyRole("FACILITY_ADMIN", "SYSTEM_ADMIN")

                        .requestMatchers("/doctors/suspend/**", "/doctors/unsuspend/**",
                                "/doctors/delete/**").hasAnyRole("FACILITY_ADMIN", "SYSTEM_ADMIN")

                        .requestMatchers("/appointments/book").hasRole("DOCTOR")
                        .requestMatchers("/follow-up/**").hasRole("DOCTOR")
                        .requestMatchers("/appointments/*/pdf").hasAnyRole(
                                "PATIENT", "RECEPTIONIST", "NURSE", "DOCTOR", "FACILITY_ADMIN", "SYSTEM_ADMIN"
                        )

                        .requestMatchers("/appointments/**").hasAnyRole(
                                "RECEPTIONIST",
                                "NURSE",
                                "DOCTOR",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )

                        .requestMatchers("/search-records/**").hasAnyRole(
                                "RECEPTIONIST",
                                "NURSE",
                                "DOCTOR",
                                "SYSTEM_ADMIN"
                        )

                        .requestMatchers("/visits/**").hasAnyRole(
                                "NURSE",
                                "DOCTOR",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )

                        .requestMatchers("/consultations/**").hasAnyRole(
                                "DOCTOR",
                                "NURSE",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )

                        // Patients can download their own prescription receipt (ownership checked in controller)
                        .requestMatchers(HttpMethod.GET, "/prescriptions/*/receipt").hasAnyRole(
                                "PATIENT", "DOCTOR", "PHARMACIST", "NURSE",
                                "RECEPTIONIST", "FACILITY_ADMIN", "SYSTEM_ADMIN"
                        )
                        // Reading prescriptions — all clinical staff
                        .requestMatchers(HttpMethod.GET, "/prescriptions/**").hasAnyRole(
                                "DOCTOR", "PHARMACIST", "NURSE",
                                "RECEPTIONIST", "FACILITY_ADMIN", "SYSTEM_ADMIN"
                        )
                        // Writing prescriptions — doctors and admins only
                        .requestMatchers(HttpMethod.POST, "/prescriptions/**").hasAnyRole(
                                "DOCTOR", "FACILITY_ADMIN", "SYSTEM_ADMIN"
                        )

                        // Doctor availability and slot management
                        .requestMatchers("/availability", "/availability/new", "/availability/save").hasRole("DOCTOR")
                        .requestMatchers("/availability/*/slots").hasAnyRole(
                                "DOCTOR",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )
                        // Read-only schedule lookup for the appointment booking page
                        .requestMatchers("/availability/api/schedule").hasAnyRole(
                                "PATIENT", "RECEPTIONIST", "NURSE", "DOCTOR", "FACILITY_ADMIN", "SYSTEM_ADMIN"
                        )

                        // Patient medical info editing — DOCTOR and FACILITY_ADMIN
                        .requestMatchers("/patients/*/edit-medical", "/patients/*/update-medical").hasAnyRole("DOCTOR", "FACILITY_ADMIN", "SYSTEM_ADMIN")

                        // Patient history — staff roles (not patient self-service here)
                        // Reception can register and look up patients but never opens medical history
                        .requestMatchers("/patients/history/**").hasAnyRole(
                                "DOCTOR",
                                "NURSE",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )

                        // Video consultations
                        .requestMatchers("/video/my-requests", "/video/request", "/video/cancel/**").hasRole("PATIENT")
                        .requestMatchers("/video/incoming", "/video/accept/**", "/video/end/**").hasRole("DOCTOR")
                        .requestMatchers("/video/room/**").authenticated()

                        // Medical documents
                        .requestMatchers("/documents/upload", "/documents/patient/**", "/documents/download/**").hasAnyRole(
                                "DOCTOR",
                                "NURSE",
                                "FACILITY_ADMIN",
                                "SYSTEM_ADMIN"
                        )

                        // Pharmacy inventory and dispensary queue
                        .requestMatchers("/inventory/**").hasAnyRole("PHARMACIST", "FACILITY_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers("/dispensary/**").hasAnyRole("PHARMACIST", "FACILITY_ADMIN", "SYSTEM_ADMIN")

                        // Audit log — admins only
                        .requestMatchers("/audit-log").hasAnyRole("SYSTEM_ADMIN", "FACILITY_ADMIN")

                        // REST API — authenticated
                        .requestMatchers("/api/**").authenticated()

                        .requestMatchers("/redirect-dashboard").authenticated()

                        .requestMatchers("/doctor-dashboard").hasRole("DOCTOR")
                        .requestMatchers("/nurse-dashboard").hasRole("NURSE")
                        .requestMatchers("/pharmacist-dashboard").hasRole("PHARMACIST")
                        .requestMatchers("/facility-admin-dashboard").hasRole("FACILITY_ADMIN")
                        .requestMatchers("/system-admin-dashboard").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/receptionist-dashboard").hasRole("RECEPTIONIST")
                        .requestMatchers("/patient-dashboard", "/patient/profile", "/patient/appointments/**").hasRole("PATIENT")

                        .anyRequest().authenticated()
                )

                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/redirect-dashboard", true)
                        .failureHandler(loginFailureHandler)
                        .permitAll()
                )

                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .permitAll()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
