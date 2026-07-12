package com.privatecloud.config;

import com.privatecloud.entity.Role;
import com.privatecloud.entity.User;
import com.privatecloud.repository.RoleRepository;
import com.privatecloud.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initRoles(RoleRepository roleRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            List<String> roles = List.of("ADMIN", "USER");
            for (String role : roles) {
                roleRepository.findByName(role).orElseGet(() -> roleRepository.save(new Role(role)));
            }

            Role adminRole = roleRepository.findByName("ADMIN").orElseGet(() -> roleRepository.save(new Role("ADMIN")));
            User admin = userRepository.findByUsername("admin").orElseGet(() -> new User("admin", passwordEncoder.encode("Lzc0306!")));
            admin.setPasswordHash(passwordEncoder.encode("Lzc0306!"));
            admin.setEnabled(true);
            admin.getRoles().add(adminRole);
            userRepository.save(admin);
        };
    }
}
