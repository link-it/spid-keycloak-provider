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
 *
 * Modified by Link.it S.r.l., 2026: added support for overriding the SPID
 * Issuer and AttributeConsumingServiceIndex per requesting client.
 */
package org.keycloak.broker.spid;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.dom.saml.v2.protocol.AuthnRequestType;
import org.keycloak.dom.saml.v2.protocol.LogoutRequestType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.saml.preprocessor.SamlAuthenticationPreprocessor;
import org.keycloak.saml.SAML2NameIDBuilder;
import org.keycloak.saml.common.constants.JBossSAMLURIConstants;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * SPID-specific SAML authentication preprocessor that modifies AuthnRequest and LogoutRequest
 * to comply with SPID requirements:
 * - Adds NameQualifier and Format attributes to Issuer elements
 * - Adds SPNameQualifier to NameIDPolicy
 * - Stores request IssueInstant for response validation
 * - Lets the requesting client override the Issuer and AttributeConsumingServiceIndex
 *   (see {@link #CLIENT_ATTRIBUTE_SPID_ISSUER}, {@link #CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX}),
 *   so a single Identity Provider instance per real SPID/CIE provider can serve many
 *   aggregated identities and attribute datasets without one broker instance per combination.
 */
public class SpidSamlAuthenticationPreprocessor implements SamlAuthenticationPreprocessor {

    protected static final Logger logger = Logger.getLogger(SpidSamlAuthenticationPreprocessor.class);

    public static final String PROVIDER_ID = "spid-saml-preprocessor";

    /**
     * Client attribute: overrides the SPID Issuer (entityID, e.g. the aggregator
     * sub-entity form {@code subEntityID@aggregatorEntityID}) for AuthnRequests
     * originated by this client. Lets many clients share the same underlying
     * Identity Provider instance (one per real SPID/CIE provider) while each
     * still presents its own registered aggregated identity, instead of requiring
     * one broker instance per (issuer x dataset) combination.
     */
    public static final String CLIENT_ATTRIBUTE_SPID_ISSUER = "spid.issuer";

    /**
     * Client attribute: overrides the SPID AttributeConsumingServiceIndex for
     * AuthnRequests originated by this client. See {@link #CLIENT_ATTRIBUTE_SPID_ISSUER}.
     */
    public static final String CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX = "spid.attributeConsumingServiceIndex";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public SamlAuthenticationPreprocessor create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
        // No initialization needed
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No post-initialization needed
    }

    @Override
    public void close() {
        // No resources to close
    }

    @Override
    public AuthnRequestType beforeSendingLoginRequest(AuthnRequestType authnRequest,
                                                       AuthenticationSessionModel authSession) {
        if (!"true".equals(authSession.getClientNote(SpidIdentityProvider.SPID_FLOW_MARKER))) {
            return authnRequest;
        }

        // Get the issuer URL from the authnRequest (this is the IdP-instance-wide default,
        // taken from the broker's own "entityId" config)
        String issuerURL = authnRequest.getIssuer().getValue();

        // SPID: allow the requesting client to override the issuer and the
        // AttributeConsumingServiceIndex. Without this, presenting a different
        // aggregated identity (e.g. a different comune/provincia) or a different
        // attribute dataset requires a whole separate Identity Provider instance,
        // even though the target SPID/CIE provider is the same one. With this,
        // a single IdP instance per real provider can serve every client, and
        // the (issuer, dataset) pair becomes two attributes on the client instead
        // of a duplicated broker configuration.
        ClientModel client = authSession.getClient();
        if (client != null) {
            String issuerOverride = client.getAttribute(CLIENT_ATTRIBUTE_SPID_ISSUER);
            if (issuerOverride != null && !issuerOverride.isBlank()) {
                issuerURL = issuerOverride;
            }

            String attributeIndexOverride = client.getAttribute(CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX);
            if (attributeIndexOverride != null && !attributeIndexOverride.isBlank()) {
                try {
                    authnRequest.setAttributeConsumingServiceIndex(Integer.valueOf(attributeIndexOverride.trim()));
                } catch (NumberFormatException e) {
                    logger.warnf("Client '%s' has a non-numeric %s attribute ('%s'); ignoring override",
                            client.getClientId(), CLIENT_ATTRIBUTE_SPID_ATTRIBUTE_INDEX, attributeIndexOverride);
                }
            }
        }

        // SPID: Modify Issuer element - add NameQualifier and Format attributes
        NameIDType issuer = SAML2NameIDBuilder.value(issuerURL)
            .setNameQualifier(issuerURL)
            .setFormat(JBossSAMLURIConstants.NAMEID_FORMAT_ENTITY.get())
            .build();
        authnRequest.setIssuer(issuer);

        // SPID: Modify NameIDPolicy - add SPNameQualifier attribute
        if (authnRequest.getNameIDPolicy() != null) {
            authnRequest.getNameIDPolicy().setSPNameQualifier(issuerURL);
        }

        // Store the request IssueInstant in the auth session for SPID response validation
        if (authnRequest.getIssueInstant() != null) {
            authSession.setClientNote(SpidIdentityProvider.SPID_REQUEST_ISSUE_INSTANT,
                                      authnRequest.getIssueInstant().toXMLFormat());
        }

        return authnRequest;
    }

    @Override
    public LogoutRequestType beforeSendingLogoutRequest(LogoutRequestType logoutRequest,
                                                        UserSessionModel userSession,
                                                        AuthenticatedClientSessionModel clientSession) {
        if (!"true".equals(userSession.getNote(SpidIdentityProvider.SPID_FLOW_MARKER))) {
            return logoutRequest;
        }

        // Get the entity ID from the logoutRequest issuer
        String entityId = logoutRequest.getIssuer().getValue();

        // SPID: apply the same per-client issuer override used at login time, so the
        // LogoutRequest presents the same identity the IdP session was established
        // under (see CLIENT_ATTRIBUTE_SPID_ISSUER).
        ClientModel client = clientSession != null ? clientSession.getClient() : null;
        if (client != null) {
            String issuerOverride = client.getAttribute(CLIENT_ATTRIBUTE_SPID_ISSUER);
            if (issuerOverride != null && !issuerOverride.isBlank()) {
                entityId = issuerOverride;
            }
        }

        // SPID: Modify Issuer element - add NameQualifier and Format attributes
        NameIDType issuer = SAML2NameIDBuilder.value(entityId)
            .setNameQualifier(entityId)
            .setFormat(JBossSAMLURIConstants.NAMEID_FORMAT_ENTITY.get())
            .build();
        logoutRequest.setIssuer(issuer);

        return logoutRequest;
    }
}
