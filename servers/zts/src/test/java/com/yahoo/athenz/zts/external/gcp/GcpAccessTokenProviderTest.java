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

package com.yahoo.athenz.zts.external.gcp;

import com.yahoo.athenz.auth.Authorizer;
import com.yahoo.athenz.auth.Principal;
import com.yahoo.athenz.auth.token.IdToken;
import com.yahoo.athenz.common.server.external.IdTokenSigner;
import com.yahoo.athenz.common.server.http.HttpDriver;
import com.yahoo.athenz.common.server.http.HttpDriverResponse;
import com.yahoo.athenz.common.server.ServerResourceException;
import com.yahoo.athenz.zts.DomainDetails;
import com.yahoo.athenz.zts.ExternalCredentialsRequest;
import com.yahoo.athenz.zts.ExternalCredentialsResponse;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

public class GcpAccessTokenProviderTest {

    public static final String EXCHANGE_TOKEN_RESPONSE_STR = "{\n" +
            "  \"access_token\": \"exchange-token\",\n" +
            "  \"issued_token_type\": \"jwt\",\n" +
            "  \"token_type\": \"Bearer\",\n" +
            "  \"expires_in\": 300\n" +
            "}";
    public static final String ACCESS_TOKEN_RESPONSE_STR = "{\n" +
            "  \"accessToken\": \"access-token\",\n" +
            "  \"expireTime\": \"2014-10-02T15:01:23Z\"\n" +
            "}";
    public static final String EXCHANGE_TOKEN_ERROR_STR = "{\n" +
            "  \"error\": \"failure\",\n" +
            "  \"error_description\": \"gcp exchange token error\",\n" +
            "  \"attribute\": \"unknown-attribute\"\n" +
            "}";
    public static final String ACCESS_TOKEN_ERROR_STR = "{\n" +
            "  \"error\": {\n" +
            "    \"code\": 403,\n" +
            "    \"message\": \"Permission 'iam.serviceAccounts.getAccessToken' denied on resource (or it may not exist).\",\n" +
            "    \"status\": \"PERMISSION_DENIED\",\n" +
            "    \"details\": [\n" +
            "      {\n" +
            "        \"@type\": \"type.googleapis.com/google.rpc.ErrorInfo\",\n" +
            "        \"reason\": \"IAM_PERMISSION_DENIED\",\n" +
            "        \"domain\": \"iam.googleapis.com\",\n" +
            "        \"metadata\": {\n" +
            "          \"permission\": \"iam.serviceAccounts.getAccessToken\"\n" +
            "        }\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";
    static final IdTokenSigner signer = (idToken, keyType) -> "id-token";

    @Test
    public void testGcpAccessTokenProviderFailures() throws IOException {

        GcpAccessTokenProvider provider = new GcpAccessTokenProvider();
        Principal principal = Mockito.mock(Principal.class);
        DomainDetails domainDetails = new DomainDetails();
        List<String> idTokenGroups = new ArrayList<>();
        ExternalCredentialsRequest request = new ExternalCredentialsRequest();
        Map<String, String> attributes = new HashMap<>();
        request.setAttributes(attributes);

        // authorizer not configured

        try {
            provider.getCredentials(principal, domainDetails, idTokenGroups, new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), ServerResourceException.FORBIDDEN);
            assertTrue(ex.getMessage().contains("ZTS authorizer not configured"));
        }

        // gcp project not present

        Authorizer authorizer = Mockito.mock(Authorizer.class);
        provider.setAuthorizer(authorizer);

        try {
            provider.getCredentials(principal, domainDetails, idTokenGroups, new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), ServerResourceException.FORBIDDEN);
            assertTrue(ex.getMessage().contains("gcp project id not configured"));
        }

        // gcp service account not present

        domainDetails.setGcpProjectId("gcp-project").setGcpProjectNumber("123");

        try {
            provider.getCredentials(principal, domainDetails, idTokenGroups, new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), ServerResourceException.BAD_REQUEST);
            assertTrue(ex.getMessage().contains("missing gcp service account"));
        }

        // not authorized

        attributes.put(GcpAccessTokenProvider.GCP_SERVICE_ACCOUNT, "gcp-service");
        when(authorizer.access(any(), any(), any(), any())).thenReturn(false);

        try {
            provider.getCredentials(principal, domainDetails, idTokenGroups, new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), ServerResourceException.FORBIDDEN);
            assertTrue(ex.getMessage().contains("Principal not authorized for configured scope"));
        }

        // http driver returning failure

        HttpDriver httpDriver = Mockito.mock(HttpDriver.class);
        when(httpDriver.doPostHttpResponse(any())).thenThrow(new IOException("http-failure"));
        provider.setHttpDriver(httpDriver);
        when(authorizer.access(any(), any(), any(), any())).thenReturn(true);

        try {
            provider.getCredentials(principal, domainDetails, idTokenGroups, new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), ServerResourceException.FORBIDDEN);
            assertTrue(ex.getMessage().contains("http-failure"));
        }
    }

    @Test
    public void testGcpAccessTokenProvider() throws IOException, ServerResourceException {

        GcpAccessTokenProvider provider = new GcpAccessTokenProvider();

        Principal principal = Mockito.mock(Principal.class);
        when(principal.getFullName()).thenReturn("user.joe");
        IdToken idToken = new IdToken();
        List<String> idTokenGroups = new ArrayList<>();
        idTokenGroups.add("domain:role.reader");
        idTokenGroups.add("domain:role.writer");
        DomainDetails domainDetails = new DomainDetails()
                .setGcpProjectId("gcp-project")
                .setGcpProjectNumber("gcp-project-number");

        ExternalCredentialsRequest request = new ExternalCredentialsRequest();
        request.setClientId("domain.gcp");
        request.setExpiryTime(1800);
        Map<String, String> attributes = new HashMap<>();
        attributes.put(GcpAccessTokenProvider.GCP_SERVICE_ACCOUNT, "gcp-service");
        request.setAttributes(attributes);

        Authorizer authorizer = Mockito.mock(Authorizer.class);
        provider.setAuthorizer(authorizer);

        HttpDriver httpDriver = Mockito.mock(HttpDriver.class);
        HttpDriverResponse exchangeTokenResponse = new HttpDriverResponse(200, EXCHANGE_TOKEN_RESPONSE_STR, null);
        HttpDriverResponse accessTokenResponse = new HttpDriverResponse(200, ACCESS_TOKEN_RESPONSE_STR, null);
        when(httpDriver.doPostHttpResponse(any())).thenReturn(exchangeTokenResponse, accessTokenResponse);

        provider.setHttpDriver(httpDriver);
        when(authorizer.access(any(), any(), any(), any())).thenReturn(true);

        ExternalCredentialsResponse response = provider.getCredentials(principal, domainDetails, idTokenGroups, idToken, signer, request);
        assertNotNull(response);
        Map<String, String> responseAttributes = response.getAttributes();
        assertEquals(responseAttributes.get("accessToken"), "access-token");
        assertEquals(idToken.getSubject(), "user.joe");
        assertEquals(idToken.getAudience(), "domain.gcp");
        assertEquals(idToken.getGroups(), idTokenGroups);
    }

    @Test
    public void testGcpAccessTokenProviderExchangeTokenFailure() throws IOException {

        GcpAccessTokenProvider provider = new GcpAccessTokenProvider();

        Principal principal = Mockito.mock(Principal.class);
        DomainDetails domainDetails = new DomainDetails()
                .setGcpProjectId("gcp-project")
                .setGcpProjectNumber("gcp-project-number");

        ExternalCredentialsRequest request = new ExternalCredentialsRequest();
        request.setExpiryTime(1800);
        Map<String, String> attributes = new HashMap<>();
        attributes.put(GcpAccessTokenProvider.GCP_SERVICE_ACCOUNT, "gcp-service");
        request.setAttributes(attributes);

        Authorizer authorizer = Mockito.mock(Authorizer.class);
        provider.setAuthorizer(authorizer);

        HttpDriver httpDriver = Mockito.mock(HttpDriver.class);
        HttpDriverResponse exchangeTokenResponse = new HttpDriverResponse(401, EXCHANGE_TOKEN_ERROR_STR, null);
        when(httpDriver.doPostHttpResponse(any())).thenReturn(exchangeTokenResponse);

        provider.setHttpDriver(httpDriver);
        when(authorizer.access(any(), any(), any(), any())).thenReturn(true);

        try {
            provider.getCredentials(principal, domainDetails, new ArrayList<>(), new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), 403);
            assertTrue(ex.getMessage().contains("gcp exchange token error"));
        }
    }

    @Test
    public void testGcpAccessTokenProviderAccessTokenFailure() throws IOException {

        GcpAccessTokenProvider provider = new GcpAccessTokenProvider();

        Principal principal = Mockito.mock(Principal.class);
        DomainDetails domainDetails = new DomainDetails()
                .setGcpProjectId("gcp-project")
                .setGcpProjectNumber("gcp-project-number");

        ExternalCredentialsRequest request = new ExternalCredentialsRequest();
        Map<String, String> attributes = new HashMap<>();
        attributes.put(GcpAccessTokenProvider.GCP_SERVICE_ACCOUNT, "gcp-service");
        request.setAttributes(attributes);

        Authorizer authorizer = Mockito.mock(Authorizer.class);
        provider.setAuthorizer(authorizer);

        HttpDriver httpDriver = Mockito.mock(HttpDriver.class);
        HttpDriverResponse exchangeTokenResponse = new HttpDriverResponse(200, EXCHANGE_TOKEN_RESPONSE_STR, null);
        HttpDriverResponse accessTokenResponse = new HttpDriverResponse(403, ACCESS_TOKEN_ERROR_STR, null);
        when(httpDriver.doPostHttpResponse(any())).thenReturn(exchangeTokenResponse, accessTokenResponse);

        provider.setHttpDriver(httpDriver);
        when(authorizer.access(any(), any(), any(), any())).thenReturn(true);

        try {
            provider.getCredentials(principal, domainDetails, new ArrayList<>(), new IdToken(), signer, request);
            fail();
        } catch (ServerResourceException ex) {
            assertEquals(ex.getCode(), 403);
            assertTrue(ex.getMessage().contains("Permission 'iam.serviceAccounts.getAccessToken' denied on resource (or it may not exist)."));
        }
    }
}
