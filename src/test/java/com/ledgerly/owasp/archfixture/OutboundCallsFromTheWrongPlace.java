package com.ledgerly.owasp.archfixture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Deliberately wrong, and never called: fetches whatever URL it is given, from a package that is not
 * infrastructure. The SSRF test uses it to show that the ArchUnit rule fails when it should.
 */
public final class OutboundCallsFromTheWrongPlace {

    private OutboundCallsFromTheWrongPlace() {}

    public static String fetch(String userSuppliedUrl) throws Exception {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(userSuppliedUrl)).build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }
}
