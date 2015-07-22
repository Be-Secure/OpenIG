/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openig.filter.oauth2.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.http.protocol.Response.newResponsePromise;
import static org.forgerock.json.fluent.JsonValue.array;
import static org.forgerock.json.fluent.JsonValue.field;
import static org.forgerock.json.fluent.JsonValue.json;
import static org.forgerock.json.fluent.JsonValue.object;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.net.URI;

import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.heap.HeapImpl;
import org.forgerock.openig.heap.Name;
import org.forgerock.openig.http.Exchange;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Unit tests for the ClientRegistration class. */
@SuppressWarnings("javadoc")
public class ClientRegistrationTest {

    private static final String AUTHORIZE_ENDPOINT = "/openam/oauth2/authorize";
    private static final String TOKEN_ENDPOINT = "/openam/oauth2/access_token";
    private static final String USER_INFO_ENDPOINT = "/openam/oauth2/userinfo";
    private static final String REGISTRATION_ENDPOINT = "/openam/oauth2/connect/register";
    private static final String SAMPLE_URI = "http://www.example.com:8089";

    private Exchange exchange;

    @Captor
    private ArgumentCaptor<Request> captor;

    @Mock
    private Handler handler;

    @BeforeMethod
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        exchange = new Exchange(null, new URI("path"));
    }

    @DataProvider
    private Object[][] validConfigurations() {
        return new Object[][] {
            /* Minimal configuration. */
            { json(object(
                    field("clientId", "OpenIG"),
                    field("clientSecret", "password"),
                    field("issuer", "myIssuer"),
                    field("scopes", array("openid")))) },
            /* With token end point using POST. */
            { json(object(
                    field("clientId", "OpenIG"),
                    field("clientSecret", "password"),
                    field("scopes", array("openid", "profile", "email", "address", "phone", "offline_access")),
                    field("issuer", "myIssuer"),
                    field("tokenEndpointUseBasicAuth", false))) },
            /* With redirect uris. */
            { json(object(
                    field("client_id", "OpenIG"),
                    field("client_secret", "password"),
                    field("scopes", array("openid", "profile")),
                    field("issuer", "myIssuer"),
                    field("redirect_uris", array("https://client.example.org/callback")))) }};
    }

    @DataProvider
    private Object[][] missingRequiredAttributes() {
        return new Object[][] {
            /* Missing clientId. */
            { json(object(
                    field("clientSecret", "password"),
                    field("scopes", array("openid")),
                    field("issuer", "myIssuer"),
                    field("redirectUris", array("https://client.example.org/callback")))) },
            /* Missing clientSecret. */
            { json(object(
                    field("clientId", "OpenIG"),
                    field("scopes", array("openid")),
                    field("issuer", "myIssuer"),
                    field("redirectUris", array("https://client.example.org/callback2")))) },
            /* Missing issuer. */
            { json(object(
                    field("clientId", "OpenIG"),
                    field("clientSecret", "password"),
                    field("scopes", array("openid")),
                    field("issuer", "notDeclaredIssuer"),
                    field("redirectUris", array("https://client.example.org/callback4")))) }};
    }

    @DataProvider
    private Object[][] errorResponseStatus() {
        return new Status[][] {
            { Status.BAD_REQUEST },
            { Status.BAD_GATEWAY } };
    }

    @Test(dataProvider = "missingRequiredAttributes", expectedExceptions = JsonValueException.class)
    public void shouldFailToCreateHeapletWhenRequiredAttributeIsMissing(final JsonValue config) throws Exception {
        final ClientRegistration.Heaplet heaplet = new ClientRegistration.Heaplet();
        heaplet.create(Name.of("myClientRegistration"), config, buildDefaultHeap());
    }

    @Test(dataProvider = "validConfigurations")
    public void shouldSucceedToCreateHeaplet(final JsonValue config) throws HeapException, Exception {
        final ClientRegistration.Heaplet heaplet = new ClientRegistration.Heaplet();
        final ClientRegistration cr = (ClientRegistration) heaplet.create(Name.of("myClientRegistration"),
                                                                          config,
                                                                          buildDefaultHeap());
        assertThat(cr.getClientId()).isEqualTo("OpenIG");
        assertThat(cr.getScopes()).contains("openid");
    }

    @Test(dataProvider = "validConfigurations")
    public void shouldReturnInlinedConfiguration(final JsonValue config) throws Exception {
        final ClientRegistration.Heaplet heaplet = new ClientRegistration.Heaplet();
        final ClientRegistration cr = (ClientRegistration) heaplet.create(Name.of("myClientRegistration"),
                                                                          config,
                                                                          buildDefaultHeap());

        assertThat(cr.getName()).isEqualTo("myClientRegistration");
        assertThat(cr.getClientId()).isEqualTo("OpenIG");
        assertThat(cr.getScopes()).contains("openid");
    }

    @Test
    public void shouldGetAccessToken() throws Exception {
        // given
        final String code = "sampleAuthorizationCodeForTestOnly";
        final String callbackUri = "shouldBeACallbackUri";
        final ClientRegistration cr = buildClientRegistration();
        final Response response = new Response();
        response.setStatus(Status.OK);
        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        // when
        cr.getAccessToken(exchange, code, callbackUri);

        // then
        verify(handler).handle(eq(exchange), captor.capture());
        final Request request = captor.getValue();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUri().toASCIIString()).isEqualTo(SAMPLE_URI + TOKEN_ENDPOINT);
        assertThat(request.getEntity().getString()).contains("grant_type=authorization_code",
                                                             "redirect_uri=" + callbackUri, "code=" + code);
    }

    @Test(dataProvider = "errorResponseStatus", expectedExceptions = OAuth2ErrorException.class)
    public void shouldFailToGetAccessTokenWhenReceiveErrorResponse(final Status errorResponseStatus) throws Exception {

        final Response response = new Response();
        response.setStatus(errorResponseStatus);
        response.setEntity(json(object(field("error", "Generated by tests"))));
        when(handler.handle(eq(exchange), any(Request.class))).thenReturn(newResponsePromise(response));

        buildClientRegistration().getAccessToken(exchange, "code", "callbackUri");
    }

    private ClientRegistration buildClientRegistration() throws Exception {
        final JsonValue config = json(object(field("clientId", "OpenIG"),
                                             field("clientSecret", "password"),
                                             field("issuer", "myIssuer"),
                                             field("scopes", array("openid"))));
        return new ClientRegistration(null,
                                      config,
                                      getIssuer(),
                                      handler);
    }

    private HeapImpl buildDefaultHeap() throws Exception {
        final HeapImpl heap = HeapUtilsTest.buildDefaultHeap();
        heap.put("myIssuer", getIssuer());
        return heap;
    }

    private Issuer getIssuer() throws Exception {
        final JsonValue configuration = json(object(
                field("authorizeEndpoint", SAMPLE_URI + AUTHORIZE_ENDPOINT),
                field("registrationEndpoint", SAMPLE_URI + REGISTRATION_ENDPOINT),
                field("tokenEndpoint", SAMPLE_URI + TOKEN_ENDPOINT),
                field("userInfoEndpoint", SAMPLE_URI + USER_INFO_ENDPOINT)));
        return new Issuer("myIssuer", configuration);
    }
}
