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

package org.keycloak.broker.spid;

/**
 * Costanti per gli attributi SPID a livello di Realm.
 * Questi attributi vengono configurati in Admin Console -> Realm Settings -> General -> Attributes
 */
public final class SpidRealmAttributes {

    private SpidRealmAttributes() {}

    // Aggregatore
    public static final String AGGREGATOR_ENABLED = "spid.aggregator.enabled";
    public static final String AGGREGATOR_TYPE = "spid.aggregator.type";
    public static final String AGGREGATOR_VAT_NUMBER = "spid.aggregator.vatNumber";
    public static final String AGGREGATOR_FISCAL_CODE = "spid.aggregator.fiscalCode";
    public static final String AGGREGATOR_COMPANY = "spid.aggregator.company";
    public static final String AGGREGATOR_EMAIL = "spid.aggregator.email";
    public static final String AGGREGATOR_PHONE = "spid.aggregator.phone";

    // Ente aggregato
    public static final String AGGREGATED_IPA_CODE = "spid.aggregated.ipaCode";
    public static final String AGGREGATED_COMPANY = "spid.aggregated.company";
    public static final String AGGREGATED_IS_PUBLIC = "spid.aggregated.isPublic";
}
