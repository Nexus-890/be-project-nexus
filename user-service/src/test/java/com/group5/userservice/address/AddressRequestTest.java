package com.group5.userservice.address;

import com.group5.userservice.address.dto.AddressRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AddressRequestTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void aCompleteAddressIsValidAndTrimmed() {
        AddressRequest request = new AddressRequest("  Nguyen Van A ", " +84 (90) 123-4567 ", " 12 Le Loi ",
                " 79 ", " Ho Chi Minh ", " 26734 ", " Phuong Ben Thanh ", null);

        assertThat(violationFields(request)).isEmpty();
        assertThat(request.recipientName()).isEqualTo("Nguyen Van A");
        assertThat(request.phone()).isEqualTo("+84901234567");
        assertThat(request.provinceCode()).isEqualTo("79");
    }

    @Test
    void everyRequiredFieldIsNamed() {
        assertThat(violationFields(new AddressRequest(" ", null, "", null, " ", null, "", true)))
                .containsExactlyInAnyOrder("recipientName", "phone", "addressLine", "provinceCode",
                        "provinceName", "wardCode", "wardName");
    }

    @Test
    void codesPhoneAndLengthsAreChecked() {
        AddressRequest request = new AddressRequest("A".repeat(151), "09-abc", "L".repeat(256),
                "079", "P".repeat(101), "1234567890", "W".repeat(101), false);

        assertThat(violationFields(request)).containsExactlyInAnyOrder("recipientName", "phone", "addressLine",
                "provinceCode", "provinceName", "wardCode", "wardName");
    }

    private static Set<String> violationFields(AddressRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
