/**
 * Copyright 2014 Confluent Inc.
 *
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
package io.confluent.kafka.schemaregistry.storage;

import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.cluster.Broker;
import kafka.log.LogConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.protocol.SecurityProtocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.confluent.kafka.schemaregistry.ClusterTestHarness;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreInitializationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class KafkaStoreTest extends ClusterTestHarness {

  private static final Logger log = LoggerFactory.getLogger(KafkaStoreTest.class);

  @Before
  public void setup() {
    log.debug("Zk conn url = " + zkConnect);
  }

  @After
  public void teardown() {
    log.debug("Shutting down");
  }

  @Test
  public void testInitialization() throws Exception {
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect);
    kafkaStore.close();
  }

  @Test(expected = StoreInitializationException.class)
  public void testDoubleInitialization() throws Exception {
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect);
    try {
      kafkaStore.init();
    } finally {
      kafkaStore.close();
    }
  }

  @Test
  public void testSimplePut() throws Exception {
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect);
    String key = "Kafka";
    String value = "Rocks";
    try {
      kafkaStore.put(key, value);
      String retrievedValue = kafkaStore.get(key);
      assertEquals("Retrieved value should match entered value", value, retrievedValue);
    } finally {
      kafkaStore.close();
    }
  }

  // TODO: This requires fix for https://issues.apache.org/jira/browse/KAFKA-1788
//  @Test
//  public void testPutRetries() throws InterruptedException {
//    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect,
//                                                                                       zkClient);
//    String key = "Kafka";
//    String value = "Rocks";
//    try {
//      kafkaStore.put(key, value);
//    } catch (StoreException e) {
//      fail("Kafka store put(Kafka, Rocks) operation failed");
//    }
//    String retrievedValue = null;
//    try {
//      retrievedValue = kafkaStore.get(key);
//    } catch (StoreException e) {
//      fail("Kafka store get(Kafka) operation failed");
//    }
//    assertEquals("Retrieved value should match entered value", value, retrievedValue);
//    // stop the Kafka servers
//    for (KafkaServer server : servers) {
//      server.shutdown();
//    }
//    try {
//      kafkaStore.put(key, value);
//      fail("Kafka store put(Kafka, Rocks) operation should fail");
//    } catch (StoreException e) {
//      // expected since the Kafka producer will run out of retries
//    }
//    kafkaStore.close();
//  }

  @Test
  public void testSimpleGetAfterFailure() throws Exception {
    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(
        zkConnect,
        inMemoryStore
    );
    String key = "Kafka";
    String value = "Rocks";
    String retrievedValue = null;
    try {
      try {
        kafkaStore.put(key, value);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store put(Kafka, Rocks) operation failed", e);
      }
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertEquals("Retrieved value should match entered value", value, retrievedValue);
    } finally {
      kafkaStore.close();
    }

    // recreate kafka store
    kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore);
    try {
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertEquals("Retrieved value should match entered value", value, retrievedValue);
    } finally {
      kafkaStore.close();
    }
  }

  @Test
  public void testSimpleDelete() throws Exception {
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect);
    String key = "Kafka";
    String value = "Rocks";
    try {
      try {
        kafkaStore.put(key, value);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store put(Kafka, Rocks) operation failed", e);
      }
      String retrievedValue = null;
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertEquals("Retrieved value should match entered value", value, retrievedValue);
      try {
        kafkaStore.delete(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store delete(Kafka) operation failed", e);
      }
      // verify that value is deleted
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertNull("Value should have been deleted", retrievedValue);
    } finally {
      kafkaStore.close();
    }
  }

  @Test
  public void testDeleteAfterRestart() throws Exception {
    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();
    KafkaStore<String, String> kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(
        zkConnect,
        inMemoryStore
    );
    String key = "Kafka";
    String value = "Rocks";
    try {
      try {
        kafkaStore.put(key, value);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store put(Kafka, Rocks) operation failed", e);
      }
      String retrievedValue = null;
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertEquals("Retrieved value should match entered value", value, retrievedValue);
      // delete the key
      try {
        kafkaStore.delete(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store delete(Kafka) operation failed", e);
      }
      // verify that key is deleted
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertNull("Value should have been deleted", retrievedValue);
      kafkaStore.close();
      // recreate kafka store
      kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore);
      // verify that key still doesn't exist in the store
      retrievedValue = value;
      try {
        retrievedValue = kafkaStore.get(key);
      } catch (StoreException e) {
        throw new RuntimeException("Kafka store get(Kafka) operation failed", e);
      }
      assertNull("Value should have been deleted", retrievedValue);
    } finally {
      kafkaStore.close();
    }
  }

  @Test
  public void testFilterBrokerEndpointsSinglePlaintext() {
    String endpoint = "PLAINTEXT://hostname:1234";
    List<String> endpointsList = new ArrayList<String>();
    endpointsList.add(endpoint);
    assertEquals("Expected one PLAINTEXT endpoint for localhost", endpoint,
            KafkaStore.endpointsToBootstrapServers(endpointsList, SecurityProtocol.PLAINTEXT.toString()));
  }

  @Test(expected = ConfigException.class)
  public void testGetBrokerEndpointsEmpty() {
    KafkaStore.endpointsToBootstrapServers(new ArrayList<String>(), SecurityProtocol.PLAINTEXT.toString());
  }

  @Test(expected = ConfigException.class)
  public void testGetBrokerEndpointsNoSecurityProtocolMatches() {
    KafkaStore.endpointsToBootstrapServers(Collections.singletonList("SSL://localhost:1234"), SecurityProtocol.PLAINTEXT.toString());
  }

  @Test(expected = ConfigException.class)
  public void testGetBrokerEndpointsUnsupportedSecurityProtocol() {
    KafkaStore.endpointsToBootstrapServers(Collections.singletonList("TRACE://localhost:1234"), "TRACE");
  }

  @Test
  public void testGetBrokerEndpointsMixed() throws IOException {
    List<String> endpointsList = new ArrayList<String>(4);
    endpointsList.add("PLAINTEXT://localhost0:1234");
    endpointsList.add("PLAINTEXT://localhost1:1234");
    endpointsList.add("SASL_PLAINTEXT://localhost1:1235");
    endpointsList.add("SSL://localhost1:1236");
    endpointsList.add("SASL_SSL://localhost2:1234");
    endpointsList.add("TRACE://localhost3:1234");

    assertEquals("PLAINTEXT://localhost0:1234,PLAINTEXT://localhost1:1234",
            KafkaStore.endpointsToBootstrapServers(endpointsList, SecurityProtocol.PLAINTEXT.toString()));

    assertEquals("SASL_PLAINTEXT://localhost1:1235",
            KafkaStore.endpointsToBootstrapServers(endpointsList, SecurityProtocol.SASL_PLAINTEXT.toString()));

    assertEquals("SSL://localhost1:1236",
            KafkaStore.endpointsToBootstrapServers(endpointsList, SecurityProtocol.SSL.toString()));

    assertEquals("SASL_SSL://localhost2:1234",
            KafkaStore.endpointsToBootstrapServers(endpointsList, SecurityProtocol.SASL_SSL.toString()));
  }

  @Test
  public void testBrokersToEndpoints() {
    List<Broker> brokersList = new ArrayList<Broker>(4);
    brokersList.add(new Broker(0, "localhost", 1, new ListenerName("CLIENT"), SecurityProtocol.PLAINTEXT));
    brokersList.add(new Broker(1, "localhost1", 12, ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT), SecurityProtocol.PLAINTEXT));
    brokersList.add(new Broker(2, "localhost2", 123, new ListenerName("SECURE_REPLICATION"), SecurityProtocol.SASL_PLAINTEXT));
    brokersList.add(new Broker(2, "localhost2", 123, ListenerName.forSecurityProtocol(SecurityProtocol.SASL_PLAINTEXT), SecurityProtocol.SASL_PLAINTEXT));
    brokersList.add(new Broker(3, "localhost3", 1234, ListenerName.forSecurityProtocol(SecurityProtocol.SSL), SecurityProtocol.SSL));
    List<String> endpointsList = KafkaStore.brokersToEndpoints((brokersList));

    List<String> expected = new ArrayList<String>(4);
    expected.add("PLAINTEXT://localhost:1");
    expected.add("PLAINTEXT://localhost1:12");
    expected.add("SASL_PLAINTEXT://localhost2:123");
    expected.add("SASL_PLAINTEXT://localhost2:123");
    expected.add("SSL://localhost3:1234");

    assertEquals("Expected the same size list.", expected.size(), endpointsList.size());

    for (int i = 0; i < endpointsList.size(); i++) {
      assertEquals("Expected a different endpoint", expected.get(i), endpointsList.get(i));
    }
  }

  @Test
  public void testCustomGroupIdConfig() throws Exception {
    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();
    String groupId = "test-group-id";
    Properties props = new Properties();
    props.put(SchemaRegistryConfig.KAFKASTORE_GROUP_ID_CONFIG, groupId);
    KafkaStore kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore, props);

    assertEquals(kafkaStore.getKafkaStoreReaderThread().getConsumerProperty(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG), groupId);
  }


  @Test
  public void testDefaultGroupIdConfig() throws Exception {
    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();
    Properties props = new Properties();
    KafkaStore kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore, props);

    assertTrue(kafkaStore.getKafkaStoreReaderThread().getConsumerProperty(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG).startsWith("schema-registry-"));
  }

  @Test(expected=StoreInitializationException.class)
  public void testMandatoryCompationPolicy() throws Exception {
    Properties kafkaProps = new Properties();
    Properties topicProps = new Properties();
    topicProps.put(LogConfig.CleanupPolicyProp(), "delete");

    AdminUtils.createTopic(zkUtils, SchemaRegistryConfig.DEFAULT_KAFKASTORE_TOPIC, 1, 1, topicProps, RackAwareMode.Enforced$.MODULE$);

    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();

    KafkaStore kafkaStore = StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore, kafkaProps);
  }

  @Test(expected=StoreInitializationException.class)
  public void testTooManyPartitions() throws Exception {
    Properties kafkaProps = new Properties();
    Properties topicProps = new Properties();
    topicProps.put(LogConfig.CleanupPolicyProp(), "compact");

    AdminUtils.createTopic(zkUtils, SchemaRegistryConfig.DEFAULT_KAFKASTORE_TOPIC, 3, 1,
                           topicProps, RackAwareMode.Enforced$.MODULE$);

    Store<String, String> inMemoryStore = new InMemoryStore<String, String>();

    StoreUtils.createAndInitKafkaStoreInstance(zkConnect, inMemoryStore, kafkaProps);
  }

}
