package com.babycam;

import java.util.Objects;

/** Identity of a bound control listener; never include credentials in logs. */
final class ControlConfiguration {
    final String host;
    final int port;
    final String username;
    final String password;

    ControlConfiguration(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ControlConfiguration)) return false;
        ControlConfiguration value = (ControlConfiguration) other;
        return port == value.port && Objects.equals(host, value.host)
                && Objects.equals(username, value.username) && Objects.equals(password, value.password);
    }

    @Override public int hashCode() { return Objects.hash(host, port, username, password); }
}
