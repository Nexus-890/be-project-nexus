package com.group5.userservice.address;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.userservice.address.dto.AddressRequest;
import com.group5.userservice.address.dto.AddressResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addresses;

    @GetMapping
    public ApiResponse<List<AddressResponse>> list(CurrentUser currentUser) {
        return ApiResponse.list(addresses.list(currentUser.id()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddressResponse> create(CurrentUser currentUser, @Valid @RequestBody AddressRequest request) {
        return ApiResponse.of(addresses.create(currentUser.id(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AddressResponse> update(CurrentUser currentUser, @PathVariable UUID id,
                                               @Valid @RequestBody AddressRequest request) {
        return ApiResponse.of(addresses.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser currentUser, @PathVariable UUID id) {
        addresses.delete(currentUser.id(), id);
    }

    @PostMapping("/{id}/default")
    public ApiResponse<AddressResponse> makeDefault(CurrentUser currentUser, @PathVariable UUID id) {
        return ApiResponse.of(addresses.makeDefault(currentUser.id(), id));
    }
}
