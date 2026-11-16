package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.DualIPv4v6Tries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Publishes a complete, read-only filter to the BiglyBT connection threads. */
final class BanState {
    private final String instance = UUID.randomUUID().toString();
    private volatile Snapshot snapshot = new Snapshot(0, Set.of(), new DualIPv4v6Tries());

    String instance() {
        return instance;
    }

    long revision() {
        return snapshot.revision;
    }

    boolean matches(String value) {
        IPAddress address = value == null ? null : new IPAddressString(value).getAddress();
        return address != null && !address.isPrefixed()
                && snapshot.trie.elementsContaining(address) != null;
    }

    List<String> addresses() {
        return snapshot.addresses.stream().map(IPAddress::toCanonicalString).sorted().collect(Collectors.toList());
    }

    synchronized void update(boolean replace, List<String> added, List<String> removed, long revision) {
        Snapshot current = snapshot;
        if (revision >= 0 && current.revision != revision) {
            throw new IllegalStateException("Ban list revision changed");
        }
        Set<IPAddress> addresses = replace ? new HashSet<>() : new HashSet<>(current.addresses);
        addresses.removeAll(parse(removed));
        addresses.addAll(parse(added));
        if (addresses.equals(current.addresses)) {
            return;
        }
        DualIPv4v6Tries trie = new DualIPv4v6Tries();
        addresses.forEach(trie::add);
        snapshot = new Snapshot(current.revision + 1, Set.copyOf(addresses), trie);
    }

    private static Set<IPAddress> parse(List<String> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("Missing IP address list");
        }
        Set<IPAddress> result = new HashSet<>();
        for (String entry : entries) {
            IPAddress address = entry == null ? null : new IPAddressString(entry).getAddress();
            if (address == null || (address.isMultiple() && !address.isPrefixBlock())) {
                throw new IllegalArgumentException("Expected an IP address or CIDR prefix");
            }
            result.add(address.isPrefixed() ? address.toPrefixBlock() : address);
        }
        return result;
    }

    private static final class Snapshot {
        final long revision;
        final Set<IPAddress> addresses;
        final DualIPv4v6Tries trie;

        Snapshot(long revision, Set<IPAddress> addresses, DualIPv4v6Tries trie) {
            this.revision = revision;
            this.addresses = addresses;
            this.trie = trie;
        }
    }
}
