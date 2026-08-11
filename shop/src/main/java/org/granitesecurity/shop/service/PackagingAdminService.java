package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.PackagingGroup;
import org.granitesecurity.shop.domain.PackagingOption;
import org.granitesecurity.shop.dto.PackagingCapacityRequest;
import org.granitesecurity.shop.dto.PackagingGroupRequest;
import org.granitesecurity.shop.dto.PackagingGroupResponse;
import org.granitesecurity.shop.dto.PackagingOptionRequest;
import org.granitesecurity.shop.dto.PackagingOptionResponse;
import org.granitesecurity.shop.repository.GroupOptionRow;
import org.granitesecurity.shop.repository.PackagingGroupOptionRepository;
import org.granitesecurity.shop.repository.PackagingGroupRepository;
import org.granitesecurity.shop.repository.PackagingOptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin maintenance of packaging groups, options and capacities (step 7).
 *
 * <p>Separate from {@link PackagingService}, which only prices carts: that one runs
 * inside checkout on every order, this one runs when someone introduces a new box.
 * Mixing them would put admin CRUD on the hot path's dependency list.
 */
@Service
public class PackagingAdminService {

    private final PackagingGroupRepository groupRepository;
    private final PackagingOptionRepository optionRepository;
    private final PackagingGroupOptionRepository groupOptionRepository;

    public PackagingAdminService(PackagingGroupRepository groupRepository,
                                 PackagingOptionRepository optionRepository,
                                 PackagingGroupOptionRepository groupOptionRepository) {
        this.groupRepository = groupRepository;
        this.optionRepository = optionRepository;
        this.groupOptionRepository = groupOptionRepository;
    }

    // ── Groups ──────────────────────────────────────────────

    public Mono<List<PackagingGroupResponse>> listGroups() {
        return groupRepository.findAll().collectList()
                .flatMap(groups -> {
                    if (groups.isEmpty()) {
                        return Mono.just(List.of());
                    }
                    // Retired options included: an admin needs to see the box they
                    // turned off in order to turn it back on.
                    return groupOptionRepository
                            .findAllByGroupIds(groups.stream().map(PackagingGroup::getId).toList())
                            .collectList()
                            .map(rows -> toGroupResponses(groups, rows));
                });
    }

    public Mono<PackagingGroupResponse> createGroup(PackagingGroupRequest request) {
        String code = requireCode(request.code());
        if (request.name() == null || request.name().isBlank()) {
            return Mono.error(new ShopException("A packaging group needs a name"));
        }
        return groupRepository.findByCode(code)
                .flatMap(existing -> Mono.<PackagingGroupResponse>error(new ShopException(
                        "A packaging group with code " + code + " already exists",
                        HttpStatus.CONFLICT, "Conflict")))
                .switchIfEmpty(Mono.defer(() -> groupRepository
                        .save(new PackagingGroup(code, request.name(), request.description()))
                        .map(saved -> toGroupResponse(saved, List.of()))));
    }

    /**
     * Renames a group. The code is deliberately not updatable: changelogs, events and
     * whatever picks orders in the warehouse all refer to it, and none of them would
     * learn about the rename.
     */
    public Mono<PackagingGroupResponse> updateGroup(Long id, PackagingGroupRequest request) {
        return groupRepository.findById(id)
                .switchIfEmpty(Mono.error(new ShopException(
                        "Packaging group not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(existing -> {
                    if (request.name() != null && !request.name().isBlank()) {
                        existing.setName(request.name());
                    }
                    if (request.description() != null) {
                        existing.setDescription(request.description());
                    }
                    existing.setUpdatedAt(Instant.now());
                    return groupRepository.save(existing);
                })
                .map(saved -> toGroupResponse(saved, List.of()));
    }

    // ── Options ─────────────────────────────────────────────

    public Flux<PackagingOptionResponse> listOptions() {
        return optionRepository.findAllOrdered().map(PackagingAdminService::toOptionResponse);
    }

    public Mono<PackagingOptionResponse> createOption(PackagingOptionRequest request) {
        String code = requireCode(request.code());
        if (request.name() == null || request.name().isBlank()) {
            return Mono.error(new ShopException("A packaging option needs a name"));
        }
        if (request.price() == null || request.price().signum() < 0) {
            return Mono.error(new ShopException("A packaging option needs a price of zero or more"));
        }
        if (request.unitCost() == null || request.unitCost().signum() < 0) {
            // Refused rather than defaulted: an unstated box cost is not a rounding
            // question, it is a fulfilment cost that would silently go unrecorded.
            return Mono.error(new ShopException("A packaging option needs a unit cost of zero or more"));
        }
        return optionRepository.findByCode(code)
                .flatMap(existing -> Mono.<PackagingOptionResponse>error(new ShopException(
                        "A packaging option with code " + code + " already exists",
                        HttpStatus.CONFLICT, "Conflict")))
                .switchIfEmpty(Mono.defer(() -> {
                    PackagingOption option = new PackagingOption();
                    option.setCode(code);
                    option.setName(request.name());
                    option.setDescription(request.description());
                    option.setPrice(request.price());
                    option.setUnitCost(request.unitCost());
                    option.setImageUrl(request.imageUrl());
                    option.setActive(request.active() == null || request.active());
                    option.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
                    Instant now = Instant.now();
                    option.setCreatedAt(now);
                    option.setUpdatedAt(now);
                    return optionRepository.save(option).map(PackagingAdminService::toOptionResponse);
                }));
    }

    /**
     * Reprices or retires an option.
     *
     * <p>Repricing does not touch orders already placed: {@code order_packaging} froze
     * its own copy (D26). Retiring is refused when it would leave any group with no
     * box at all — that failure belongs here, where an admin can read it, not at
     * checkout in front of a shopper.
     */
    public Mono<PackagingOptionResponse> updateOption(Long id, PackagingOptionRequest request) {
        return optionRepository.findById(id)
                .switchIfEmpty(Mono.error(new ShopException(
                        "Packaging option not found: " + id, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(existing -> {
                    boolean deactivating = Boolean.FALSE.equals(request.active())
                            && Boolean.TRUE.equals(existing.getActive());
                    Mono<Void> guard = deactivating
                            ? groupOptionRepository.countGroupsLeftWithoutActiveOption(id)
                                    .flatMap(orphaned -> orphaned != null && orphaned > 0
                                            ? Mono.error(new ShopException(
                                                    "'" + existing.getName() + "' is the last packaging left for "
                                                            + orphaned + " group(s); add another before retiring it",
                                                    HttpStatus.CONFLICT, "Conflict"))
                                            : Mono.empty())
                            : Mono.empty();

                    return guard.then(Mono.defer(() -> {
                        if (request.name() != null && !request.name().isBlank()) {
                            existing.setName(request.name());
                        }
                        if (request.description() != null) {
                            existing.setDescription(request.description());
                        }
                        if (request.price() != null) {
                            if (request.price().signum() < 0) {
                                return Mono.error(new ShopException("Price cannot be negative"));
                            }
                            existing.setPrice(request.price());
                        }
                        if (request.unitCost() != null) {
                            if (request.unitCost().signum() < 0) {
                                return Mono.error(new ShopException("Unit cost cannot be negative"));
                            }
                            existing.setUnitCost(request.unitCost());
                        }
                        if (request.imageUrl() != null) {
                            existing.setImageUrl(request.imageUrl());
                        }
                        if (request.active() != null) {
                            existing.setActive(request.active());
                        }
                        if (request.sortOrder() != null) {
                            existing.setSortOrder(request.sortOrder());
                        }
                        existing.setUpdatedAt(Instant.now());
                        return optionRepository.save(existing);
                    }));
                })
                .map(PackagingAdminService::toOptionResponse);
    }

    // ── Capacities ──────────────────────────────────────────

    /**
     * Allows an option for a group, or changes how many fit. An upsert, because "this
     * box holds twelve of these" is one statement, not a create-or-update decision the
     * admin should have to make first.
     */
    public Mono<PackagingGroupResponse> setCapacity(Long groupId, PackagingCapacityRequest request) {
        if (request.optionId() == null) {
            return Mono.error(new ShopException("Name the packaging option to set a capacity for"));
        }
        if (request.capacity() == null || request.capacity() < 1) {
            // A capacity of zero would divide by zero at checkout; a negative one is
            // meaningless. The CHECK constraint agrees, this is just the readable half.
            return Mono.error(new ShopException("Capacity must be at least 1"));
        }
        return groupRepository.findById(groupId)
                .switchIfEmpty(Mono.error(new ShopException(
                        "Packaging group not found: " + groupId, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(group -> optionRepository.findById(request.optionId())
                        .switchIfEmpty(Mono.error(new ShopException(
                                "Packaging option not found: " + request.optionId(),
                                HttpStatus.NOT_FOUND, "Not Found")))
                        .flatMap(option -> groupOptionRepository
                                .upsertCapacity(groupId, option.getId(), request.capacity())))
                .then(groupWithOptions(groupId));
    }

    /**
     * Stops offering an option for a group. Refused when it is the group's last
     * pairing, for the same reason retiring an option is: a group with no box is a
     * product that cannot be ordered.
     */
    public Mono<PackagingGroupResponse> removeCapacity(Long groupId, Long optionId) {
        return groupOptionRepository.findAllByGroupIds(List.of(groupId))
                .collectList()
                .flatMap(rows -> {
                    if (rows.stream().noneMatch(r -> r.optionId().equals(optionId))) {
                        return Mono.error(new ShopException(
                                "That packaging is not allowed for this group anyway",
                                HttpStatus.NOT_FOUND, "Not Found"));
                    }
                    boolean lastActive = rows.stream()
                            .filter(r -> Boolean.TRUE.equals(r.active()))
                            .allMatch(r -> r.optionId().equals(optionId));
                    if (lastActive) {
                        return Mono.error(new ShopException(
                                "That is the last packaging available for this group; "
                                        + "allow another before removing it",
                                HttpStatus.CONFLICT, "Conflict"));
                    }
                    return groupOptionRepository.delete(groupId, optionId);
                })
                .then(groupWithOptions(groupId));
    }

    private Mono<PackagingGroupResponse> groupWithOptions(Long groupId) {
        return groupRepository.findById(groupId)
                .switchIfEmpty(Mono.error(new ShopException(
                        "Packaging group not found: " + groupId, HttpStatus.NOT_FOUND, "Not Found")))
                .flatMap(group -> groupOptionRepository.findAllByGroupIds(List.of(groupId))
                        .collectList()
                        .map(rows -> toGroupResponse(group, rows)));
    }

    private static List<PackagingGroupResponse> toGroupResponses(List<PackagingGroup> groups,
                                                                 List<GroupOptionRow> rows) {
        Map<Long, List<GroupOptionRow>> byGroup = new LinkedHashMap<>();
        for (GroupOptionRow row : rows) {
            byGroup.computeIfAbsent(row.groupId(), k -> new ArrayList<>()).add(row);
        }
        return groups.stream()
                .map(group -> toGroupResponse(group, byGroup.getOrDefault(group.getId(), List.of())))
                .toList();
    }

    private static PackagingGroupResponse toGroupResponse(PackagingGroup group, List<GroupOptionRow> rows) {
        return new PackagingGroupResponse(
                group.getId(), group.getCode(), group.getName(), group.getDescription(),
                rows.stream()
                        .map(row -> new PackagingGroupResponse.AllowedOption(
                                row.optionId(), row.optionCode(), row.optionName(), row.price(),
                                Boolean.TRUE.equals(row.active()), row.capacity()))
                        .toList());
    }

    private static PackagingOptionResponse toOptionResponse(PackagingOption option) {
        return new PackagingOptionResponse(
                option.getId(), option.getCode(), option.getName(), option.getDescription(),
                option.getPrice(), option.getUnitCost(), option.getImageUrl(),
                Boolean.TRUE.equals(option.getActive()), option.getSortOrder());
    }

    /** Codes are uppercased and trimmed here so lookups never depend on how it was typed. */
    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ShopException("A stable code is required");
        }
        return code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
