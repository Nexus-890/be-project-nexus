package com.group5.userservice.iam;

import com.group5.common_core.model.ProtocolResponse;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwksController {

    private final JWKSet publicKeys;

    public JwksController(RSAKey jwtSigningKey) { //-> jwtSigningKey same BEAN with JwtKeyConfig
        //Only store public key
        this.publicKeys = new JWKSet(jwtSigningKey.toPublicJWK()); //CONTROLLER CANNOT STORE PRIVATE KEY
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ProtocolResponse("RFC 7517 requires a JWK Set object for OAuth resource servers")
    public Map<String, Object> getJwks() {
        return publicKeys.toJSONObject(true);
        //Chỉ return/export public site
    }
}
