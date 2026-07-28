package org.granitesecurity.profile.dto;

import java.util.List;

/** shop's answer to "is this user purgeable?" — see shop's dto of the same name. */
public record PurgeEligibility(boolean eligible, List<Long> orderIds, int paidOrderCount) {
}
