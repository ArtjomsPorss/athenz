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
package io.athenz.server.aws.common.cert.impl;

import com.yahoo.athenz.auth.PrivateKeyStore;
import com.yahoo.athenz.common.server.ssh.SSHRecordStore;
import com.yahoo.athenz.common.server.ssh.SSHRecordStoreFactory;
import com.yahoo.athenz.db.dynamodb.DynamoDBClientFetcher;
import com.yahoo.athenz.db.dynamodb.DynamoDBClientFetcherFactory;
import com.yahoo.athenz.common.server.ServerResourceException;
import io.athenz.server.aws.common.notification.impl.ZTSClientNotificationSenderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class DynamoDBSSHRecordStoreFactory implements SSHRecordStoreFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamoDBSSHRecordStoreFactory.class);

    public static final String ZTS_PROP_SSH_DYNAMODB_TABLE_NAME = "athenz.zts.ssh_dynamodb_table_name";

    @Override
    public SSHRecordStore create(PrivateKeyStore keyStore) throws ServerResourceException {

        final String tableName = System.getProperty(ZTS_PROP_SSH_DYNAMODB_TABLE_NAME);
        if (tableName == null || tableName.isEmpty()) {
            LOGGER.error("SSH Store DynamoDB table name not specified");
            throw new ServerResourceException(ServerResourceException.SERVICE_UNAVAILABLE,
                    "DynamoDB ssh table name not specified");
        }

        ZTSClientNotificationSenderImpl ztsClientNotificationSender = new ZTSClientNotificationSenderImpl();
        DynamoDbClient client = getDynamoDBClient(ztsClientNotificationSender, keyStore);
        return new DynamoDBSSHRecordStore(client, tableName, ztsClientNotificationSender);
    }

    DynamoDbClient getDynamoDBClient(ZTSClientNotificationSenderImpl ztsClientNotificationSender, PrivateKeyStore keyStore) {
        DynamoDBClientFetcher dynamoDBClientFetcher = DynamoDBClientFetcherFactory.getDynamoDBClientFetcher();
        ZTSDynamoDBClientSettingsFactory ztsDynamoDBClientSettingsFactory = new ZTSDynamoDBClientSettingsFactory(keyStore);
        return dynamoDBClientFetcher.getDynamoDBClient(ztsClientNotificationSender,
                ztsDynamoDBClientSettingsFactory.getDynamoDBClientSettings(false)).getDynamoDbClient();
    }
}
