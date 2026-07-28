package org.granitesecurity.profile.dto;

import java.util.List;

public record PurgeResult(List<Long> deletedOrderIds) {
}
