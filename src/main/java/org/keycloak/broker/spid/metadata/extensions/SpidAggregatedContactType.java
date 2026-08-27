/*
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

package org.keycloak.broker.spid.metadata.extensions;

import org.keycloak.broker.spid.SpidAggregatorConfig;
import org.keycloak.broker.spid.SpidIdentityProviderConfig;
import org.keycloak.dom.saml.v2.metadata.ContactType;
import org.keycloak.dom.saml.v2.metadata.ContactTypeType;
import org.keycloak.dom.saml.v2.metadata.ExtensionsType;
import org.keycloak.models.RealmModel;
import org.keycloak.saml.common.exceptions.ConfigurationException;
import org.keycloak.saml.common.util.DocumentUtil;
import org.keycloak.saml.common.util.StringUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Optional;

/**
 * ContactPerson type=other per l'Ente Aggregato SPID.
 * Genera il blocco XML con spid:entityType="spid:aggregated".
 *
 * Sorgente dei dati (in ordine di priorità):
 * 1. Configurazione del primo IdP SPID (campi otherContact* già presenti nel form)
 * 2. File /etc/keycloak/spid/{realm-name}/aggregator.properties
 * 3. File /etc/keycloak/spid/aggregator.properties
 * 4. Realm Attributes di Keycloak
 */
public class SpidAggregatedContactType extends ContactType {

    public static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    public static final String SPID_METADATA_EXTENSIONS_NS = "https://spid.gov.it/saml-extensions";

    private Document doc;
    private final String entityType = "spid:aggregated";

    /**
     * Costruisce il ContactPerson dell'ente aggregato leggendo i dati dalla
     * configurazione del primo IdP SPID (con fallback a file properties / realm attributes).
     *
     * @param realm     il RealmModel corrente
     * @param idpConfig configurazione del primo IdP SPID (può essere null)
     */
    public static Optional<SpidAggregatedContactType> build(RealmModel realm, SpidIdentityProviderConfig idpConfig)
            throws ConfigurationException {

        SpidAggregatorConfig aggregatorConfig = new SpidAggregatorConfig(realm);
        if (!aggregatorConfig.isAggregatorEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new SpidAggregatedContactType(aggregatorConfig, idpConfig));
    }

    /**
     * @deprecated Usare {@link #build(RealmModel, SpidIdentityProviderConfig)} per leggere
     *             i dati direttamente dalla configurazione IdP.
     */
    @Deprecated
    public static Optional<SpidAggregatedContactType> build(RealmModel realm)
            throws ConfigurationException {
        return build(realm, null);
    }

    protected SpidAggregatedContactType(SpidAggregatorConfig aggregatorConfig, SpidIdentityProviderConfig idpConfig)
            throws ConfigurationException {
        super(ContactTypeType.OTHER);

        doc = DocumentUtil.createDocument();

        // IPA Code: dall'IdP config (getIpaCode) → fallback al file properties / realm attributes
        String ipaCode = (idpConfig != null) ? idpConfig.getIpaCode() : null;
        if (StringUtil.isNullOrEmpty(ipaCode)) {
            ipaCode = aggregatorConfig.getAggregatedIpaCode();
        }

        // Company: dall'Organization Name dell'IdP (locale "it", poi prima disponibile)
        //          → fallback al file properties / realm attributes
        String company = (idpConfig != null) ? extractOrganizationName(idpConfig.getOrganizationNames()) : null;
        if (StringUtil.isNullOrEmpty(company)) {
            company = aggregatorConfig.getAggregatedCompany();
        }

        // Pubblico/Privato: dall'IdP config (isSpPrivate=false → ente pubblico) → fallback
        boolean isPublic;
        if (idpConfig != null && !StringUtil.isNullOrEmpty(idpConfig.getOtherContactCompany())) {
            // Se l'IdP config è valorizzata, usa il suo flag
            isPublic = !idpConfig.isSpPrivate();
        } else {
            isPublic = aggregatorConfig.isAggregatedPublic();
        }

        // Extensions
        this.setExtensions(new ExtensionsType());

        // IPACode
        addExtensionElement("spid:IPACode", ipaCode);

        // Public/Private qualifier
        if (isPublic) {
            addQualifier("spid:Public");
        } else {
            addQualifier("spid:Private");
        }

        // Company
        if (!StringUtil.isNullOrEmpty(company)) {
            this.setCompany(company);
        }
    }

    public String getEntityType() {
        return entityType;
    }

    protected void addQualifier(String qualifier) {
        Element element = doc.createElementNS(SPID_METADATA_EXTENSIONS_NS, qualifier);
        element.setAttributeNS(XMLNS_NS, "xmlns:spid", SPID_METADATA_EXTENSIONS_NS);
        getExtensions().addExtension(element);
    }

    protected void addExtensionElement(String name, String value) {
        if (!StringUtil.isNullOrEmpty(value)) {
            Element element = doc.createElementNS(SPID_METADATA_EXTENSIONS_NS, name);
            element.setAttributeNS(XMLNS_NS, "xmlns:spid", SPID_METADATA_EXTENSIONS_NS);
            element.setTextContent(value);
            getExtensions().addExtension(element);
        }
    }

    /**
     * Estrae il nome dall'Organization Names dell'IdP (formato "it|Nome,en|Name,...").
     * Priorità: locale "it" → prima voce disponibile.
     */
    private static String extractOrganizationName(String organizationNames) {
        if (StringUtil.isNullOrEmpty(organizationNames)) {
            return null;
        }
        String fallback = null;
        for (String entry : organizationNames.split(",")) {
            String[] parts = entry.trim().split("\\|", 2);
            if (parts.length == 2) {
                String locale = parts[0].trim();
                String name   = parts[1].trim();
                if ("it".equals(locale)) {
                    return name;
                }
                if (fallback == null) {
                    fallback = name;
                }
            }
        }
        return fallback;
    }
}
