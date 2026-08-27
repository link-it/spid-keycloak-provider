# HowTo: multi-tenant SPID setups without a broker instance per tenant

Two related extensions let a single SPID Identity Provider broker instance serve multiple
"tenants" — different issuers and/or different requested attribute datasets — without creating
a separate Identity Provider alias for each one:

1. **Per-client issuer / AttributeConsumingServiceIndex override** (`SpidSamlAuthenticationPreprocessor`)
2. **Multiple `AttributeConsumingService` blocks in the published SP metadata** (`SpidSpMetadataResourceProvider`)

They are independent — you can use either on its own — but they are designed to be combined,
since a real SPID IdP validates the requested `AttributeConsumingServiceIndex` against what your
SP metadata actually publishes.

## Use case 1 — one shared IdP alias, many issuers

**Problem it solves:** a SPID Aggregatore pattern, where a single legal entity is registered with
AgID as an aggregator and federates many other bodies (municipalities, provinces, agencies) under
it. Each aggregated body gets its own AgID-registered SAML entity, typically at a URL like
`https://aggregatore-spid.<aggregator-domain>/pub-ag-full/<CODE>` (`CODE` following whatever
per-body coding scheme the aggregator uses — e.g. a province abbreviation or a national
municipality code). Every client application belonging to a given body must present that body's
own `Issuer` in the `AuthnRequest`/`LogoutRequest` it sends to the real SPID IdP. Without this
feature you would need one Identity Provider broker instance per aggregated body — same real IdP,
same certificate, same everything, differing only in the issuer string — which does not scale once
you're aggregating dozens of bodies behind one broker.

**How it works:** `SpidSamlAuthenticationPreprocessor` already runs for every login/logout request
originated through a SPID broker (see [scope-samlauthenticationpreprocessor.md](scope-samlauthenticationpreprocessor.md)
for how it's scoped to SPID-only flows). It now also reads two attributes off the Keycloak
**client** that started the flow — `authSession.getClient()` for login,
`clientSession.getClient()` for logout — and if present, overrides the issuer (and, for login,
the requested `AttributeConsumingServiceIndex`) before the request is signed and sent:

| Client attribute | Purpose | Applies to |
|---|---|---|
| `spid.issuer` | Overrides the `Issuer` element in the outgoing `AuthnRequest`/`LogoutRequest` | Login + logout |
| `spid.attributeConsumingServiceIndex` | Overrides `AuthnRequestType.setAttributeConsumingServiceIndex(...)` | Login only |

If a client has neither attribute set, behaviour is unchanged — the IdP's configured default
issuer and index are used. If `spid.attributeConsumingServiceIndex` is not a valid integer, it is
ignored and a warning is logged (`SpidSamlAuthenticationPreprocessor`); the flow still proceeds
with the default index.

### Configuring it

1. In the Keycloak Admin Console, go to the realm's **Clients** → select the client → **Advanced**
   tab → **Extra client attributes** (or set it via `ClientRepresentation.attributes` through the
   Admin REST API / a realm export).
2. Add:
   - `spid.issuer` = the issuer string this client must present, e.g.
     `https://aggregatore-spid.example-aggregator.it/pub-ag-full/C_A001`
   - `spid.attributeConsumingServiceIndex` = the numeric index this client wants (must match an
     index actually published in the SP metadata — see use case 2 below), e.g. `2`
3. Leave the SPID Identity Provider alias's own configuration (issuer, default
   AttributeConsumingServiceIndex) as the fallback used by clients that don't set these attributes.
4. No code/theme changes are needed beyond what's already built into the jar — this is pure
   per-client configuration.

**Example:** a broker aggregates ~20 municipalities/provinces behind one real IdP. Each body's
client gets its own `spid.issuer`, following the aggregator's registered URL pattern:

| Keycloak client | `spid.issuer` | `spid.attributeConsumingServiceIndex` |
|---|---|---|
| `portale-comune-alfa` | `https://aggregatore-spid.example-aggregator.it/pub-ag-full/C_A001` | `1` (default, not set) |
| `portale-comune-beta` | `https://aggregatore-spid.example-aggregator.it/pub-ag-full/C_B002` | `2` |
| `portale-provincia-gamma` | `https://aggregatore-spid.example-aggregator.it/pub-ag-full/P_GA` | `1` (default, not set) |

Most bodies keep the default `spid.attributeConsumingServiceIndex` (dataset "minimo" — see use
case 2); only `portale-comune-beta`'s application needs the richer dataset, so it overrides the
index to `2`.

## Use case 2 — one shared IdP alias, multiple attribute datasets

**Problem it solves:** the SP metadata published by `SpidSpMetadataResourceProvider` previously
contained exactly one `AttributeConsumingService` block — the one built from that IdP alias's own
Mapper configuration. A real SPID IdP validates the `AttributeConsumingServiceIndex` requested in
the `AuthnRequest` against the indices actually present in the SP's registered metadata; requesting
an index the metadata doesn't publish causes the IdP to reject the request. This meant every
distinct dataset (e.g. "minimo" for one portal, "esteso" with email/phone for another) needed its
own IdP alias, even when everything else (certificate, endpoints, upstream IdP) was identical.

**How it works:** `SpidIdentityProviderConfig` gained a new config field,
`additionalAttributeConsumingServices`, on the Identity Provider itself. `SpidSpMetadataResourceProvider`
still builds the *default* `AttributeConsumingService` block from the alias's Mapper configuration
exactly as before (this stays the `isDefault="true"` block, whatever index the alias is configured
with). After that, it parses `additionalAttributeConsumingServices` and appends one extra
`AttributeConsumingService` block per non-blank, non-comment line.

> **Why this bypasses the Mapper mechanism for the extra blocks:** Keycloak's built-in
> `UserAttributeMapper.updateMetadata()` broadcasts each configured `RequestedAttribute` to
> **every** `AttributeConsumingService` already present in the descriptor at the time mappers run
> (not just the default one). If the extra blocks were also populated via Mappers, every mapper on
> the alias would leak into every block, making it impossible to give two datasets different
> attribute sets. Extra blocks are therefore built directly from this dedicated config string,
> added only *after* the Mapper loop has already run, so they're never touched by it.

### Config format

One line per additional block:

```
index|service name|attr1:friendlyName1,attr2:friendlyName2,...
```

- `index` — integer, must be unique and distinct from the default block's index; this is the value
  clients pass via `spid.attributeConsumingServiceIndex` (use case 1) to select this block.
- `service name` — free text, published as `<md:ServiceName xml:lang="it">`.
- attribute list — comma-separated `attributeName:friendlyName` pairs. `friendlyName` is optional
  (omit the `:` to publish the attribute with no `FriendlyName`). Attribute names are the same
  plain SPID attribute names already used elsewhere in this provider's Mapper configuration
  (`spidCode`, `name`, `familyName`, `fiscalNumber`, `email`, `mobilePhone`, ...) — not URIs/OIDs.
- Blank lines and lines starting with `#` are skipped.
- A malformed line (wrong number of `|`-separated fields, non-numeric index, or zero valid
  attributes after parsing) is skipped with a logged warning; it does not fail metadata generation
  or affect the default block.

### Configuring it

1. In the Keycloak Admin Console, go to **Identity Providers** → select the SPID alias → find the
   **Additional AttributeConsumingService blocks** field (theme key
   `identity-provider.spid.additional-attribute-consuming-services`, added to
   `messages_it.properties`/`messages_en.properties`).
2. Enter one line per extra dataset, e.g.:
   ```
   2|Servizi online (esteso)|spidCode,name,familyName,fiscalNumber,email:Email,mobilePhone
   ```
3. Save. Re-fetch `/realms/<realm>/broker/<alias>/endpoint/descriptor` (the SP metadata endpoint)
   and confirm the new `<md:AttributeConsumingService index="2" isDefault="false">` block appears
   alongside the existing default one.
4. If the real SPID IdP you federate with caches your SP metadata (most do, refreshed
   periodically), you may need to trigger a re-fetch on their side or wait for their refresh cycle
   before the new index is accepted.

**Example:** continuing the scenario above, the alias's default block (index `1`, `isDefault="true"`)
publishes the "minimo" dataset (`fiscalNumber,name,familyName`) used by most bodies.
`portale-comune-beta`'s application needs email and mobile phone too, so
`additionalAttributeConsumingServices` gets one extra line for index `2`:
```
2|Servizi online (esteso)|spidCode,name,familyName,fiscalNumber,email:Email,mobilePhone
```
and `portale-comune-beta`'s client sets `spid.attributeConsumingServiceIndex=2` (use case 1) to
request it.

## Combining both

The two features compose directly:

1. Configure the IdP alias's default `AttributeConsumingService` (existing Mapper-based
   configuration, unchanged) plus any extra indices via `additionalAttributeConsumingServices`.
2. For each client (tenant) that needs a non-default issuer and/or a non-default dataset, set
   `spid.issuer` and/or `spid.attributeConsumingServiceIndex` on that client.
3. Clients that set neither attribute keep using the alias's default issuer and default
   (`isDefault="true"`) dataset — fully backward compatible with realms that don't use either
   feature.

This lets N tenants share one IdP broker instance (one certificate, one set of endpoints) while
each independently controls its issuer identity and requested attribute set, instead of an N×M
explosion of near-identical Identity Provider aliases.

## Related files

| File | Role |
|---|---|
| `SpidSamlAuthenticationPreprocessor.java` | Reads `spid.issuer` / `spid.attributeConsumingServiceIndex` client attributes, overrides the outgoing request |
| `SpidIdentityProviderConfig.java` | Defines `ADDITIONAL_ATTRIBUTE_CONSUMING_SERVICES` config field and admin console property |
| `SpidSpMetadataResourceProvider.java` | Parses the config and appends extra `AttributeConsumingService` blocks to the published SP metadata |
| `messages_it.properties` / `messages_en.properties` | Admin console label/tooltip for the new IdP config field |
