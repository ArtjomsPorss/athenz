/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.athenz.instance.provider.impl;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.yahoo.athenz.auth.Authorizer;
import com.yahoo.athenz.auth.KeyStore;
import com.yahoo.athenz.auth.Principal;
import com.yahoo.athenz.auth.impl.SimplePrincipal;
import com.yahoo.athenz.auth.token.jwts.JwtsHelper;
import com.yahoo.athenz.auth.token.jwts.JwtsSigningKeyResolver;
import com.yahoo.athenz.common.server.util.config.dynamic.DynamicConfigLong;
import com.yahoo.athenz.instance.provider.InstanceConfirmation;
import com.yahoo.athenz.instance.provider.InstanceProvider;
import com.yahoo.athenz.instance.provider.ProviderResourceException;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.yahoo.athenz.common.server.util.config.ConfigManagerSingleton.CONFIG_MANAGER;

public class InstanceGithubActionsProvider implements InstanceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceGithubActionsProvider.class);

    private static final String URI_INSTANCE_ID_PREFIX = "athenz://instanceid/";
    private static final String URI_SPIFFE_PREFIX = "spiffe://";

    static final String GITHUB_ACTIONS_PROP_PROVIDER_DNS_SUFFIX  = "athenz.zts.github_actions.provider_dns_suffix";
    static final String GITHUB_ACTIONS_PROP_BOOT_TIME_OFFSET     = "athenz.zts.github_actions.boot_time_offset";
    static final String GITHUB_ACTIONS_PROP_CERT_EXPIRY_TIME     = "athenz.zts.github_actions.cert_expiry_time";
    static final String GITHUB_ACTIONS_PROP_ENTERPRISE           = "athenz.zts.github_actions.enterprise";
    static final String GITHUB_ACTIONS_PROP_AUDIENCE             = "athenz.zts.github_actions.audience";
    static final String GITHUB_ACTIONS_PROP_ISSUER               = "athenz.zts.github_actions.issuer";
    static final String GITHUB_ACTIONS_PROP_JWKS_URI             = "athenz.zts.github_actions.jwks_uri";

    static final String GITHUB_ACTIONS_ISSUER          = "https://token.actions.githubusercontent.com";
    static final String GITHUB_ACTIONS_ISSUER_JWKS_URI = "https://token.actions.githubusercontent.com/.well-known/jwks";

    public static final String CLAIM_ENTERPRISE    = "enterprise";
    public static final String CLAIM_RUN_ID        = "run_id";
    public static final String CLAIM_EVENT_NAME    = "event_name";
    public static final String CLAIM_REPOSITORY    = "repository";

    Set<String> dnsSuffixes = null;
    String githubIssuer = null;
    String provider = null;
    String audience = null;
    String enterprise = null;
    ConfigurableJWTProcessor<SecurityContext> jwtProcessor = null;
    Authorizer authorizer = null;
    DynamicConfigLong bootTimeOffsetSeconds;
    long certExpiryTime;

    @Override
    public Scheme getProviderScheme() {
        return Scheme.CLASS;
    }

    @Override
    public void initialize(String provider, String providerEndpoint, SSLContext sslContext,
            KeyStore keyStore) {

        // save our provider name

        this.provider = provider;

        // lookup the zts audience. if not specified we'll default to athenz.io

        audience = System.getProperty(GITHUB_ACTIONS_PROP_AUDIENCE, "athenz.io");

        // determine the dns suffix. if this is not specified we'll just default to github-actions.athenz.cloud

        final String dnsSuffix = System.getProperty(GITHUB_ACTIONS_PROP_PROVIDER_DNS_SUFFIX, "github-actions.athenz.io");
        dnsSuffixes = new HashSet<>();
        dnsSuffixes.addAll(Arrays.asList(dnsSuffix.split(",")));

        // how long the instance must be booted in the past before we
        // stop validating the instance requests

        long timeout = TimeUnit.SECONDS.convert(5, TimeUnit.MINUTES);
        bootTimeOffsetSeconds = new DynamicConfigLong(CONFIG_MANAGER, GITHUB_ACTIONS_PROP_BOOT_TIME_OFFSET, timeout);

        // determine if we're running in enterprise mode

        enterprise = System.getProperty(GITHUB_ACTIONS_PROP_ENTERPRISE);

        // get default/max expiry time for any generated tokens - 6 hours

        certExpiryTime = Long.parseLong(System.getProperty(GITHUB_ACTIONS_PROP_CERT_EXPIRY_TIME, "360"));

        // initialize our jwt processor

        githubIssuer = System.getProperty(GITHUB_ACTIONS_PROP_ISSUER, GITHUB_ACTIONS_ISSUER);
        jwtProcessor = JwtsHelper.getJWTProcessor(new JwtsSigningKeyResolver(extractGitHubIssuerJwksUri(githubIssuer), null));
    }

    String extractGitHubIssuerJwksUri(final String issuer) {

        // if we have the value configured then that's what we're going to use

        String jwksUri = System.getProperty(GITHUB_ACTIONS_PROP_JWKS_URI);
        if (!StringUtil.isEmpty(jwksUri)) {
            return jwksUri;
        }

        // otherwise we'll assume the issuer follows the standard and
        // includes the jwks uri in its openid configuration

        final String openIdConfigUri = issuer + "/.well-known/openid-configuration";
        JwtsHelper helper = new JwtsHelper();
        jwksUri = helper.extractJwksUri(openIdConfigUri, null);

        // if we still don't have a value we'll just return the default value

        return StringUtil.isEmpty(jwksUri) ? GITHUB_ACTIONS_ISSUER_JWKS_URI : jwksUri;
    }

    private ProviderResourceException forbiddenError(String message) {
        LOGGER.error(message);
        return new ProviderResourceException(ProviderResourceException.FORBIDDEN, message);
    }

    @Override
    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public InstanceConfirmation confirmInstance(InstanceConfirmation confirmation) throws ProviderResourceException {

        // before running any checks make sure we have a valid authorizer

        if (authorizer == null) {
            throw forbiddenError("Authorizer not available");
        }

        final String instanceDomain = confirmation.getDomain();
        final String instanceService = confirmation.getService();
        final Map<String, String> instanceAttributes = confirmation.getAttributes();

        // our request must not have any sanIPs or hostnames

        if (!StringUtil.isEmpty(InstanceUtils.getInstanceProperty(instanceAttributes,
                InstanceProvider.ZTS_INSTANCE_SAN_IP))) {
            throw forbiddenError("Request must not have any sanIP addresses");
        }

        if (!StringUtil.isEmpty(InstanceUtils.getInstanceProperty(instanceAttributes,
                InstanceProvider.ZTS_INSTANCE_HOSTNAME))) {
            throw forbiddenError("Request must not have any hostname values");
        }

        // validate san URI

        if (!validateSanUri(InstanceUtils.getInstanceProperty(instanceAttributes,
                InstanceProvider.ZTS_INSTANCE_SAN_URI))) {
            throw forbiddenError("Unable to validate certificate request URI values");
        }

        // we need to validate the token which is our attestation
        // data for the service requesting a certificate


        final String attestationData = confirmation.getAttestationData();
        if (StringUtil.isEmpty(attestationData)) {
            throw forbiddenError("Service credentials not provided");
        }

        StringBuilder errMsg = new StringBuilder(256);
        final String reqInstanceId = InstanceUtils.getInstanceProperty(instanceAttributes,
                InstanceProvider.ZTS_INSTANCE_ID);
         if (!validateOIDCToken(attestationData, instanceDomain, instanceService, reqInstanceId, errMsg)) {
             throw forbiddenError("Unable to validate Certificate Request: " + errMsg);
        }

        // validate the certificate san DNS names

        StringBuilder instanceId = new StringBuilder(256);
        if (!InstanceUtils.validateCertRequestSanDnsNames(instanceAttributes, instanceDomain,
                instanceService, dnsSuffixes, null, null, false, instanceId, null)) {
            throw forbiddenError("Unable to validate certificate request sanDNS entries");
        }

        // set our cert attributes in the return object.
        // for GitHub Actions we do not allow refresh of those certificates, and
        // the issued certificate can only be used by clients and not servers

        Map<String, String> attributes = new HashMap<>();
        attributes.put(InstanceProvider.ZTS_CERT_REFRESH, "false");
        attributes.put(InstanceProvider.ZTS_CERT_USAGE, ZTS_CERT_USAGE_CLIENT);
        attributes.put(InstanceProvider.ZTS_CERT_EXPIRY_TIME, Long.toString(certExpiryTime));

        confirmation.setAttributes(attributes);
        return confirmation;
    }

    @Override
    public InstanceConfirmation refreshInstance(InstanceConfirmation confirmation) throws ProviderResourceException {

        // we do not allow refresh of GitHub actions certificates

        throw forbiddenError("GitHub Action X.509 Certificates cannot be refreshed");
    }

    /**
     * verifies that sanUri only contains the spiffe and instance id uris
     * @param sanUri the SAN URI value
     * @return true if it only contains spiffe and instance id uris, otherwise false
     */
    boolean validateSanUri(final String sanUri) {

        if (StringUtil.isEmpty(sanUri)) {
            LOGGER.debug("Request contains no sanURI to verify");
            return true;
        }

        for (String uri: sanUri.split(",")) {
            if (uri.startsWith(URI_SPIFFE_PREFIX) || uri.startsWith(URI_INSTANCE_ID_PREFIX)) {
                continue;
            }
            LOGGER.error("Request contains unsupported uri value: {}", uri);
            return false;
        }

        return true;
    }

    boolean validateOIDCToken(final String jwToken, final String domainName, final String serviceName,
            final String instanceId, StringBuilder errMsg) {

        if (jwtProcessor == null) {
            errMsg.append("JWT Processor not initialized");
            return false;
        }

        JWTClaimsSet claimsSet;
        try {
            claimsSet = jwtProcessor.process(jwToken, null);
        } catch (Exception ex) {
            errMsg.append("Unable to parse and validate token: ").append(ex.getMessage());
            return false;
        }

        // verify the issuer in set to GitHub Actions

        if (!githubIssuer.equals(claimsSet.getIssuer())) {
            errMsg.append("token issuer is not GitHub Actions: ").append(claimsSet.getIssuer());
            return false;
        }

        // verify that token audience is set for our service

        if (!audience.equals(JwtsHelper.getAudience(claimsSet))) {
            errMsg.append("token audience is not ZTS Server audience: ").append(JwtsHelper.getAudience(claimsSet));
            return false;
        }

        // verify that token issuer is set for our enterprise if one is configured

        if (!StringUtil.isEmpty(enterprise)) {
            final String tokenEnterprise = JwtsHelper.getStringClaim(claimsSet, CLAIM_ENTERPRISE);
            if (!enterprise.equals(tokenEnterprise)) {
                errMsg.append("token enterprise is not the configured enterprise: ").append(tokenEnterprise);
                return false;
            }
        }

        // need to verify that the issue time is within our configured bootstrap time

        Date issueDate = claimsSet.getIssueTime();
        if (issueDate == null || issueDate.getTime() < System.currentTimeMillis() -
                TimeUnit.SECONDS.toMillis(bootTimeOffsetSeconds.get())) {
            errMsg.append("job start time is not recent enough, issued at: ").append(issueDate);
            return false;
        }

        // verify that the instance id matches the repository and run id in the token

        if (!validateInstanceId(instanceId, claimsSet, errMsg)) {
            return false;
        }

        // verify the domain and service names in the token based on our configuration

        return validateTenantDomainToken(claimsSet, domainName, serviceName, errMsg);
    }

    boolean validateInstanceId(final String instanceId, JWTClaimsSet claimsSet, StringBuilder errMsg) {

        // the format for the instance id is <org>:<repo>:<run_id>
        // the repository claim in the token has the format <org>/<repo>
        // so we'll extract that value and replace / with : to match our instance id

        final String runId = JwtsHelper.getStringClaim(claimsSet, CLAIM_RUN_ID);
        final String repository = JwtsHelper.getStringClaim(claimsSet, CLAIM_REPOSITORY);
        if (StringUtil.isEmpty(runId) || StringUtil.isEmpty(repository)) {
            errMsg.append("token does not contain required run_id or repository claims");
            return false;
        }
        final String tokenInstanceId = repository.replace("/", ":") + ":" + runId;
        if (!tokenInstanceId.equals(instanceId)) {
            errMsg.append("invalid instance id: ").append(tokenInstanceId).append("/").append(instanceId);
            return false;
        }
        return true;
    }

    boolean validateTenantDomainToken(final JWTClaimsSet claimsSet, final String domainName, final String serviceName,
            StringBuilder errMsg) {

        // we need to extract and generate our action value for the authz check

        final String eventName = JwtsHelper.getStringClaim(claimsSet, CLAIM_EVENT_NAME);
        if (StringUtil.isEmpty(eventName)) {
            errMsg.append("token does not contain required event_name claim");
            return false;
        }
        final String action = "github." + eventName;

        // we need to generate our resource value based on the subject

        final String subject = claimsSet.getSubject();
        if (StringUtil.isEmpty(subject)) {
            errMsg.append("token does not contain required subject claim");
            return false;
        }

        // generate our principal object and carry out authorization check

        final String resource = domainName + ":" + subject;
        Principal principal = SimplePrincipal.create(domainName, serviceName, (String) null);
        boolean accessCheck = authorizer.access(action, resource, principal, null);
        if (!accessCheck) {
            errMsg.append("authorization check failed for action: ").append(action)
                    .append(" resource: ").append(resource);
        }
        return accessCheck;
    }
}
