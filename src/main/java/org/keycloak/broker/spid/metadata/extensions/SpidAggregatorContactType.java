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
 * ContactPerson type=other per l'Aggregatore SPID.
 * Genera il blocco XML con spid:entityType="spid:aggregator"
 */
public class SpidAggregatorContactType extends ContactType {

    public static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    public static final String SPID_METADATA_EXTENSIONS_NS = "https://spid.gov.it/saml-extensions";

    private Document doc;
    private final String entityType = "spid:aggregator";

    /**
     * Costruisce il ContactPerson dell'aggregatore se abilitato.
     * Legge la configurazione da file properties o Realm Attributes.
     */
    public static Optional<SpidAggregatorContactType> build(RealmModel realm)
            throws ConfigurationException {

        SpidAggregatorConfig config = new SpidAggregatorConfig(realm);
        if (!config.isAggregatorEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new SpidAggregatorContactType(config));
    }

    protected SpidAggregatorContactType(SpidAggregatorConfig config) throws ConfigurationException {
        super(ContactTypeType.OTHER);

        doc = DocumentUtil.createDocument();

        String company = config.getAggregatorCompany();
        String email = config.getAggregatorEmail();
        String phone = config.getAggregatorPhone();
        String vatNumber = config.getAggregatorVatNumber();
        String fiscalCode = config.getAggregatorFiscalCode();
        String aggregatorType = config.getAggregatorType();

        // Extensions (devono essere prima degli altri elementi per conformità SPID)
        this.setExtensions(new ExtensionsType());

        // VATNumber
        addExtensionElement("spid:VATNumber", vatNumber);

        // FiscalCode
        addExtensionElement("spid:FiscalCode", fiscalCode);

        // Aggregator type qualifier (es. PublicServicesFullAggregator)
        if (!StringUtil.isNullOrEmpty(aggregatorType)) {
            addQualifier("spid:" + aggregatorType);
        }

        // Company
        if (!StringUtil.isNullOrEmpty(company)) {
            this.setCompany(company);
        }

        // Email
        if (!StringUtil.isNullOrEmpty(email)) {
            this.addEmailAddress(email);
        }

        // Phone
        if (!StringUtil.isNullOrEmpty(phone)) {
            this.addTelephone(phone);
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
}
