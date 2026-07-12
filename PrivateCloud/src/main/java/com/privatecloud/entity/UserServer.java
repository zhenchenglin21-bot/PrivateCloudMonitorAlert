package com.privatecloud.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_servers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_server", columnNames = {"user_id", "server_host"})
})
public class UserServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "server_host", nullable = false, length = 128)
    private String serverHost;

    public UserServer() {}

    public UserServer(User user, String serverHost) {
        this.user = user;
        this.serverHost = serverHost;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getServerHost() {
        return serverHost;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
    }
}
