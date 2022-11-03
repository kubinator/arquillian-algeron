package org.arquillian.algeron.pact.provider.core.loader.pactbroker;

import au.com.dius.pact.pactbroker.PactBrokerConsumer;
import au.com.dius.pact.provider.broker.PactBrokerClient;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.arquillian.algeron.provider.spi.retriever.ContractsRetriever;

import static org.arquillian.algeron.configuration.RunnerExpressionParser.parseExpressions;
import static org.arquillian.algeron.configuration.RunnerExpressionParser.parseListExpression;

/**
 * Out-of-the-box implementation of {@link org.arquillian.algeron.provider.spi.retriever.ContractsRetriever} that
 * downloads pacts from Pact broker
 */
public class PactBrokerLoader implements ContractsRetriever {
    private String providerName;
    private PactBroker pactBroker;

    public PactBrokerLoader() {
    }

    public PactBrokerLoader(PactBroker pactBroker) {
        this.pactBroker = pactBroker;
    }

    @Override
    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    @Override
    public void configure(Map<String, Object> configuration) {
        this.pactBroker = new ExternallyConfiguredContractsPactBroker(configuration);
    }

    @Override
    public String getName() {
        return "pactbroker";
    }

    @Override
    public List<URI> retrieve() throws IOException {

        final PactBrokerClient pactBrokerClient = new PactBrokerClient(parseExpressions(pactBroker.url()), getAuthOptions());


        final List<String> tags = Arrays.stream(pactBroker.tags())
            .flatMap(tagString -> parseListExpression(tagString).stream()).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (tags.size() == 0) {

            final List<PactBrokerConsumer> consumerInfos = pactBrokerClient.fetchConsumers(this.providerName);
            return toUri(consumerInfos);
        } else {
            final List<URI> contracts = new ArrayList<>();
            for (String tag : tags) {
                contracts.addAll(
                    toUri(pactBrokerClient.fetchConsumersWithTag(this.providerName, tag)));
            }

            return contracts;
        }
    }

    private List<URI> toUri(List<PactBrokerConsumer> consumerInfos) {
        return consumerInfos.stream()
            .map(PactBrokerConsumer::getPactBrokerUrl)
            .map(URI::create)
            .collect(Collectors.toList());
    }

    private Map<String, ?> getAuthOptions() {
        final String pactBrokerUsername = parseExpressions(pactBroker.userame()).trim();
        final String pactBrokerPassword = parseExpressions(pactBroker.password()).trim();
        return "".equals(pactBrokerUsername) || "".equals(pactBrokerPassword) ? Collections.emptyMap() :
            Collections.singletonMap("authentication", Arrays.asList("basic", pactBrokerUsername, pactBrokerPassword));
    }

    static class ExternallyConfiguredContractsPactBroker implements PactBroker {

        static final String URL = "url";
        static final String USERNAME = "username";
        static final String PASSWORD = "password";
        static final String TAGS = "tags";

        String url;
        String username = "";
        String password = "";
        String[] tags = new String[0];

        public ExternallyConfiguredContractsPactBroker(Map<String, Object> configuration) {

            if (!configuration.containsKey(URL)) {
                throw new IllegalArgumentException(
                    String.format("To use Pact Broker Publisher you need to set %s of the broker", URL));
            }

            if (!(configuration.get(URL) instanceof String)) {
                throw new IllegalArgumentException(String.format(
                    "Pact Broker Publisher requires %s configuration property to be an String instead of %s", URL,
                    configuration.get(URL)));
            }

            url = (String) configuration.get(URL);

            if (configuration.containsKey(USERNAME)) {
                username = (String) configuration.get(USERNAME);
            }

            if (configuration.containsKey(PASSWORD)) {
                password = (String) configuration.get(PASSWORD);
            }
            if (configuration.containsKey(TAGS)) {
                Object tags = configuration.get(TAGS);

                if (tags instanceof String) {
                    String tagsString = (String) tags;
                    this.tags = tagsString.split(",");
                }

                if (tags instanceof Collection) {
                    Collection<String> tagsCollection = (Collection<String>) tags;
                    this.tags = tagsCollection.toArray(new String[tagsCollection.size()]);
                }
            }
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String userame() {
            return username;
        }

        @Override
        public String password() {
            return password;
        }

        @Override
        public String[] tags() {
            return tags;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return PactBroker.class;
        }
    }
}
