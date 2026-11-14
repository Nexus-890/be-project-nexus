package com.group5.userservice.address;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.address.dto.AddressRequest;
import com.group5.userservice.address.dto.AddressResponse;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AddressService {
    static final int MAX_ADDRESSES = 10;

    private final UserAddressRepository addresses;
    private final UserRepository users;

    public AddressService(UserAddressRepository addresses, UserRepository users) {
        this.addresses = addresses;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(UUID userId) {
        return addresses.findByUserIdOrderByDefaultAddressDescCreatedAtAscIdAsc(userId).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    public AddressResponse create(UUID userId, AddressRequest request) {
        lockOwner(userId);
        long count = addresses.countByUserId(userId);
        if (count >= MAX_ADDRESSES) {
            throw new DomainException(UserErrorCode.ADDRESS_LIMIT_REACHED);
        }
        UserAddress address = new UserAddress();
        address.setId(UUID.randomUUID());
        address.setUserId(userId);
        apply(address, request);
        boolean makeDefault = count == 0 || Boolean.TRUE.equals(request.isDefault());
        if (makeDefault) {
            addresses.clearDefault(userId, address.getId());
        }
        address.setDefaultAddress(makeDefault);
        return AddressResponse.from(addresses.saveAndFlush(address));
    }

    @Transactional
    public AddressResponse update(UUID userId, UUID addressId, AddressRequest request) {
        lockOwner(userId);
        if (Boolean.TRUE.equals(request.isDefault())) {
            addresses.clearDefault(userId, addressId);
        }
        UserAddress address = owned(userId, addressId);
        apply(address, request);
        if (Boolean.TRUE.equals(request.isDefault())) {
            address.setDefaultAddress(true);
        }
        return AddressResponse.from(addresses.saveAndFlush(address));
    }

    @Transactional
    public AddressResponse makeDefault(UUID userId, UUID addressId) {
        lockOwner(userId);
        owned(userId, addressId);
        addresses.clearDefault(userId, addressId);
        UserAddress address = owned(userId, addressId);
        address.setDefaultAddress(true);
        return AddressResponse.from(addresses.saveAndFlush(address));
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        lockOwner(userId);
        UserAddress address = owned(userId, addressId);
        boolean wasDefault = address.isDefaultAddress();
        addresses.delete(address);
        addresses.flush();
        if (wasDefault) {
            addresses.findFirstByUserIdOrderByUpdatedAtDescIdDesc(userId).ifPresent(next -> {
                next.setDefaultAddress(true);
                addresses.saveAndFlush(next);
            });
        }
    }

    private UserAddress owned(UUID userId, UUID addressId) {
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.ADDRESS_NOT_FOUND));
    }

    private void lockOwner(UUID userId) {
        users.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
    }

    private static void apply(UserAddress address, AddressRequest request) {
        address.setRecipientName(request.recipientName());
        address.setPhone(request.phone());
        address.setAddressLine(request.addressLine());
        address.setProvinceCode(request.provinceCode());
        address.setProvinceName(request.provinceName());
        address.setWardCode(request.wardCode());
        address.setWardName(request.wardName());
    }
}
