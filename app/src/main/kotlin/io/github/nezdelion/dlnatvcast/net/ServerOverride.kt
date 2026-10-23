package io.github.nezdelion.dlnatvcast.net

/**
 * Debug-only way to make the phone's HTTP server reachable through port forwarding:
 * listen on all interfaces at [port] and put [advertisedAuthority] into item URLs.
 */
data class ServerOverride(val port: Int, val advertisedAuthority: String)
