package org.granitesecurity.shop.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * Reads and writes {@code packaging_group_option}, whose key is the pair
 * {@code (group, option)}.
 *
 * <p>A hand-written {@link DatabaseClient} repository rather than a
 * {@code ReactiveCrudRepository}: Spring Data needs a single {@code @Id} property to
 * build one, and inventing a surrogate key for a pure join row would put a second,
 * meaningless identity on a table whose identity is already the pair.
 */
@Repository
public class PackagingGroupOptionRepository {

    private static final String SELECT_BY_GROUPS = """
            SELECT g.id            AS group_id,
                   g.code          AS group_code,
                   g.name          AS group_name,
                   g.description   AS group_description,
                   o.id            AS option_id,
                   o.code          AS option_code,
                   o.name          AS option_name,
                   o.description   AS option_description,
                   o.image_url     AS image_url,
                   o.price         AS price,
                   o.unit_cost     AS unit_cost,
                   o.sort_order    AS sort_order,
                   o.active        AS active,
                   go.capacity     AS capacity
            FROM packaging_group_option go
            JOIN packaging_group  g ON g.id = go.packaging_group_id
            JOIN packaging_option o ON o.id = go.packaging_option_id
            WHERE go.packaging_group_id IN (:groupIds)
            """;

    private final DatabaseClient databaseClient;

    public PackagingGroupOptionRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Every active option for these groups, with capacity — one round trip for the
     * whole cart, however many groups it spans.
     */
    public Flux<GroupOptionRow> findActiveByGroupIds(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Flux.empty();
        }
        return databaseClient.sql(SELECT_BY_GROUPS + " AND o.active = TRUE ORDER BY g.name, o.sort_order, o.id")
                .bind("groupIds", groupIds)
                .map(PackagingGroupOptionRepository::toRow)
                .all();
    }

    /** Admin view: includes retired options, which is the only way to un-retire one. */
    public Flux<GroupOptionRow> findAllByGroupIds(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Flux.empty();
        }
        return databaseClient.sql(SELECT_BY_GROUPS + " ORDER BY g.name, o.sort_order, o.id")
                .bind("groupIds", groupIds)
                .map(PackagingGroupOptionRepository::toRow)
                .all();
    }

    /**
     * Sets the capacity for a pairing, creating it if it does not exist. An upsert
     * because "this box holds twelve of these" is one statement to an admin, not a
     * create-or-update decision they should have to make.
     */
    public Mono<Long> upsertCapacity(Long groupId, Long optionId, int capacity) {
        return databaseClient.sql("""
                        INSERT INTO packaging_group_option (packaging_group_id, packaging_option_id, capacity)
                        VALUES (:groupId, :optionId, :capacity)
                        ON CONFLICT (packaging_group_id, packaging_option_id)
                        DO UPDATE SET capacity = EXCLUDED.capacity
                        """)
                .bind("groupId", groupId)
                .bind("optionId", optionId)
                .bind("capacity", capacity)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> delete(Long groupId, Long optionId) {
        return databaseClient.sql("""
                        DELETE FROM packaging_group_option
                        WHERE packaging_group_id = :groupId AND packaging_option_id = :optionId
                        """)
                .bind("groupId", groupId)
                .bind("optionId", optionId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * How many groups would be left with no active option at all if this one were
     * deactivated. Non-zero means the deactivation must be refused: a product whose
     * group has no box is a product that cannot be ordered, and the failure would
     * surface at checkout rather than here.
     */
    public Mono<Long> countGroupsLeftWithoutActiveOption(Long optionId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS orphaned FROM packaging_group g
                        WHERE EXISTS (
                                  SELECT 1 FROM packaging_group_option go
                                  WHERE go.packaging_group_id = g.id AND go.packaging_option_id = :optionId)
                          AND NOT EXISTS (
                                  SELECT 1 FROM packaging_group_option go2
                                  JOIN packaging_option o ON o.id = go2.packaging_option_id
                                  WHERE go2.packaging_group_id = g.id
                                    AND go2.packaging_option_id <> :optionId
                                    AND o.active = TRUE)
                        """)
                .bind("optionId", optionId)
                .map(row -> row.get("orphaned", Long.class))
                .one();
    }

    private static GroupOptionRow toRow(io.r2dbc.spi.Readable row) {
        return new GroupOptionRow(
                row.get("group_id", Long.class),
                row.get("group_code", String.class),
                row.get("group_name", String.class),
                row.get("group_description", String.class),
                row.get("option_id", Long.class),
                row.get("option_code", String.class),
                row.get("option_name", String.class),
                row.get("option_description", String.class),
                row.get("image_url", String.class),
                row.get("price", java.math.BigDecimal.class),
                row.get("unit_cost", java.math.BigDecimal.class),
                row.get("sort_order", Integer.class),
                row.get("active", Boolean.class),
                row.get("capacity", Integer.class));
    }
}
