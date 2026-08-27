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

import org.jboss.logging.Logger;
import org.keycloak.models.RealmModel;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurazione per l'aggregatore SPID.
 *
 * Gerarchia di lettura (dalla più specifica alla più generica):
 * 1. /etc/keycloak/spid/{realm-name}/aggregator.properties  (per-realm, priorità massima)
 * 2. /etc/keycloak/spid/aggregator.properties               (globale, fallback)
 * 3. Realm Attributes di Keycloak                           (ultimo fallback)
 *
 * I file vengono ricaricati automaticamente se modificati su disco, senza riavvio.
 */
public class SpidAggregatorConfig {

    private static final Logger logger = Logger.getLogger(SpidAggregatorConfig.class);

    // File globale (shared tra tutti i realm)
    private static final String GLOBAL_CONFIG_FILE = "/etc/keycloak/spid/aggregator.properties";

    // Pattern per file per-realm: /etc/keycloak/spid/{realm-name}/aggregator.properties
    private static final String REALM_CONFIG_FILE_PATTERN = "/etc/keycloak/spid/%s/aggregator.properties";

    // Cache del file globale
    private static Properties globalCachedProperties = null;
    private static long globalLastModified = 0;

    // Cache dei file per-realm: realm-name -> Properties
    private static final ConcurrentHashMap<String, Properties> realmCachedProperties = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> realmLastModified = new ConcurrentHashMap<>();

    private final RealmModel realm;
    private final Properties globalProperties;
    private final Properties realmProperties;

    public SpidAggregatorConfig(RealmModel realm) {
        this.realm = realm;
        this.globalProperties = loadGlobalProperties();
        this.realmProperties = (realm != null) ? loadRealmProperties(realm.getName()) : new Properties();
    }

    /**
     * Carica il file globale con cache e ricaricamento automatico se il file cambia.
     */
    private static synchronized Properties loadGlobalProperties() {
        return loadPropertiesFile(GLOBAL_CONFIG_FILE,
            () -> globalCachedProperties,
            (props) -> { globalCachedProperties = props; },
            () -> globalLastModified,
            (ts) -> { globalLastModified = ts; },
            "globale");
    }

    /**
     * Carica il file per-realm con cache e ricaricamento automatico se il file cambia.
     */
    private static Properties loadRealmProperties(String realmName) {
        if (realmName == null || realmName.isEmpty()) {
            return new Properties();
        }
        String filePath = String.format(REALM_CONFIG_FILE_PATTERN, realmName);
        synchronized (realmCachedProperties) {
            return loadPropertiesFile(filePath,
                () -> realmCachedProperties.get(realmName),
                (props) -> realmCachedProperties.put(realmName, props),
                () -> realmLastModified.getOrDefault(realmName, 0L),
                (ts) -> realmLastModified.put(realmName, ts),
                "realm '" + realmName + "'");
        }
    }

    @FunctionalInterface
    interface Supplier<T> { T get(); }
    @FunctionalInterface
    interface Consumer<T> { void accept(T t); }

    private static Properties loadPropertiesFile(
            String filePath,
            Supplier<Properties> getCached,
            Consumer<Properties> setCached,
            Supplier<Long> getLastMod,
            Consumer<Long> setLastMod,
            String label) {

        Path configPath = Paths.get(filePath);

        if (!Files.exists(configPath)) {
            return new Properties();
        }

        try {
            long currentModified = Files.getLastModifiedTime(configPath).toMillis();
            Properties cached = getCached.get();

            if (cached == null || currentModified > getLastMod.get()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(filePath)) {
                    props.load(fis);
                }
                setCached.accept(props);
                setLastMod.accept(currentModified);
                logger.infof("Configurazione SPID aggregatore caricata [%s]: %s", label, filePath);
                return props;
            }

            return cached;
        } catch (IOException e) {
            logger.warnf("Errore nel caricamento della configurazione [%s]: %s", label, filePath);
            Properties cached = getCached.get();
            return cached != null ? cached : new Properties();
        }
    }

    /**
     * Ottiene un valore seguendo la gerarchia:
     * 1. File per-realm
     * 2. File globale
     * 3. Realm Attributes
     */
    private String getValue(String key) {
        // 1. File per-realm
        String value = realmProperties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }

        // 2. File globale
        value = globalProperties.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }

        // 3. Realm Attributes
        if (realm != null) {
            value = realm.getAttribute(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        return null;
    }

    // === Getter ===

    public boolean isAggregatorEnabled() {
        return "true".equalsIgnoreCase(getValue(SpidRealmAttributes.AGGREGATOR_ENABLED));
    }

    public String getAggregatorType() {
        return getValue(SpidRealmAttributes.AGGREGATOR_TYPE);
    }

    public String getAggregatorVatNumber() {
        return getValue(SpidRealmAttributes.AGGREGATOR_VAT_NUMBER);
    }

    public String getAggregatorFiscalCode() {
        return getValue(SpidRealmAttributes.AGGREGATOR_FISCAL_CODE);
    }

    public String getAggregatorCompany() {
        return getValue(SpidRealmAttributes.AGGREGATOR_COMPANY);
    }

    public String getAggregatorEmail() {
        return getValue(SpidRealmAttributes.AGGREGATOR_EMAIL);
    }

    public String getAggregatorPhone() {
        return getValue(SpidRealmAttributes.AGGREGATOR_PHONE);
    }

    public String getAggregatedIpaCode() {
        return getValue(SpidRealmAttributes.AGGREGATED_IPA_CODE);
    }

    public String getAggregatedCompany() {
        return getValue(SpidRealmAttributes.AGGREGATED_COMPANY);
    }

    public boolean isAggregatedPublic() {
        return "true".equalsIgnoreCase(getValue(SpidRealmAttributes.AGGREGATED_IS_PUBLIC));
    }

    /**
     * Forza il ricaricamento di tutta la configurazione (globale + tutti i realm).
     */
    public static synchronized void reloadConfig() {
        globalCachedProperties = null;
        globalLastModified = 0;
        realmCachedProperties.clear();
        realmLastModified.clear();
    }

    /**
     * Forza il ricaricamento della configurazione di un singolo realm.
     */
    public static synchronized void reloadRealmConfig(String realmName) {
        realmCachedProperties.remove(realmName);
        realmLastModified.remove(realmName);
    }
}
