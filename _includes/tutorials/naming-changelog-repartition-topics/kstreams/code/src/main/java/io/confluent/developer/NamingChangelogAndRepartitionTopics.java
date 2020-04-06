package io.confluent.developer;


import io.confluent.common.utils.TestUtils;
import io.confluent.developer.avro.Example;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;

public class NamingChangelogAndRepartitionTopics {


  public Properties buildStreamsProperties(Properties envProps) {
    Properties props = new Properties();

    props.put(StreamsConfig.APPLICATION_ID_CONFIG, envProps.getProperty("application.id"));
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, envProps.getProperty("bootstrap.servers"));
    props.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getPath());
    props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, envProps.getProperty("schema.registry.url"));

    return props;
  }

  public Topology buildTopology(Properties envProps) {
    final StreamsBuilder builder = new StreamsBuilder();
    final String inputTopic = envProps.getProperty("input.topic.name");
    final String outputTopic = envProps.getProperty("output.topic.name");
    final Serde<Long> longSerde = getPrimitiveAvroSerde(envProps, true);
    final Serde<Example> exampleSerde = getSpecificAvroSerde(envProps);

    final boolean addFilter = Boolean.parseBoolean(envProps.getProperty("add.filter"));
    final boolean addNames = Boolean.parseBoolean(envProps.getProperty("add.names"));

    KStream<Long, Example> exampleStream = builder.stream(inputTopic, Consumed.with(longSerde, exampleSerde))
                                                  .selectKey((k, v) -> Long.parseLong(v.getName().substring(0, 1)));
    if (addFilter) {
      exampleStream = exampleStream.filter((k, v) -> k != 1L);
    }

    final KStream<Long, String> joinedStream;

    if (!addNames) {
        final KStream<Long, Long> exampleCountStream = exampleStream.groupByKey()
                                                                    .count()
                                                                    .toStream();

        joinedStream = exampleStream.join(exampleCountStream, (v1, v2) -> v1.getName() + v2.toString(),
                                                              JoinWindows.of(Duration.ofMillis(100)),
                                                              StreamJoined.with(longSerde, exampleSerde, longSerde));
    } else {
        final KStream<Long, Long> exampleCountStream = exampleStream.groupByKey(Grouped.as("count"))
                                                                    .count(Materialized.as("count-store"))
                                                                    .toStream();

        joinedStream = exampleStream.join(exampleCountStream, (v1, v2) -> v1.getName() + v2.toString(),
                                                              JoinWindows.of(Duration.ofMillis(100)),
                                                              StreamJoined.with(Serdes.Long(), exampleSerde, Serdes.Long())
                                                                          .withName("join").withStoreName("join-store"));
    }

    joinedStream.to(outputTopic, Produced.with(Serdes.Long(), Serdes.String()));

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  static <T> Serde<T> getPrimitiveAvroSerde(final Properties envProps, boolean isKey) {
    final KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();
    final KafkaAvroSerializer serializer = new KafkaAvroSerializer();
    final Map<String, String> config = new HashMap<>();
    config.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
        envProps.getProperty("schema.registry.url"));
    deserializer.configure(config, isKey);
    serializer.configure(config, isKey);
    return (Serde<T>) Serdes.serdeFrom(serializer, deserializer);
  }

  static <T extends SpecificRecord> SpecificAvroSerde<T> getSpecificAvroSerde(
      final Properties envProps) {
    final SpecificAvroSerde<T> specificAvroSerde = new SpecificAvroSerde<>();

    final HashMap<String, String> serdeConfig = new HashMap<>();
    serdeConfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
        envProps.getProperty("schema.registry.url"));

    specificAvroSerde.configure(serdeConfig, false);
    return specificAvroSerde;
  }

  public void createTopics(final Properties envProps) {
    final Map<String, Object> config = new HashMap<>();
    config.put("bootstrap.servers", envProps.getProperty("bootstrap.servers"));
    try (final AdminClient client = AdminClient.create(config)) {

      final List<NewTopic> topics = new ArrayList<>();

      topics.add(new NewTopic(
          envProps.getProperty("input.topic.name"),
          Integer.parseInt(envProps.getProperty("input.topic.partitions")),
          Short.parseShort(envProps.getProperty("input.topic.replication.factor"))));

      topics.add(new NewTopic(
          envProps.getProperty("output.topic.name"),
          Integer.parseInt(envProps.getProperty("output.topic.partitions")),
          Short.parseShort(envProps.getProperty("output.topic.replication.factor"))));

      client.createTopics(topics);
    }
  }

  public Properties loadEnvProperties(String fileName) throws IOException {
    final Properties envProps = new Properties();
    final FileInputStream input = new FileInputStream(fileName);
    envProps.load(input);
    input.close();

    return envProps;
  }

  public static void main(String[] args) throws Exception {

    if (args.length < 1) {
      throw new IllegalArgumentException(
          "This program takes one argument: the path to an environment configuration file.");
    }

    final NamingChangelogAndRepartitionTopics instance = new NamingChangelogAndRepartitionTopics();
    final Properties envProps = instance.loadEnvProperties(args[0]);
    // make sure we use named topology for running the application
    envProps.put("name.topology", "true");
    final Properties streamProps = instance.buildStreamsProperties(envProps);
    final Topology topology = instance.buildTopology(envProps);

    instance.createTopics(envProps);

    final KafkaStreams streams = new KafkaStreams(topology, streamProps);
    final CountDownLatch latch = new CountDownLatch(1);

    // Attach shutdown handler to catch Control-C.
    Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
      @Override
      public void run() {
        streams.close(Duration.ofSeconds(5));
        latch.countDown();
      }
    });

    try {
      streams.start();
      latch.await();
    } catch (Throwable e) {
      System.exit(1);
    }
    System.exit(0);
  }

}
