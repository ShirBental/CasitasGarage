package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;

/** Reading {@code tailscale whois --json}; the shape is Tailscale 1.104's (apitype.WhoIsResponse). */
class CliTailscaleWhoisTest {

    private final CliTailscaleWhois whois = new CliTailscaleWhois("tailscale", Clock.systemUTC());

    @Test
    void aUserNodeIsItsStableIdNameAndLogin() {
        String output = """
                {"Node":{"ID":123,"StableID":"nAbC123CNTRL","Name":"shirs-iphone.example-tailnet.ts.net.","ComputedName":"shirs-iphone",
                 "Addresses":["100.64.0.11/32"]},
                 "UserProfile":{"ID":9,"LoginName":"shir@example.com","DisplayName":"Shir"},"CapMap":{}}""";

        assertThat(whois.parse(output)).contains(new TailscaleWhois.Node("nAbC123CNTRL", "shirs-iphone", "shir@example.com", false));
    }

    @Test
    void aTaggedNodeIsMarked() {
        String output = """
                {"Node":{"StableID":"nT1","ComputedName":"server","Tags":["tag:server"]},"UserProfile":{"LoginName":"tagged-devices"}}""";

        assertThat(whois.parse(output)).get().extracting(TailscaleWhois.Node::tagged).isEqualTo(true);
    }

    @Test
    void anythingIncompleteOrUnreadableIsUnknown() {
        assertThat(whois.parse("{\"Node\":{\"ComputedName\":\"x\"},\"UserProfile\":{\"LoginName\":\"a@b\"}}")).isEmpty();
        assertThat(whois.parse("{\"Node\":{\"StableID\":\"n1\"}}")).isEmpty();
        assertThat(whois.parse("no such peer")).isEmpty();
        assertThat(whois.parse("")).isEmpty();
    }

    @Test
    void aMissingTailscaleCommandIsUnknownNotAnError() {
        assertThat(new CliTailscaleWhois("/nonexistent/tailscale", Clock.systemUTC()).whois("100.64.0.11")).isEmpty();
    }
}
