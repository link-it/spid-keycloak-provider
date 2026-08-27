/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.broker.spid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.dom.saml.v2.protocol.NameIDPolicyType;
import org.keycloak.models.ClientModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.xml.datatype.DatatypeFactory;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers the per-client Issuer / AttributeConsumingServiceIndex override introduced to let a
 * single SPID/CIE Identity Provider instance serve many aggregated identities and attribute
 * datasets (see {@link SpidSamlAuthenticationPreprocessor#CLIENT_ATTRIBUTE_SPID_ISSUER} and
 * {@link SpidSamlAuthenticationPreprocessor#CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX}), instead of
 * requiring one broker instance per (issuer x dataset) combination.
 */
@ExtendWith(MockitoExtension.class)
class SpidSamlAuthenticationPreprocessorTest {

    private static final String DEFAULT_ISSUER = "https://default.example.it";

    private final SpidSamlAuthenticationPreprocessor preprocessor = new SpidSamlAuthenticationPreprocessor();

    @Mock
    private AuthenticationSessionModel authSession;

    @Mock
    private ClientModel client;

    private static AuthnRequestType newAuthnRequest(String issuerUrl) throws Exception {
        AuthnRequestType request = new AuthnRequestType("_id",
                DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
        NameIDType issuer = new NameIDType();
        issuer.setValue(issuerUrl);
        request.setIssuer(issuer);
        request.setNameIDPolicy(new NameIDPolicyType());
        return request;
    }

    @Test
    void beforeSendingLoginRequest_shouldNoOpWhenNotSpidFlow() throws Exception {
        when(authSession.getClientNote(SpidIdentityProvider.SPID_FLOW_MARKER)).thenReturn(null);

        AuthnRequestType result = preprocessor.beforeSendingLoginRequest(newAuthnRequest(DEFAULT_ISSUER), authSession);

        assertEquals(DEFAULT_ISSUER, result.getIssuer().getValue());
        assertNull(result.getAttributeConsumingServiceIndex());
    }

    @Test
    void beforeSendingLoginRequest_shouldKeepDefaultIssuerWhenClientHasNoOverride() throws Exception {
        when(authSession.getClientNote(SpidIdentityProvider.SPID_FLOW_MARKER)).thenReturn("true");
        when(authSession.getClient()).thenReturn(client);
        lenient().when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ISSUER)).thenReturn(null);
        lenient().when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX)).thenReturn(null);

        AuthnRequestType result = preprocessor.beforeSendingLoginRequest(newAuthnRequest(DEFAULT_ISSUER), authSession);

        assertEquals(DEFAULT_ISSUER, result.getIssuer().getValue());
        assertEquals(DEFAULT_ISSUER, result.getIssuer().getNameQualifier());
        assertNull(result.getAttributeConsumingServiceIndex());
    }

    @Test
    void beforeSendingLoginRequest_shouldOverrideIssuerAndAttributeIndexFromClient() throws Exception {
        String aggregatedIssuer = "https://aggregatore-spid.regione.puglia.it/pub-ag-full/P_LE";

        when(authSession.getClientNote(SpidIdentityProvider.SPID_FLOW_MARKER)).thenReturn("true");
        when(authSession.getClient()).thenReturn(client);
        when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ISSUER))
                .thenReturn(aggregatedIssuer);
        when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX))
                .thenReturn("2");

        AuthnRequestType result = preprocessor.beforeSendingLoginRequest(newAuthnRequest(DEFAULT_ISSUER), authSession);

        assertEquals(aggregatedIssuer, result.getIssuer().getValue());
        assertEquals(aggregatedIssuer, result.getIssuer().getNameQualifier());
        assertEquals(aggregatedIssuer, result.getNameIDPolicy().getSPNameQualifier());
        assertEquals(Integer.valueOf(2), result.getAttributeConsumingServiceIndex());
    }

    @Test
    void beforeSendingLoginRequest_shouldIgnoreNonNumericAttributeIndexOverride() throws Exception {
        when(authSession.getClientNote(SpidIdentityProvider.SPID_FLOW_MARKER)).thenReturn("true");
        when(authSession.getClient()).thenReturn(client);
        lenient().when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ISSUER)).thenReturn(null);
        when(client.getAttribute(SpidSamlAuthenticationPreprocessor.CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX))
                .thenReturn("not-a-number");
        lenient().when(client.getClientId()).thenReturn("test-client");

        AuthnRequestType result = preprocessor.beforeSendingLoginRequest(newAuthnRequest(DEFAULT_ISSUER), authSession);

        assertNull(result.getAttributeConsumingServiceIndex());
        assertEquals(DEFAULT_ISSUER, result.getIssuer().getValue());
    }
}
