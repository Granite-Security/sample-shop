package org.granitesecurity.shop.dto;

import java.util.List;

public record PurgeResult(List<Long> deletedOrderIds) {
}
