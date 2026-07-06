package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.DeliveryAddress;
import org.granitesecurity.profile.dto.AddressRequest;
import org.granitesecurity.profile.dto.AddressResponse;
import org.granitesecurity.profile.repository.DeliveryAddressRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class AddressService {

    private final DeliveryAddressRepository deliveryAddressRepository;

    public AddressService(DeliveryAddressRepository deliveryAddressRepository) {
        this.deliveryAddressRepository = deliveryAddressRepository;
    }

    public Flux<AddressResponse> getAddresses(String username) {
        return deliveryAddressRepository.findByUsername(username)
                .map(this::toResponse);
    }

    public Mono<AddressResponse> createAddress(String username, AddressRequest req) {
        return unsetDefaultIfNeeded(username, req.isDefault())
                .then(Mono.defer(() -> {
                    DeliveryAddress addr = new DeliveryAddress();
                    addr.setUsername(username);
                    addr.setLabel(req.label());
                    addr.setRecipientName(req.recipientName());
                    addr.setAddressLine1(req.addressLine1());
                    addr.setAddressLine2(req.addressLine2());
                    addr.setCity(req.city());
                    addr.setState(req.state());
                    addr.setZipCode(req.zipCode());
                    addr.setCountry(req.country());
                    addr.setIsDefault(req.isDefault() != null && req.isDefault());
                    addr.setCreatedAt(Instant.now());
                    addr.setUpdatedAt(Instant.now());
                    return deliveryAddressRepository.save(addr);
                }))
                .map(this::toResponse);
    }

    public Mono<AddressResponse> updateAddress(Long id, String username, AddressRequest req) {
        return deliveryAddressRepository.findByIdAndUsername(id, username)
                .switchIfEmpty(Mono.error(new RuntimeException("Address not found")))
                .flatMap(addr -> unsetDefaultIfNeeded(username, req.isDefault())
                        .then(Mono.defer(() -> {
                            addr.setLabel(req.label());
                            addr.setRecipientName(req.recipientName());
                            addr.setAddressLine1(req.addressLine1());
                            addr.setAddressLine2(req.addressLine2());
                            addr.setCity(req.city());
                            addr.setState(req.state());
                            addr.setZipCode(req.zipCode());
                            addr.setCountry(req.country());
                            addr.setIsDefault(req.isDefault() != null && req.isDefault());
                            addr.setUpdatedAt(Instant.now());
                            return deliveryAddressRepository.save(addr);
                        })))
                .map(this::toResponse);
    }

    public Mono<Void> deleteAddress(Long id, String username) {
        return deliveryAddressRepository.deleteByIdAndUsername(id, username);
    }

    public Mono<AddressResponse> getAddressByIdAndUsername(Long id, String username) {
        return deliveryAddressRepository.findByIdAndUsername(id, username)
                .map(this::toResponse);
    }

    private Mono<Void> unsetDefaultIfNeeded(String username, Boolean newDefault) {
        if (newDefault == null || !newDefault) {
            return Mono.empty();
        }
        return deliveryAddressRepository.findByUsername(username)
                .filter(DeliveryAddress::getIsDefault)
                .flatMap(addr -> {
                    addr.setIsDefault(false);
                    addr.setUpdatedAt(Instant.now());
                    return deliveryAddressRepository.save(addr);
                })
                .then();
    }

    private AddressResponse toResponse(DeliveryAddress addr) {
        return new AddressResponse(
                addr.getId(),
                addr.getLabel(),
                addr.getRecipientName(),
                addr.getAddressLine1(),
                addr.getAddressLine2(),
                addr.getCity(),
                addr.getState(),
                addr.getZipCode(),
                addr.getCountry(),
                addr.getIsDefault()
        );
    }
}
