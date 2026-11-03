package com.group5.common_web.architecture;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.ProtocolResponse;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/** Shared build-time guard for every business REST operation. */
public final class ApiContractRules {
    private ApiContractRules() {
    }

    public static final ArchRule CONTROLLERS_RETURN_API_RESPONSE = methods()
            .that(new DescribedPredicate<JavaMethod>("are mapped REST controller methods") {
                @Override
                public boolean test(JavaMethod method) {
                    return AnnotatedElementUtils.hasAnnotation(method.getOwner().reflect(), RestController.class)
                            && AnnotatedElementUtils.hasAnnotation(method.reflect(), RequestMapping.class);
                }
            })
            .should(new ArchCondition<JavaMethod>("return ApiResponse, ResponseEntity<ApiResponse>, void or ResponseEntity<Void>") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    Method reflected = method.reflect();
                    ProtocolResponse protocol = reflected.getAnnotation(ProtocolResponse.class);
                    if (protocol != null) {
                        events.add(new SimpleConditionEvent(method, !protocol.value().isBlank(),
                                method.getFullName() + " must document the external protocol exemption"));
                        return;
                    }
                    Type returned = reflected.getGenericReturnType();
                    boolean valid = returned == void.class || isEnvelope(returned) || isResponseEntity(returned);
                    events.add(new SimpleConditionEvent(method, valid,
                            method.getFullName() + " returns " + returned.getTypeName()
                                    + "; REST operations must use the shared success envelope"));
                }
            });

    private static boolean isEnvelope(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == ApiResponse.class;
    }

    private static boolean isResponseEntity(Type type) {
        if (!(type instanceof ParameterizedType parameterized)
                || parameterized.getRawType() != ResponseEntity.class) {
            return false;
        }
        Type body = parameterized.getActualTypeArguments()[0];
        return body == Void.class || isEnvelope(body);
    }
}
