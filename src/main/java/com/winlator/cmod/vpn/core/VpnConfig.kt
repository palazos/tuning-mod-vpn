package com.winlator.cmod.vpn.core

object VpnConfig {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 25
    const val MAX_TEXT_LENGTH = 32

    // The host is always .1; guests get the lowest free address after it.
    const val SUBNET_PREFIX = "10.77.0."
    const val HOST_ADDRESS = SUBNET_PREFIX + "1"

    fun guestAddress(index: Int) = SUBNET_PREFIX + index

    // Broker address; credentials and the trusted CA live in BrokerConnection.
    const val BROKER_URI = "ssl://52.202.111.12:8883"
    const val TOPIC_ROOT = "winlator/vpn/v1/rooms"
    const val JOIN_ROOT = "winlator/vpn/v1/join"
    const val TALK_ROOT = "winlator/vpn/v1/talk"
    const val REPLY_ROOT = "winlator/vpn/v1/reply"
    const val PROTOCOL_VERSION = 1

    // Hosts refresh their announcement; rooms not refreshed within STALE_MS are dropped.
    const val REFRESH_MS = 30_000L
    const val STALE_MS = 100_000L

    // Members ping the host; a member silent for MEMBER_STALE_MS is dropped, and a
    // member that hears nothing from the host for that long leaves the room. Both sides
    // look for silent peers every CHECK_MS, so detection is at most MEMBER_STALE_MS + CHECK_MS.
    const val PING_MS = 10_000L
    const val MEMBER_STALE_MS = 30_000L
    const val CHECK_MS = 5_000L
}
